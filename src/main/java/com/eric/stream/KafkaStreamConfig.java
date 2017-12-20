package com.eric.stream;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.Consumed;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.WallclockTimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.kafka.annotation.EnableKafka;

import com.eric.serde.WindowedSerde;
import com.rbc.cloud.hackathon.data.Customers;
import com.rbc.cloud.hackathon.data.Transactions;
import com.rbc.cloud.hackathon.data.TransactionsAvg;
import com.rbc.cloud.hackathon.data.TransactionsEnriched;

import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

/**
 * Kafka streams config.  Most values are defined in application.properties.
 * 
 * @author Eric Leung
 *
 */
@Configuration
@EnableKafka
@PropertySource(value = "classpath:application.properties")
public class KafkaStreamConfig {

  private Logger logger = LoggerFactory.getLogger(KafkaStreamConfig.class);
  
  @Resource
  private Environment env;
  
  @Autowired
  private ResourceLoader resourceLoader;
  
  @Bean
  public StreamsConfig kStreamsConfigs() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "ericUseCase2Streams");
    properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, env.getProperty("kafka.bootstrap.servers"));
    properties.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, WallclockTimestampExtractor.class.getName());
    properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
    
    /* Could not create internal topics: Could not create topic: 
     * ericUseCase2Streams-KSTREAM-AGGREGATE-STATE-STORE-0000000007-repartition due to Topic 
     * replication factor must be 3 Retry #0
     * -- Setting to 3
     */
    properties.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3); 
    
    //RocksDB is used internally by Kafka Streams to handle operator state -- and RocksDB write some files to disc.
    properties.put(StreamsConfig.STATE_DIR_CONFIG, env.getProperty("stream.state.dir"));
    
    // -- props from bootcamp starter examples - START
    properties.put("schema.registry.url",env.getProperty("schema.registry.url"));
    properties.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

    /* Kafka Streams does not allow users to set enable.auto.commit. 
     * Streams uses its own mechanism for committing offset instead of relying 
     * on the Consumer auto commit feature. This is to ensure commit are only done 
     * at certain points when the library is sure no data will be lost.
     */
    //properties.put("enable.auto.commit",env.getProperty("enable.auto.commit"));
    properties.put("session.timeout.ms",env.getProperty("session.timeout.ms"));
    properties.put("auto.offset.reset",env.getProperty("auto.offset.reset"));
    properties.put("fetch.max.wait.ms",env.getProperty("fetch.max.wait.ms"));
    properties.put("max.partition.fetch.bytes",env.getProperty("max.partition.fetch.bytes"));
    properties.put("max.poll.records",env.getProperty("max.poll.records"));
    properties.put("group.id",env.getProperty("group.id"));
    
    try {
        properties.put("client.id", InetAddress.getLocalHost().getHostName());
    } catch (Exception e) {
        logger.error("Could not set client.id - {}",e.getMessage());
    }

    properties.put("sasl.jaas.config", env.getProperty("sasl.jaas.config") );
    properties.put("sasl.mechanism", env.getProperty("sasl.mechanism") );
    properties.put("security.protocol", env.getProperty("security.protocol") );

    String writableDir=env.getProperty("writable.dir");
    String jaasFile=null;
    try {
        jaasFile= Util.writeJaasFile(new File(writableDir), env.getProperty("kafka.username"), env.getProperty("kafka.password"));
    }
    catch (Exception e) {
        String message="Error trying to write Jaas file - {}"+e.getMessage();
        logger.error(message);
        e.printStackTrace();
        throw new RuntimeException(message);
    }

    try {
        System.setProperty("java.security.auth.login.config",resourceLoader.getResource("file:/"+jaasFile).getURI().toString() );
    } catch (IOException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
    }
    // -- props from bootcamp starter examples - END
    
    
    return new StreamsConfig(properties);
  }

  /**
   * Define our stream using Kafka's stream DSL.  This stream will be started by KafkaStreamRunner.java.
   * 
   * @param kStreamsConfigs
   * @return A KafkaStreams objects with our desired stream processing topology.
   */
  @Bean
  public KafkaStreams kafkaStreams(StreamsConfig kStreamsConfigs) {
    
    // Getting our values from application.properties
    String trxnTopic = env.getProperty("topic.trxn.name");
    String custTopic = env.getProperty("topic.cust.name");
    //String avgTopic = env.getProperty("topic.avg.name");
    String minTrxnAmount = env.getProperty("min.trxn.amount");
    
    // Minimum transaction amount that we are keeping.  Throw away trxns strictly less than this amount.
    BigInteger MIN_TRXN_AMT = new BigInteger(minTrxnAmount);
    
    // ------- Serial/Deserializers: When you want to override serdes explicitly/selectively ------- //
    final Map<String, String> serdeConfig = Collections.singletonMap("schema.registry.url",
        env.getProperty("schema.registry.url"));
    
    // -- Source Serde
    // Trxn Serde
    final Serde<Transactions> valueSpecificAvroSerdeTransactions = new SpecificAvroSerde<>();
    valueSpecificAvroSerdeTransactions.configure(serdeConfig, false); // 'false' for record values, 'true' for keys
    
    // Customer Serde
    final Serde<Customers> valueSpecificAvroSerdeCustomers = new SpecificAvroSerde<>();
    valueSpecificAvroSerdeCustomers.configure(serdeConfig, false);
    
    // Avg Serde
    final Serde<String> stringSerde = Serdes.String();
    final Serde<Windowed<String>> windowedStringSerde = new WindowedSerde<>(stringSerde);
    windowedStringSerde.configure(serdeConfig, true); // 'true' for record values
    final Serde<TransactionsAvg> valueSpecificAvroSerdeAvg = new SpecificAvroSerde<>();
    valueSpecificAvroSerdeAvg.configure(serdeConfig, false);
    
    
    // ----------------- Stream definition starts here ----------------- //
    
    // Always start with a StreamBuilder to create a processing topology
    StreamsBuilder kStreamBuilder = new StreamsBuilder();
    
    // ---------- Filter trxn < 1000 ---------- //
    // Define our input stream with specific avro serde
    KStream<String, Transactions> trxnStream = kStreamBuilder.stream(trxnTopic, Consumed.with(Serdes.String(), valueSpecificAvroSerdeTransactions));
    
    KStream<String, Customers> custStream = kStreamBuilder.stream(custTopic, Consumed.with(Serdes.String(), valueSpecificAvroSerdeCustomers));
    
    // TODO: join the 2 streams...
    KStream<String, TransactionsEnriched> trxnCustStream = trxnStream
        .selectKey((k, v) -> v.getCustId().toString())
        .leftJoin(custStream, 
                  (trxn, cust) -> new TransactionsEnriched(trxn.getTransactionId(),
                                                           trxn.getCustId(),
                                                           trxn.getTransactionType(),
                                                           trxn.getTransactionAmount(),
                                                           cust.getFirstName(), 
                                                           cust.getLastName()),
                  JoinWindows.of(TimeUnit.MINUTES.toMillis(5)));
    
    /*
    // Filter: only keep trxn >= $1000
    KStream<String, Transactions> over1000Stream = trxnStream.filter((key, value) -> StringUtils.isNumeric(value.getTransactionAmount()))
                                                       .filter((key, value) -> MIN_TRXN_AMT.compareTo(
                                                           new BigInteger(value.getTransactionAmount().toString())) < 0); 
    */
    // Write results back to another kafka topic
    //over1000Stream.to(over1000Topic);
    
    // log to info for experiment purpose
    trxnCustStream.foreach(new ForeachAction<String, TransactionsEnriched>() {
      public void apply(String key, TransactionsEnriched value) {
        logger.info("TRXN-CUST-JOINED::" + key + ":[" + value+"]");
     }
    });
     
    // Write results back to another kafka topic
    // avgStream.to(avgTopic, Produced.with(windowedStringSerde, valueSpecificAvroSerdeAvg));
    
    // Construct the KafkaStreams instance based on the topology defined above
    KafkaStreams kafkaStreams = new KafkaStreams(kStreamBuilder.build(), kStreamsConfigs);
    return kafkaStreams;
  }
}
