#!/usr/bin/python3

import requests
from typing import *
from time import sleep, time

a_quine_host: str = "http://10.56.36.5:8080"

# Hosts that form the cluster
def get_quine_hosts():
    #for i, host in requests.get(f"{a_quine_host}/api/v1/admin/status").json()["cluster"]["clusterMembers"].items():
    #    print(i)
    #    print(host)
    return [(int(i), f"http://{host['address']}:8080") for i, host in requests.get(f"{a_quine_host}/api/v1/admin/status").json()["cluster"]["clusterMembers"].items()]
    
# Number of partitions in the processEvents and fooEvents kafka topics
kafka_partitions = 420
# kafka broker string
kafka_servers = ','.join([
     "10.128.15.218:9092", "10.128.15.219:9092", "10.128.15.220:9092"
])
if (len(kafka_servers) == 0):
    print("please specify kafka_servers in env.py")
    exit(-1)

# how many ingest queries to execute simultaneously (per-host)
ingest_parallelism = 96

# Protobuf schema
protobuf_schema = "https://thatdot-public.s3.us-west-2.amazonaws.com/schema.desc"

# Given a host index, retrieve the partitions that host should read from


def partitions(for_host: int) -> List[int]:
    return list(range(for_host, kafka_partitions, len(get_quine_hosts())))

# aggregate ingest rates across all quine_hosts

# Gets a lower bound on the ingest rate of the cluster


def overallIngestRate(which_ingest: str) -> float:
    totalIngested = 0
    longestMillis = 0
    for quine_host in dict(get_quine_hosts()).values():
        stats = requests.get(
            f"{quine_host}/api/v1/ingest/{which_ingest}").json()["stats"]
        totalIngested += stats["ingestedCount"]
        longestMillis = max(longestMillis, stats["totalRuntime"])
    return totalIngested / (longestMillis / 1000)

# Prints the full ingest stats block for the named ingest for each host of the cluster


def printIngestStatus(which_ingest: str) -> None:
    for quine_host in dict(get_quine_hosts()).values():
        stats = requests.get(
            f"{quine_host}/api/v1/ingest/{which_ingest}").json()["stats"]
        print(stats)

# reset the cluster back to its 'initial state', clearing out all data and settings


def resetCluster():
    resp = requests.post(f"{a_quine_host}/api/v1/admin/reset")
    if not resp.ok:
        print(resp.text)

"""
SQ1: Standing Queries / Event Multiplexing
==========================================

Using the same processEvents stream as before, we aim to extract 4-generation
process heirarchies. That is, patterns within the graph that would match this
cypher expression:

MATCH (child)-[:parent]->(parent)-[:parent]->(grandparent)-[:parent]->(greatGrandparent)

With Quine Enterprise's Standing Queries, you just ask the cluster to watch
for that pattern, tell it where you want matches to end up and the Quine graph takes
care of the rest! In this case, we'll send results to the "sq1Matches" kafka topic
Note that the sq1Matches topic has a retention policy of 10 minutes (in the original setup).

It is strongly recommended to register the standing query before beginning
ingest.

The minimal pattern to watch for is

```cypher
MATCH (p0)-[:parent]->(p1)-[:parent]->(p2)-[:parent]->(p3)
WHERE
    EXISTS(p0.customer_id) AND
    EXISTS(p0.sensor_id) AND
    EXISTS(p0.process_id) AND
    EXISTS(p0.filename) AND
    EXISTS(p0.command_line) AND
    EXISTS(p0.user_id) AND
    EXISTS(p0.timestamp_unix_utc) AND
    EXISTS(p0.sha256)
RETURN
    id(p0) AS id
```

Every time we get a match, we can run a second query to go collect all the fields of interest.

```cypher
MATCH (p0)-[:parent]->(p1)-[:parent]->(p2)-[:parent]->(p3)
WHERE id(p0) = $that.data.id
RETURN
    p0.customer_id as customer_id,
    p0.sensor_id as sensor_id,
    {
        process_id: p0.process_id,
        filename: p0.filename,
        command_line: p0.command_line,
        user_id: p0.user_id,
        timestamp_unix_utc: p0.timestamp_unix_utc,
        sha256: p0.sha256,
        parent_process: {
            process_id: p1.process_id,
            filename: p1.filename,
            command_line: p1.command_line,
            user_id: p1.user_id,
            timestamp_unix_utc: p1.timestamp_unix_utc,
            sha256: p1.sha256,
            parent_process: {
                process_id: p2.process_id,
                filename: p2.filename,
                command_line: p2.command_line,
                user_id: p2.user_id,
                timestamp_unix_utc: p2.timestamp_unix_utc,
                sha256: p2.sha256,
                parent_process: {
                    process_id: p3.process_id,
                    filename: p3.filename,
                    command_line: p3.command_line,
                    user_id: p3.user_id,
                    timestamp_unix_utc: p3.timestamp_unix_utc,
                    sha256: p3.sha256
                }
            }
        }
    } as process
```
"""


