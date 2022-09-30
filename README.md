# Quine, graph data that Scales Past 1 Million Events/Second

> **Note**: This repo is provided as a technical reference for the the [Scaling Quine Streaming Graph to Process 1 Million Events/Second](https://www.thatdot.com/blog/scaling-quine-streaming-graph-to-process-1-million-events-sec) blog. If you are interested in running the test yourself, please reach out to us in [Slack](https://github.com/thatdot/quine) for configuration assistance. 

Finding relationships within categorical data is graph's strong point. Doing so at scale, as Quine now makes possible, has significant implications for cyber security, fraud detection, observability, logistics, e-commerce, and any use case that graph is both well-suited for and which must process high velocity data in real time.

The goal of this test is to demonstrate a high-volume of sustained event ingest, that is resilient to cluster node failure in both Quine and the persister using commodity infrastructure, and to share performance and cost results along with details of the test for those interested in either reproducing results or running Quine in production.

![](https://uploads-ssl.webflow.com/61f0aecf55af2565526f6a95/632fcc0a24771a2089714ba0_Pasted%20image%2020220923174204.png)

Our tests delivered the following results:

* 1M events/second processed for a 2 hour period
* 1M+ writes per second
* 1M 4-node graph traversals (reads) per second
* 21K results (4-node pattern matches) emitted per second
* 140 commodity hosts plus 1 hot spare running Quine Enterprise
* 66 storage hosts using Apache Cassandra persistor
* 3 hosts for Apache Kafka

## Infrastructure

The Cassandra persistor layer’s settings are set at a TTL of 15 minutes and a replication factor of 1 to manage quota limits and spending on cloud infrastructure. This does not fit every possible use case, but it is fairly common. Other scenarios which are more data-storage oriented will often increase the replication factor and/or TTL. In those variations, maintaining the 1 million events/sec processing rate would require increasing the number of Cassandra hosts or disk storage, both of which are budgetary concerns more than technical concerns.

<table class="unstyledTable" style="padding: 20px;">
   <thead>
     <tr>
       <th style="width: 15%;">
         Component
       </th>
       <th style="width: 15%;"># of Hosts</th>
   <th style="width: 35%;">Host Types</th>
     </tr>
   </thead>
   <tbody>
     <tr>
       <td style="text-align: center;">Quine Cluster</td>
       <td style="text-align: center;">141</td>
       <td>
         <ul>
           <li>c2-standard-30 (30 vCPUs, 120GB RAM)</li>
           <li>Max heap for JVM set to 12GB</li>
           <li>140 cluster size w/ 1 hot spare</li>
         </ul>
       </td>
     </tr>
     <tr>
       <td style="text-align: center;">Cassandra Persistor Cluster</td>
       <td style="text-align: center;">66</td>
       <td>
         <ul>
         <li>n1-highmem-32 (32 vCPU, 208GB RAM)</li>
         <li>x 375 GB local SSD each</li>
         <li>r1 x 375 GB local SSD each</li>
         <li>durable_writes=false</li>
         <li>TTL=15 minutes on snapshots (to control disk costs in testing) and journals tables</li>
         </ul>
       </td>
     </tr>
     <tr>
       <td style="text-align: center;">Kafka</td>
       <td style="text-align: center;">3</td>
       <td>
          <ul>
         <li>n2-standard-4 (4 vCPU, 16 GB RAM)</li>
         <li>Preloaded with 8 billion events (sufficient for a sustained 2 hour ingest at 1 million events per second)</li>
         <li>420 partitions</li>
          </ul>
       </td>
     </tr>
   </tbody>
 </table>

### The Test

The plan is set out below, with each action labeled and the results explained. Events are clearly marked by sequence # on the Grafana screen grabs below the table.

A few notes on the test:

* A script is used to generate events
* Host failures are manually triggered.
* We used Grafana for the results (and screenshots).
* We pre-loaded Kafka with enough events to sustain **one million events/second** for two hours.
* A Cassandra cluster is used for persistent data storage. The Cassandra cluster is not over-provisioned to accommodate compaction intentionally (a common strategy) so that the effects of database maintenance on the ingest rate can be demonstrated.
* The cluster is run in a Kubernetes environment

<table class="unstyledTable" style="padding: 20px;">
  <thead>
    <tr>
      <th style="width: 5%;">Seq #</th>
      <th style="width: 20%;">Actions</th>
      <th style="width: 35%;">Expected Results</th>
      <th>Actual Results</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="text-align: center;">
        1
      </td>
      <td>
        Start the Quine cluster and begin ingest from Kafka
      </td>
      <td>
        The ingest rate increase and settle at or above 1 million events per second
      </td>
      <td>
        Observed
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        2
      </td>
      <td>
        Let Quine run for 40 minutes to establish a stable baseline
      </td>
      <td>
        Quine does not fail and maintains a baseline ingest rate at or above 1 million events per second.
      </td>
      <td>
        Observed
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        3
      </td>
      <td>
        Kill a Quine host
      </td>
      <td>
        Quine ingest is not significantly impacted. The hot spare steps in to recover quickly, and Kubernetes replaces the killed host which becomes a new hot spare.
      </td>
      <td>
        Observed at 17:47. No impact to ingest rate. The hot spare recovered quickly and ingest was not impacted.
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        4
      </td>
      <td>
        Persistor Maintenance
      </td>
      <td>
        Cassandra regularly performs maintenance, Quine experiences this as increased latency and should backpressure the ingest to maintain stability during database maintenance.
      </td>
      <td>
        From 17:55 - 18:15 the ingest rate is reduced as a corresponding increase in latency is measured above 1ms across all nodes from the Cassandra persistor.
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        5
      </td>
      <td>
        Kill two Quine hosts
      </td>
      <td>
        Observe the following sequence: hot spare recovers one host, whole cluster suspends ingest due to being degraded, Kubernetes replaces killed hosts, first replaced host recovers the cluster, and the second replaced host becomes the new hot spare.
      </td>
      <td>
        Observed from 18:18 - 18:25. Due to Kubernetes the impact was not visible. However, the expected sequence was confirmed in the logs.
      </td>
    </tr>
    <tr class="c15">
      <td style="text-align: center;">
        6
      </td>
      <td>
        Stop and resume a Quine host for about 1 minute to inject high latency
      </td>
      <td>
        Quine detects the host is no longer available, boots it from the cluster, and hot spare steps in to recover. When the rejected host resumes, it learns it was removed from the cluster, so it shuts down, is restarted by Kubernetes, and to become the new hot spare
      </td>
      <td>
        Observed from 18:41 - 18:46. No impact to ingest rate as the back pressured ingest was for a single host in the cluster, and the recovery happened quickly.
      </td>
    </tr>
    <tr class="c15">
      <td style="text-align: center;">
        7
      </td>
      <td>
        Stop and resume a Cassandra persistor host for about 1 minute to inject high latency
      </td>
      <td>
        Quine back pressures ingest until Cassandra persistor has recovered
      </td>
      <td>
        Observed from 18:47 - 18:54. Due to replication factor = 1, ingest was impacted until Cassandra persistor recovered. Then ingest resumed to &gt; 1M e/s.
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        8
      </td>
      <td>
        Kill a Cassandra persistor host
      </td>
      <td>
        Quine suspends ingest until Cassandra persistor recovers with a new host
      </td>
      <td>
        Observed from 18:54 - 19:10. The host was recovered quickly due to kubernetes, and ingest briefly recovered to 1M e/s by 18:58 (only a few minutes).
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        9
      </td>
      <td>
        Persistor Maintenance
      </td>
      <td>
        Cassandra regularly performs maintenance, Quine experiences this as increased latency and should backpressure the ingest to maintain stability during database maintenance.
      </td>
      <td>
        From 17:55 - 18:15 the ingest rate is reduced as a corresponding increase in latency is measured above 1ms across all nodes from the Cassandra persistor.
      </td>
    </tr>
    <tr>
      <td style="text-align: center;">
        10
      </td>
      <td>
        Let Quine consume the remaining Kafka stream
      </td>
      <td>
        Observe the Quine hosts drop to zero events per second (not all at once)
      </td>
      <td class="c18">
        Observed from 19:10 - 19:35. Around the time Cassandra persistor latency was returning to 1ms, and ingest returned to 1M e/s, the pre-loaded ingest stream began to become exhausted on some hosts. For the following 20 minutes hosts exhausted their partitions in the stream.
      </td>
    </tr>
  </tbody>
</table>

## Standing Queries and 1 Million 4-node traversals per second

The purpose of running any complex event processor, Quine included, is in detecting and acting on high-value events in real time. This could mean detecting indications of a cyber attack, or video stream buffering, or identifying e-commerce upsell opportunities at check out. This is where Quine really excels.

Standing queries are a unique feature of Quine. They monitor streams for specified patterns, maintaining partial matches, and executing user-specified actions the instant a full match is made. Actions can include anything from updating the graph itself by creating new nodes or edges, writing results out to Kafka (or Kinesis, or posting results to a webhook).

In this test, Quine standing queries monitored for specific 4-node patterns requiring a 4-node traversal every time an event was ingested. Traditional graph databases slow down ingest when performing multi-node traversal. Not Quine. Quine’s ability to sustain high-speed data ingest together with simultaneous graph analysis is a revolutionary new capability. Not only did Quine ingest more than 1,000,000 events per second, it analyzed all that data in real-time to find more than 20,000 matches per second for complex graph patterns. This is a whole new world!

## Why Quine Hitting 1 Million Events/Sec Matters

Since its release in 2007 at the start of the NoSQL revolution, Neo4J have proven conclusively the value of graph to connect and find complex patterns in categorical data.

The graph data model is indispensable to everything from fraud detection to network observability to cybersecurity. It is used for recommendation engines, logistics, and XDR/EDR.

But not long after NoSQL hit the scene, Kafka kicked off the movement toward real-time event processing. Soon, event processors like Flink, Spark Streaming and ksqlDB brought the ability to process live streams. These systems relied on less-expressive key-value stores or slower document and relational databases to save intermediate data.

Quine is the graph analog and is important because now you can do what graph is really good at -- finding complex patterns across multiple streams of data using not just numerical but categorical data.

Quine makes all the great graph use cases viable at high volumes and in real time.
