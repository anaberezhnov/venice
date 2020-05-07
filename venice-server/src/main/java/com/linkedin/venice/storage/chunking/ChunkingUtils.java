package com.linkedin.venice.storage.chunking;

import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.listener.response.ReadResponse;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.serialization.KeyWithChunkingSuffixSerializer;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.ChunkedValueManifestSerializer;
import com.linkedin.venice.storage.protocol.ChunkedKeySuffix;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.store.AbstractStorageEngine;
import com.linkedin.venice.store.record.ValueRecord;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.writer.VeniceWriter;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;


/**
 * This class and the rest of this package encapsulate the complexity of assembling chunked values
 * from the storage engine. At a high level, value chunking in Venice works this way:
 *
 * The {@link VeniceWriter} performs the chunking, and the ingestion code completely ignores it,
 * treating chunks and full values exactly the same way. Re-assembly then happens at read time.
 *
 * The reason the above strategy works is that when a store-version has chunking enabled, there
 * is a {@link ChunkedKeySuffix} appended to the end of every key. This suffix indicates, via
 * {@link ChunkedKeySuffix#isChunk}, whether the corresponding value is a chunk or a "top-level"
 * key. The suffix is carefully designed to achieve the following goals:
 *
 * 1. Chunks and top-level keys should never collide, so that the storage engine and Kafka log
 *    compaction never inadvertently overwrite a chunk with a top-level key or vice-versa.
 * 2. Byte ordering is preserved assuming the {@link VeniceWriter} writes chunks in order and
 *    then writes the top-level key/value at the end. This is important because Venice is optimized
 *    for ordered ingestion.
 *
 * A top-level key can correspond either to a full value, or to a {@link ChunkedValueManifest}.
 * This is disambiguated by looking at the {@link Put#schemaId} field, which is set to a specific
 * negative value in the case of manifests.
 *
 * @see AvroProtocolDefinition#CHUNKED_VALUE_MANIFEST for the specific ID
 *
 * Therefore, at read time, the following steps are executed:
 *
 * 1. The top-level key is queried.
 * 2. The top-level key's value's schema ID is checked.
 *    a) If it is positive, then it's a full value, and is returned immediately.
 *    b) If it is negative, then it's a {@link ChunkedValueManifest}, and we continue to the next steps.
 * 3. The {@link ChunkedValueManifest} is deserialized, and its chunk keys are extracted.
 * 4. Each chunk key is queried.
 * 5. The chunks are stitched back together using the various adpater interfaces of this package,
 *    depending on whether it is the single get or batch get/compute path that needs to re-assembe
 *    a chunked value.
 */
public class ChunkingUtils {
  final static ChunkedValueManifestSerializer CHUNKED_VALUE_MANIFEST_SERIALIZER = new ChunkedValueManifestSerializer(false);
  public final static KeyWithChunkingSuffixSerializer KEY_WITH_CHUNKING_SUFFIX_SERIALIZER = new KeyWithChunkingSuffixSerializer();

  /**
   * Fills in default values for the unused parameters of the single get and batch get paths.
   */
  static <VALUE, ASSEMBLED_VALUE_CONTAINER> VALUE getFromStorage(
      ChunkingAdapter<ASSEMBLED_VALUE_CONTAINER, VALUE> adapter,
      AbstractStorageEngine store,
      int partition,
      ByteBuffer keyBuffer,
      ReadResponse response) {
    return getFromStorage(
        adapter,
        store,
        partition,
        keyBuffer,
        response,
        null,
        null,
        null,
        false,
        null,
        null);
  }

  static <VALUE, CHUNKS_CONTAINER> VALUE getFromStorage(
      ChunkingAdapter<CHUNKS_CONTAINER, VALUE> adapter,
      AbstractStorageEngine store,
      int partition,
      byte[] keyBuffer,
      ByteBuffer reusedRawValue,
      VALUE reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    long databaseLookupStartTimeInNS = (null != response) ? System.nanoTime() : 0;
    reusedRawValue = store.get(partition, keyBuffer, reusedRawValue);
    if (null == reusedRawValue) {
      return null;
    }
    return getFromStorage(
        reusedRawValue.array(), reusedRawValue.limit(), databaseLookupStartTimeInNS, adapter, store, partition, response,
        reusedValue, reusedDecoder, compressionStrategy, fastAvroEnabled, schemaRepo, storeName);
  }


  /**
   * Fetches the value associated with the given key, and potentially re-assembles it, if it is
   * a chunked value.
   *
   * This code makes use of the {@link ChunkingAdapter} interface in order to abstract away the
   * different needs of the single get, batch get and compute code paths. This function should
   * not be called directly, from the query code, as it expects the key to be properly formatted
   * already. Use of one these simpler functions instead:
   *
   * @see SingleGetChunkingAdapter#get(AbstractStorageEngine, int, byte[], boolean, ReadResponse)
   * @see BatchGetChunkingAdapter#get(AbstractStorageEngine, int, ByteBuffer, boolean, ReadResponse)
   * @see GenericRecordChunkingAdapter#get(AbstractStorageEngine, int, ByteBuffer, boolean, GenericRecord, BinaryDecoder, ReadResponse, CompressionStrategy, boolean, ReadOnlySchemaRepository, String, Optional)
   */
  static <VALUE, CHUNKS_CONTAINER> VALUE getFromStorage(
      ChunkingAdapter<CHUNKS_CONTAINER, VALUE> adapter,
      AbstractStorageEngine store,
      int partition,
      ByteBuffer keyBuffer,
      ReadResponse response,
      VALUE reusedValue,
      BinaryDecoder reusedDecoder,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    long databaseLookupStartTimeInNS = (null != response) ? System.nanoTime() : 0;
    byte[] value = store.get(partition, keyBuffer);

    return getFromStorage(
        value, (null == value ? 0 : value.length), databaseLookupStartTimeInNS, adapter, store, partition,
        response, reusedValue, reusedDecoder, compressionStrategy, fastAvroEnabled, schemaRepo, storeName);
  }