# To get the status of the standing query:
def printSq1Status():
    resp = requests.get(f"{a_quine_host}/api/v1/query/standing/sq1")
    if resp.ok:
        print(resp.text)  # this is JSON, but we want to print that JSON
    else:
        print(f"Unable to retrieve sq1 status: {resp.text}")


sq_out_parallelism = ingest_parallelism
if __name__ == "__main__":
    resp = requests.post(f"{a_quine_host}/api/v1/query/standing/sq1", json={
        "pattern": {
            "query": "MATCH (p0)-[:parent]->(p1)-[:parent]->(p2)-[:parent]->(p3) WHERE EXISTS(p0.customer_id) AND EXISTS(p0.sensor_id) AND EXISTS(p0.process_id) AND EXISTS(p0.filename) AND EXISTS(p0.command_line) AND EXISTS(p0.user_id) AND EXISTS(p0.timestamp_unix_utc) AND EXISTS(p0.sha256) RETURN DISTINCT id(p0) AS id",
            "type": "Cypher",
            "mode": "DistinctId"
        },
        "inputBufferSize": 8196,
        "outputs": {
            "to-kafka": {
                "type": "CypherQuery",
                "query": "MATCH (p0)-[:parent]->(p1)-[:parent]->(p2)-[:parent]->(p3) WHERE id(p0) = $that.data.id RETURN p0.customer_id as customer_id, p0.sensor_id as sensor_id, {  process_id: p0.process_id, filename: p0.filename, command_line: p0.command_line, user_id: p0.user_id, timestamp_unix_utc: p0.timestamp_unix_utc, sha256: p0.sha256, parent_process: { process_id: p1.process_id, filename: p1.filename, command_line: p1.command_line, user_id: p1.user_id, timestamp_unix_utc: p1.timestamp_unix_utc, sha256: p1.sha256, parent_process: { process_id: p2.process_id, filename: p2.filename, command_line: p2.command_line, user_id: p2.user_id, timestamp_unix_utc: p2.timestamp_unix_utc, sha256: p2.sha256, parent_process: { process_id: p3.process_id, filename: p3.filename, command_line: p3.command_line, user_id: p3.user_id, timestamp_unix_utc: p3.timestamp_unix_utc, sha256: p3.sha256 } } } } as process",
                "parallelism": sq_out_parallelism,
                "andThen": {
                    "type": "WriteToKafka",
                    "topic": "sq1Matches",
                    "bootstrapServers": kafka_servers,
                    "format": {
                        "type": "Protobuf",
                        "schemaUrl": protobuf_schema,
                        "typeName": "ProcessCombined"
                    }
                }
            }
        }
    })
    if resp.ok:
        print(
            f"Registered Standing Query on thatDot Quine Enterprise via {a_quine_host}")
    else:
        print(resp.text)

    # With the Standing Query registered, now is the perfect time to kick off the ingest.
    startG1G2Ingest()

    # We can guess at some results with the query UI:
    # CALL recentNodes(10000) YIELD node MATCH (n)-->(m)-->(o)-->(p) WHERE id(n) = node RETURN n, m, o, p LIMIT 1

    # You can also "listen in" on the matches made on any particular host by visiting that host's SSE endpoint
    # curl --silent {a_quine_host}/api/v1/query/standing/sq1/results | head -n 10

    # sleep for 30 seconds to let everything normalize
    print("Waiting 30 seconds to collect performance data...")
    sleep(30)
    # We can get the standing query definition and status via the API:
    printSq1Status()

    # Finally, to check the ingest rates, we can make the same API calls as before
    print(
        f"Current cluster-wide ingest rate: {overallIngestRate('g1g2'):,.0f}")

