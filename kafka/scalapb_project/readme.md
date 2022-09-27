# Requirements

- each process should have a parent id or 0
- almost all processes that have a non0 parent ID should refer to a process earlier in the stream
- every process and its parent should have the same sensorId and customerId
- Variety in size of process trees is desirable
- The scale constraints are:
  - there should be 100M total process events
  - there should be 1M distinct sensor IDs
  - every process event should have a unique process ID

# Usage

```bash
TOPIC=processEvents NUMBER_OF_EVENTS=100000000 NUMBER_OF_CUSTOMERS=5000 NUMBER_OF_SENSORS=1000000 ROOT_PROCESS_RARITY=100 AVERAGE_BRANCHING=3 DATASET=process sbt run
```