  /**
   * Fetches the value associated with the given key, and potentially re-assembles it, if it is
   * a chunked value.
   *
   * This code makes use of the {@link ChunkingAdapter} interface in order to abstract away the
   * different needs of the single get, batch get and compute code paths. This function should
   * not be called directly, from the query code, as it expects the key to be properly formatted
   * already. Use of one these simpler functions instead:
   *
   * @see SingleGetChunkingAdapter#get(AbstractStorageEngine, int, byte[], boolean, ReadResponse)
   * @see BatchGetChunkingAdapter#get(AbstractStorageEngine, int, ByteBuffer, boolean, ReadResponse)
   * @see GenericRecordChunkingAdapter#get(AbstractStorageEngine, int, ByteBuffer, boolean, GenericRecord, BinaryDecoder, ReadResponse, CompressionStrategy, boolean, ReadOnlySchemaRepository, String, Optional)
   */
  private static <VALUE, CHUNKS_CONTAINER> VALUE getFromStorage(
      byte[] value,
      int valueLength,
      long databaseLookupStartTimeInNS,
      ChunkingAdapter<CHUNKS_CONTAINER, VALUE> adapter,
      AbstractStorageEngine store,
      int partition,
      ReadResponse response,
      VALUE reusedValue,
      BinaryDecoder reusedDecoder,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {

    if (null == value) {
      return null;
    }
    int schemaId = ValueRecord.parseSchemaId(value);

    if (schemaId > 0) {
      // User-defined schema, thus not a chunked value. Early termination.

      if (null != response) {
        response.addDatabaseLookupLatency(LatencyUtils.getLatencyInMS(databaseLookupStartTimeInNS));
      }

      return adapter.constructValue(schemaId, value, valueLength, reusedValue, reusedDecoder, response, compressionStrategy,
          fastAvroEnabled, schemaRepo, storeName);
    } else if (schemaId != AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion()) {
      throw new VeniceException("Found a record with invalid schema ID: " + schemaId);
    }

    // End of initial sanity checks. We have a chunked value, so we need to fetch all chunks

    ChunkedValueManifest chunkedValueManifest = CHUNKED_VALUE_MANIFEST_SERIALIZER.deserialize(value, schemaId);
    CHUNKS_CONTAINER assembledValueContainer = adapter.constructChunksContainer(chunkedValueManifest);
    int actualSize = 0;

    for (int chunkIndex = 0; chunkIndex < chunkedValueManifest.keysWithChunkIdSuffix.size(); chunkIndex++) {
      // N.B.: This is done sequentially. Originally, each chunk was fetched concurrently in the same executor
      // as the main queries, but this might cause deadlocks, so we are now doing it sequentially. If we want to
      // optimize large value retrieval in the future, it's unclear whether the concurrent retrieval approach
      // is optimal (as opposed to streaming the response out incrementally, for example). Since this is a
      // premature optimization, we are not addressing it right now.
      byte[] valueChunk = store.get(partition, chunkedValueManifest.keysWithChunkIdSuffix.get(chunkIndex).array());

      if (null == valueChunk) {
        throw new VeniceException(
            "Chunk not found in " + getExceptionMessageDetails(store, partition, chunkIndex));
      } else if (ValueRecord.parseSchemaId(valueChunk) != AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion()) {
        throw new VeniceException(
            "Did not get the chunk schema ID while attempting to retrieve a chunk! "
                + "Instead, got schema ID: " + ValueRecord.parseSchemaId(valueChunk) + " from "
                + getExceptionMessageDetails(store, partition, chunkIndex));
      }

      actualSize += valueChunk.length - ValueRecord.SCHEMA_HEADER_LENGTH;
      adapter.addChunkIntoContainer(assembledValueContainer, chunkIndex, valueChunk);
    }

    // Sanity check based on size...
    if (actualSize != chunkedValueManifest.size) {
      throw new VeniceException(
          "The fully assembled large value does not have the expected size! "
              + "actualSize: " + actualSize + ", chunkedValueManifest.size: " + chunkedValueManifest.size
              + ", " + getExceptionMessageDetails(store, partition, null));
    }

    if (null != response) {
      response.addDatabaseLookupLatency(LatencyUtils.getLatencyInMS(databaseLookupStartTimeInNS));
      response.incrementMultiChunkLargeValueCount();
    }

    return adapter.constructValue(chunkedValueManifest.schemaId, assembledValueContainer, reusedValue, reusedDecoder, response, compressionStrategy, fastAvroEnabled, schemaRepo, storeName);
  }

  private static String getExceptionMessageDetails(AbstractStorageEngine store, int partition, Integer chunkIndex) {
    String message = "store: " + store.getName() + ", partition: " + partition;
    if (chunkIndex != null) {
      message += ", chunk index: " + chunkIndex;
    }
    message += ".";
    return message;
  }
}