"""
G1: General Ingest (writes, minimal reads)
==========================================

We will be reading protobuf-formatted `ProcessEvents` from a kafka topic, and generating a forest of
process nodes. Because a forest is a set of trees, and each tree has one fewer edge than it has
nodes, this approximates a 1:1 relationship between nodes and edges.

ORIGINAL SETUP:

The topic contains 300 million events, partitioned by `sensor_id`. Quine Enterprise's internal host-mapping
scheme matches that of Kafka, so when the same partitioning key is used in `locIdFrom` as in Kafka,
few to no cross-host edges will be created. We go a step further and predetermine which partition's
data will end up on each host, so that during ingest, hosts shouldn't need to distribute much data.

Among those processEvents there are represented:
- 1 million sensor IDs
- 5 thousand customer IDs
- Sensor IDs are unique per-customer
- Process trees share the same sensor ID

For each processEvent, we want to use its ID to match the process it describes and that process's parent.
We then want to create an edge between the two. To do all this in cypher looks like the following:
"""

g1g2IngestQuery = (
    # generates an ID using determinsitic hashing for the process node
    """WITH locIdFrom($that.sensor_id, "process", $that.customer_id, $that.sensor_id, $that.process_id) AS pId """ +
    # retrieves the node (n) with that ID ("all nodes exist")
    """MATCH (n) WHERE id(n) = pId """ +
    # copies the processEvent onto the node n
    """SET n = $that WITH n """ +
    # stops the query here when the process is a root process
    """WHERE $that.parent_process_id <> 0 """ +
    # otherwise, repeats the "generate ID, retrieve node" pattern to find the parent process node
    """MATCH (m) WHERE id(m) = locIdFrom($that.sensor_id, "process", $that.customer_id, $that.sensor_id, $that.parent_process_id) """ +
    # creates the edge from the child to the parent
    """CREATE (n)-[:parent]->(m)"""
)

# Register the ingest on each host in the cluster


def startG1G2Ingest():
    # kafka group ID to use -- must be the same across all hosts but unique across different test runs (to avoid reusing committed offsets)
    groupId = str(int(time()))
    for i, quine_host in get_quine_hosts():
        resp = requests.post(f"{quine_host}/api/v1/ingest/g1g2", json={
            "type": "KafkaIngest",
            "topics": {
                "processEvents": partitions(i)
            },
            "bootstrapServers": kafka_servers,
            "groupId": groupId,
            "autoOffsetReset": "earliest",
            "parallelism": ingest_parallelism,
            "offsetCommitting": {
                "maxBatch": 1000,
                "maxIntervalMillis": 1000,
                "parallelism": 100,
                "waitForCommitConfirmation": False,
                "type": "ExplicitCommit"
            },
            "format": {
                "type": "CypherProtobuf",
                "query": g1g2IngestQuery,
                "schemaUrl": protobuf_schema,
                "typeName": "ProcessEvent"
            }
        })
        if resp.ok:
            print(f"Registered ingest g1g2 on {quine_host}")
        else:
            print(resp.text)

# To pause all the ingests:


def pauseG1G2():
    for quine_host in quine_hosts:
        resp = requests.put(f"{quine_host}/api/v1/ingest/g1g2/pause")
        if resp.ok:
            print(f"Paused ingest g1g2 on {quine_host}")
        else:
            print(resp.text)


# To resume all the ingests:
def resumeG1G2():
    for quine_host in dict(get_quine_hosts()).values():
        resp = requests.put(f"{quine_host}/api/v1/ingest/g1g2/start")
        if resp.ok:
            print(f"Resumed ingest g1g2 on {quine_host}")
        else:
            print(resp.text)

# To delete (ie, permanently stop) all the ingests:


def stopG1G2Ingest():
    for quine_host in dict(get_quine_hosts()).values():
        resp = requests.delete(f"{quine_host}/api/v1/ingest/g1g2")
        if resp.ok:
            print(f"Deleted ingest g1g2 on {quine_host}")
        else:
            print(resp.text)


if __name__ == "__main__":
    startG1G2Ingest()

    # sleep for 30 seconds to let everything normalize
    print("Waiting 30 seconds to collect performance data...")
    sleep(30)

    # With the ingest running, we can see data streaming into the system by repeatedly querying (via the web interface):
    # CALL recentNodes(10) YIELD node MATCH (n) WHERE id(n) = node AND n.process_id IS NOT NULL RETURN n

    # The insert rate is measured by the ingest streams themselves (see the "stats" section):
    print(f"Ingest on {a_quine_host}:")
    ingest_sample_response = requests.get(
        f"{a_quine_host}/api/v1/ingest/g1g2")
    print(ingest_sample_response.text)

    # We can aggregate all the interest rates to find out the cluster's total rate per second
    print(
        f"Current cluster-wide ingest rate: {overallIngestRate('g1g2'):,.0f}")
