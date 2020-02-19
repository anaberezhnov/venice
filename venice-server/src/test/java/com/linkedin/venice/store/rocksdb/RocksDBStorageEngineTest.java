package com.linkedin.venice.store.rocksdb;

import com.linkedin.venice.config.VeniceStoreConfig;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.server.VeniceConfigLoader;
import com.linkedin.venice.stats.AggVersionedBdbStorageEngineStats;
import com.linkedin.venice.stats.AggVersionedStorageEngineStats;
import com.linkedin.venice.storage.StorageService;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.store.AbstractStorageEngineTest;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.VeniceProperties;
import java.util.Optional;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.venice.store.AbstractStorageEngine.*;
import static org.mockito.Mockito.*;


public class RocksDBStorageEngineTest extends AbstractStorageEngineTest {
  private StorageService service;
  private VeniceStoreConfig storeConfig;

  private String storeName;
  private static final int PARTITION_ID = 0;

  @Override
  public void createStorageEngineForTest() {
    storeName = TestUtils.getUniqueString("rocksdb_store_test");
    VeniceProperties serverProperties = AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB);
    VeniceConfigLoader configLoader = AbstractStorageEngineTest.getVeniceConfigLoader(serverProperties);
    service = new StorageService(configLoader, s -> s.toString(), mock(AggVersionedBdbStorageEngineStats.class),
        mock(AggVersionedStorageEngineStats.class));
    storeConfig = new VeniceStoreConfig(storeName, serverProperties, PersistenceType.ROCKS_DB);
    testStoreEngine = service.openStoreForNewPartition(storeConfig , PARTITION_ID);
    createStoreForTest();
  }

  @BeforeClass
  public void setup() {
    createStorageEngineForTest();
  }

  @AfterClass
  public void tearDown() {
    if(service != null && storeConfig != null) {
      service.dropStorePartition(storeConfig , PARTITION_ID);
    }
  }

  @Test
  public void testGetAndPut() {
    super.testGetAndPut();
  }

  @Test
  public void testGetAndPutPartitionOffset() {
    AbstractStorageEngine testStorageEngine = getTestStoreEngine();
    Assert.assertEquals(testStorageEngine.getType(), PersistenceType.ROCKS_DB);
    RocksDBStorageEngine rocksDBStorageEngine = (RocksDBStorageEngine)testStorageEngine;
    OffsetRecord offsetRecord = new OffsetRecord();
    offsetRecord.setOffset(666L);
    rocksDBStorageEngine.putPartitionOffset(PARTITION_ID, offsetRecord);
    Assert.assertEquals(rocksDBStorageEngine.getPartitionOffset(PARTITION_ID).get().getOffset(), 666L);
    rocksDBStorageEngine.clearPartitionOffset(PARTITION_ID);
    Assert.assertEquals(rocksDBStorageEngine.getPartitionOffset(PARTITION_ID).isPresent(), false);
  }

  @Test
  public void testGetAndPutStoreVersionState() {
    AbstractStorageEngine testStorageEngine = getTestStoreEngine();
    Assert.assertEquals(testStorageEngine.getType(), PersistenceType.ROCKS_DB);
    RocksDBStorageEngine rocksDBStorageEngine = (RocksDBStorageEngine)testStorageEngine;

    // Create a StoreVersionState record
    StoreVersionState storeVersionStateRecord = new StoreVersionState();
    storeVersionStateRecord.sorted = true;

    rocksDBStorageEngine.putStoreVersionState(storeVersionStateRecord);
    Assert.assertEquals(rocksDBStorageEngine.getStoreVersionState().get(), storeVersionStateRecord);

    // If no store version state is present in this metadata partition, Optional.empty() should be returned.
    rocksDBStorageEngine.clearStoreVersionState();
    Assert.assertEquals(rocksDBStorageEngine.getStoreVersionState(), Optional.empty());
  }

  @Test
  public void testIllegalPartitionIdInGetAndPutPartitionOffset() {
    AbstractStorageEngine testStorageEngine = getTestStoreEngine();
    Assert.assertEquals(testStorageEngine.getType(), PersistenceType.ROCKS_DB);
    RocksDBStorageEngine rocksDBStorageEngine = (RocksDBStorageEngine)testStorageEngine;

    Assert.assertThrows(IllegalArgumentException.class,
        () -> rocksDBStorageEngine.putPartitionOffset(-1, new OffsetRecord()));

    Assert.assertThrows(IllegalArgumentException.class,
        () -> rocksDBStorageEngine.getPartitionOffset(-1));
  }

  @Test
  public void testDelete() {
    super.testDelete();
  }

  @Test
  public void testUpdate() {
    super.testUpdate();
  }

  @Test
  public void testGetInvalidKeys() {
    super.testGetInvalidKeys();
  }

  @Test
  public void testPutNullKey() {
    super.testPutNullKey();
  }

  @Test
  public void testPartitioning()
      throws Exception {
    super.testPartitioning();
  }

  @Test
  public void testAddingAPartitionTwice()
      throws Exception {
    super.testAddingAPartitionTwice();
  }

  @Test
  public void testRemovingPartitionTwice()
      throws Exception {
    super.testRemovingPartitionTwice();
  }

  @Test
  public void testOperationsOnNonExistingPartition()
      throws Exception {
    super.testOperationsOnNonExistingPartition();
  }

  @Test
  public void testGetPersistedPartitionIds() {
    AbstractStorageEngine testStorageEngine = getTestStoreEngine();
    Assert.assertEquals(testStorageEngine.getType(), PersistenceType.ROCKS_DB);
    RocksDBStorageEngine rocksDBStorageEngine = (RocksDBStorageEngine)testStorageEngine;
    Set<Integer> persistedPartitionIds = rocksDBStorageEngine.getPersistedPartitionIds();
    Assert.assertEquals(persistedPartitionIds.size(), 2);
    Assert.assertTrue(persistedPartitionIds.contains(PARTITION_ID));
    Assert.assertTrue(persistedPartitionIds.contains(METADATA_PARTITION_ID));
  }
}
