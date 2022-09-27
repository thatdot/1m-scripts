# This "script" expects $KAFKA to be set
if [ -z "$KAFKA" ]; then echo "\$KAFKA is blank"; exit -1; fi;

export PARTITIONS=420
sh kafka-topics.sh --bootstrap-server "$KAFKA" --create --topic processEvents --config retention.ms=-1 --partitions $PARTITIONS
sh kafka-topics.sh --bootstrap-server "$KAFKA" --create --topic fooEvents --config retention.ms=-1 --partitions $PARTITIONS
# Because of the large customer/sensor sets, you may need to set Xmx when running the generator
# DATASET=process NUMBER_OF_EVENTS=500000000 java -Xmx1500m -jar generator.jar
# DATASET=foo NUMBER_OF_EVENTS=100000000 java -jar generator.jar

# This one's intentionally a single partition -- no need to overcomplicate
sh kafka-topics.sh --bootstrap-server "$KAFKA" --create --topic sq1Matches --config retention.ms=1800000
