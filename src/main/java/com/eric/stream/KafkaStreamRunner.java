package com.eric.stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * This class will start our stream after all dependencies are injected.
 * 
 * @author Eric Leung
 *
 */
@Component
public class KafkaStreamRunner {

  private Logger logger = LoggerFactory.getLogger(KafkaStreamRunner.class);
  
  @Autowired
  private KafkaStreams kafkaStreams;

  /**
   * Run our stream after all dependency injections are done.
   * 
   * The stream is defined in KafkaStreamConfig.java via DSL.
   * 
   */
  @PostConstruct
  public void runStream() {
    logger.info("*** STARTING STREAM!!! ***");
    kafkaStreams.start();
  }

  @PreDestroy
  public void closeStream() {
    kafkaStreams.close();
  }
}