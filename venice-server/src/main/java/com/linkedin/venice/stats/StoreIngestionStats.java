package com.linkedin.venice.stats;

import com.linkedin.venice.kafka.consumer.StoreIngestionTask;
import io.tehuti.metrics.MetricsRepository;
import io.tehuti.metrics.Sensor;
import io.tehuti.metrics.stats.Avg;
import io.tehuti.metrics.stats.Count;
import io.tehuti.metrics.stats.Max;
import io.tehuti.metrics.stats.Min;
import io.tehuti.metrics.stats.Rate;
import io.tehuti.metrics.stats.Total;
import java.util.function.Supplier;

import static com.linkedin.venice.stats.StatsErrorCode.*;

public class StoreIngestionStats extends AbstractVeniceStats{
  private StoreIngestionTask storeIngestionTask;

  private final Sensor bytesConsumedSensor;
  private final Sensor recordsConsumedSensor;
  private final Sensor storageQuotaUsedSensor;

  private final Sensor pollRequestSensor;
  private final Sensor pollRequestLatencySensor;
  private final Sensor pollResultNumSensor;
  private final Sensor consumerRecordsQueuePutLatencySensor;
  private final Sensor keySizeSensor;
  private final Sensor valueSizeSensor;

  private final Sensor unexpectedMessageSensor;
  private final Sensor inconsistentStoreMetadataSensor;

  private final Sensor ingestionFailureSensor;

  /**
   * A gauge reporting the total the percentage of hybrid quota used.
   */
  private double hybridQuotaUsageGauge;

  // Measure the avg/max time we need to spend on waiting for the leader producer
  private final Sensor leaderProducerSynchronizeLatencySensor;
  // Measure the avg/max latency for data lookup and deserialization
  private final Sensor leaderWriteComputeLookUpLatencySensor;
  // Measure the avg/max latency for the actual write computation
  private final Sensor leaderWriteComputeUpdateLatencySensor;

  public StoreIngestionStats(MetricsRepository metricsRepository,
                             String storeName) {
    super(metricsRepository, storeName);
    this.storeIngestionTask = null;

    bytesConsumedSensor = registerSensor("bytes_consumed", new Rate());
    recordsConsumedSensor = registerSensor("records_consumed", new Rate());

    // Measure latency of Kafka consumer poll request and processing returned consumer records
    pollRequestSensor = registerSensor("kafka_poll_request", new Count());
    pollRequestLatencySensor = registerSensor("kafka_poll_request_latency", new Avg());
    // consumer record number per second returned by Kafka consumer poll.
    pollResultNumSensor = registerSensor("kafka_poll_result_num", new Avg(), new Total());
    // To measure 'put' latency of consumer records blocking queue
    consumerRecordsQueuePutLatencySensor = registerSensor("consumer_records_queue_put_latency", new Avg(), new Max());

    String keySizeSensorName = "record_key_size_in_bytes";
    keySizeSensor = registerSensor(keySizeSensorName, new Avg(), new Min(), new Max(),
        TehutiUtils.getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + keySizeSensorName, 40000, 1000000));

    String valueSizeSensorName = "record_value_size_in_bytes";
    valueSizeSensor = registerSensor(valueSizeSensorName, new Avg(), new Min(), new Max(),
        TehutiUtils.getPercentileStat(getName() + AbstractVeniceStats.DELIMITER + valueSizeSensorName, 40000, 1000000));

    unexpectedMessageSensor = registerSensor("unexpected_message", new Rate());
    inconsistentStoreMetadataSensor = registerSensor("inconsistent_store_metadata", new Count());


    ingestionFailureSensor = registerSensor("ingestion_failure", new Count());

    storageQuotaUsedSensor = registerSensor("storage_quota_used",
                                            new Gauge(() -> hybridQuotaUsageGauge), new Avg(), new Min(), new Max());

    leaderProducerSynchronizeLatencySensor = registerSensor("leader_producer_synchronize_latency", new Avg(), new Max());
    leaderWriteComputeLookUpLatencySensor = registerSensor("leader_write_compute_lookup_latency", new Avg(), new Max());
    leaderWriteComputeUpdateLatencySensor = registerSensor("leader_write_compute_update_latency", new Avg(), new Max());
  }

  public void updateStoreConsumptionTask(StoreIngestionTask task) {
    storeIngestionTask = task;

    //TODO: It would be much better to apply versioned stats pattern for these metrics.
    if (task.isHybridMode()) {
      registerSensor("largest_version_kafka_real_time_buffer_offset_lag", new StoreIngestionStatsCounter(this,
          () -> storeIngestionTask.getRealTimeBufferOffsetLag()));
      registerSensor("largest_version_number_of_partitions_not_receive_SOBR", new StoreIngestionStatsCounter(this,
          () -> storeIngestionTask.getNumOfPartitionsNotReceiveSOBR()));
    }
  }

  public StoreIngestionTask getStoreIngestionTask() {
    return storeIngestionTask;
  }

  public void recordBytesConsumed(long bytes) {
    bytesConsumedSensor.record(bytes);
  }

  public void recordRecordsConsumed(int count) {
    recordsConsumedSensor.record(count);
  }

  public void recordStorageQuotaUsed(double quotaUsed) {
    hybridQuotaUsageGauge = quotaUsed;
    storageQuotaUsedSensor.record(quotaUsed);
  }

  public void recordPollRequestLatency(double latency) {
    pollRequestSensor.record();
    pollRequestLatencySensor.record(latency);
  }

  public void recordPollResultNum(int count) {
    pollResultNumSensor.record(count);
  }

  public void recordConsumerRecordsQueuePutLatency(double latency) {
    consumerRecordsQueuePutLatencySensor.record(latency);
  }

  public void recordUnexpectedMessage(int count) {
    unexpectedMessageSensor.record(count);
  }

  public void recordInconsistentStoreMetadata(int count) { inconsistentStoreMetadataSensor.record(count); }

  public void recordKeySize(long bytes) {
    keySizeSensor.record(bytes);
  }

  public void recordValueSize(long bytes) {
    valueSizeSensor.record(bytes);
  }

  public void recordIngestionFailure() {
    ingestionFailureSensor.record();
  }

  public void recordLeaderProducerSynchronizeLatency(double latency) {
    leaderProducerSynchronizeLatencySensor.record(latency);
  }

  public void recordWriteComputeLookUpLatency(double latency) {
    leaderWriteComputeLookUpLatencySensor.record(latency);
  }

  public void recordWriteComputeUpdateLatency(double latency) {
    leaderWriteComputeUpdateLatencySensor.record(latency);
  }

  private static class StoreIngestionStatsCounter extends LambdaStat {
    StoreIngestionStatsCounter(StoreIngestionStats stats, Supplier<Long> supplier) {
      super(() -> {
        StoreIngestionTask task = stats.getStoreIngestionTask();
        if (task != null && task.isRunning()) {
          return (double) supplier.get();
        } else {
          return INACTIVE_STORE_INGESTION_TASK.code;
        }
      });
    }
  }
}
