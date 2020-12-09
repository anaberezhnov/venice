package com.linkedin.davinci.kafka.consumer;

import com.linkedin.davinci.kafka.consumer.StoreBufferService;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTask;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.utils.TestUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

public class StoreBufferServiceTest {
  private static int TIMEOUT_IN_MS = 1000;

  @Test
  public void testRun() throws Exception {
    StoreBufferService bufferService = new StoreBufferService(1, 10000, 1000);
    StoreIngestionTask mockTask = mock(StoreIngestionTask.class);
    String topic = TestUtils.getUniqueString("test_topic");
    int partition1 = 1;
    int partition2 = 2;
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr1 = new ConsumerRecord<>(topic, partition1, -1, null, null);
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr2 = new ConsumerRecord<>(topic, partition2, -1, null, null);
    bufferService.putConsumerRecord(cr1, mockTask, null);
    bufferService.putConsumerRecord(cr2, mockTask, null);

    bufferService.start();
    verify(mockTask, timeout(TIMEOUT_IN_MS)).processConsumerRecord(cr1, null);
    verify(mockTask, timeout(TIMEOUT_IN_MS)).processConsumerRecord(cr2, null);

    bufferService.stop();
  }

  @Test
  public void testRunWhenThrowException() throws Exception {
    StoreBufferService bufferService = new StoreBufferService(1, 10000, 1000);
    StoreIngestionTask mockTask = mock(StoreIngestionTask.class);
    String topic = TestUtils.getUniqueString("test_topic");
    int partition1 = 1;
    int partition2 = 2;
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr1 = new ConsumerRecord<>(topic, partition1, -1, null, null);
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr2 = new ConsumerRecord<>(topic, partition2, -1, null, null);
    Exception e = new VeniceException("test_exception");
    doThrow(e).when(mockTask)
        .processConsumerRecord(cr1, null);

    bufferService.putConsumerRecord(cr1, mockTask, null);
    bufferService.putConsumerRecord(cr2, mockTask, null);

    bufferService.start();
    verify(mockTask, timeout(TIMEOUT_IN_MS)).processConsumerRecord(cr1, null);
    verify(mockTask, timeout(TIMEOUT_IN_MS)).processConsumerRecord(cr2, null);
    verify(mockTask).setLastDrainerException(e);

    bufferService.stop();
  }

  @Test
  public void testDrainBufferedRecordsWhenNotExists() throws InterruptedException {
    StoreBufferService bufferService = new StoreBufferService(1, 10000, 1000);
    StoreIngestionTask mockTask = mock(StoreIngestionTask.class);
    String topic = TestUtils.getUniqueString("test_topic");
    int partition = 1;
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr = new ConsumerRecord<>(topic, partition, -1, null, null);
    bufferService.putConsumerRecord(cr, mockTask, null);
    int nonExistingPartition = 2;
    bufferService.internalDrainBufferedRecordsFromTopicPartition(topic, nonExistingPartition, 3, 50);
  }

  @Test (expectedExceptions = VeniceException.class)
  public void testDrainBufferedRecordsWhenExists() throws InterruptedException {
    StoreBufferService bufferService = new StoreBufferService(1, 10000, 1000);
    StoreIngestionTask mockTask = mock(StoreIngestionTask.class);
    String topic = TestUtils.getUniqueString("test_topic");
    int partition = 1;
    ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr = new ConsumerRecord<>(topic, partition, 100, null, null);
    bufferService.putConsumerRecord(cr, mockTask, null);
    bufferService.internalDrainBufferedRecordsFromTopicPartition(topic, partition, 3, 50);
    Assert.fail("Exception should be thrown here");
  }

  @Test
  public void testGetDrainerIndexForConsumerRecord() {
    String topic = TestUtils.getUniqueString("test_topic");
    int partitionCount = 64;
    int drainerNum = 8;
    int[] drainerPartitionCount = new int[drainerNum];
    for (int i = 0; i < drainerNum; ++i) {
      drainerPartitionCount[i] = 0;
    }
    StoreBufferService bufferService = new StoreBufferService(8, 10000, 1000);
    for (int partition = 0; partition < partitionCount; ++partition) {
      ConsumerRecord<KafkaKey, KafkaMessageEnvelope> cr = new ConsumerRecord<>(topic, partition, 100, null, null);
      int drainerIndex = bufferService.getDrainerIndexForConsumerRecord(cr);
      ++drainerPartitionCount[drainerIndex];
    }
    int avgPartitionCountPerDrainer = partitionCount / drainerNum;
    for (int i = 0; i < drainerNum; ++i) {
      Assert.assertEquals(drainerPartitionCount[i], avgPartitionCountPerDrainer);
    }
  }
}