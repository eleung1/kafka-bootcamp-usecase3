# Kafka Bootcamp use case 2: Filter and Aggregate

## Prerequisites
A working version of use case 1 producing to the 3 topics:
```
[your_team]-Transactions
[your_team]-Customers
[your_team]-Cities
```

## How to run
This is a spring boot project.  Check out the project to your machine.

### In application.properties, update these fields to match your settings:
```
# Source Transactions topic from use case 1
topic.trxn.name=[your_team]-Transactions

# Filtered topic containing transactions >= 1000 
topic.over1000.name=[your_team]-over-1000

# Average topic containing the rolling window average
topic.avg.name=[your_team]-avg

# Threshold used by the filter processor to filter out trxns by amount.
min.trxn.amount=1000

# Kafka uses RocksDB to store stream states.  
# DOES NOT WORK ON WINDOWS: It will try to create a directory name containing a colon.
stream.state.dir=/your/favourite/dir

# Same story as use case 1.  Jaas file for authentication.
writable.dir=/your/favourite/dir
```

### To run the project
```
mvn spring-boot:run
```


## Kafka Streams
Our Kafka Streams are defined in KafkaStreamConfig.java using the stream DSL.

We use StreamsBuilder to define a transaction stream for filter processing.  Writing the results to a Kafka topic.

Then we use a KTable to create 30-second sliding windows for averages.  Writing the results to a Kafka topic.

Finally, we have a KafkaStreamRunner class with a runStream() method that is executed after all dependency injections are done.

## High-level diagram
![Use case 2](diagrams/ca-kafka-business-rules-2.svg)


