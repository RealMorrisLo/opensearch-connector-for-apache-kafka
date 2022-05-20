/*
 * Copyright 2020 Aiven Oy
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.opensearch;

import com.amazonaws.RequestClientOptions;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.connect.errors.ConnectException;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.aiven.kafka.connect.opensearch.RetryUtil.callWithRetry;

public class BulkProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(BulkProcessor.class);

  private static final AtomicLong BATCH_ID_GEN = new AtomicLong();

  private final Time time;
  private final RestHighLevelClient client;
  private final int maxBufferedRecords;
  private final int batchSize;
  private final long lingerMs;
  private final int maxRetries;
  private final long retryBackoffMs;
  private final BehaviorOnMalformedDoc behaviorOnMalformedDoc;

  private final Thread farmer;
  private final ExecutorService executor;
  private final AtomicReference<ConnectException> error = new AtomicReference<>();
  // shared state, synchronized on (this), may be part of wait() conditions so need notifyAll() on
  // changes
  private final Deque<DocWriteRequest<?>> unsentRecords;
  // thread-safe state, can be mutated safely without synchronization,
  // but may be part of synchronized(this) wait() conditions so need to notifyAll() on changes
  private volatile boolean stopRequested = false;
  private volatile boolean flushRequested = false;
  private int inFlightRecords = 0;

  public BulkProcessor(
      final Time time,
      final RestHighLevelClient client,
      final OpensearchSinkConnectorConfig config) {
    this.time = time;
    this.client = client;

    this.maxBufferedRecords = config.maxBufferedRecords();
    this.batchSize = config.batchSize();
    this.lingerMs = config.lingerMs();
    this.maxRetries = config.maxRetry();
    this.retryBackoffMs = config.retryBackoffMs();
    this.behaviorOnMalformedDoc = config.behaviorOnMalformedDoc();

    unsentRecords = new ArrayDeque<>(maxBufferedRecords);

    final ThreadFactory threadFactory = makeThreadFactory();
    farmer = threadFactory.newThread(farmerTask());
    executor = Executors.newFixedThreadPool(config.maxInFlightRequests(), threadFactory);
  }

  private static ConnectException toConnectException(final Throwable t) {
    if (t instanceof ConnectException) {
      return (ConnectException) t;
    } else {
      return new ConnectException(t);
    }
  }

  private ThreadFactory makeThreadFactory() {
    final AtomicInteger threadCounter = new AtomicInteger();
    final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
        (t, e) -> {
          LOGGER.error("Uncaught exception in BulkProcessor thread {}", t, e);
          failAndStop(e);
        };
    return new ThreadFactory() {
      @Override
      public Thread newThread(final Runnable r) {
        final int threadId = threadCounter.getAndIncrement();
        final int objId = System.identityHashCode(this);
        final Thread t = new Thread(r, String.format("BulkProcessor@%d-%d", objId, threadId));
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        return t;
      }
    };
  }

  private Runnable farmerTask() {
    return () -> {
      LOGGER.debug("Starting farmer task");
      try {
        while (!stopRequested) {
          submitBatchWhenReady();
        }
      } catch (final InterruptedException e) {
        throw new ConnectException(e);
      }
      LOGGER.debug("Finished farmer task");
    };
  }

  // Visible for testing
  synchronized Future<BulkResponse> submitBatchWhenReady() throws InterruptedException {
    for (long waitStartTimeMs = time.milliseconds(), elapsedMs = 0;
        !stopRequested && !canSubmit(elapsedMs);
        elapsedMs = time.milliseconds() - waitStartTimeMs) {
      // when linger time has already elapsed, we still have to ensure the other submission
      // conditions hence the wait(0) in that case
      wait(Math.max(0, lingerMs - elapsedMs));
    }
    // at this point, either stopRequested or canSubmit
    return stopRequested ? null : submitBatch();
  }

  private synchronized Future<BulkResponse> submitBatch() {
    assert !unsentRecords.isEmpty();
    LOGGER.info("current batch size: " + batchSize);

    final int batchableSize = Math.min(batchSize, unsentRecords.size());
    final var batch = new ArrayList<DocWriteRequest<?>>(batchableSize);
    for (int i = 0; i < batchableSize; i++) {
      var currentItem = unsentRecords.removeFirst();
      boolean removedItem = batch.removeIf(x -> x.id().equals(currentItem.id()));
      if (!removedItem)
        batch.add(currentItem);
      if (unsentRecords.isEmpty())
        break;
    }
    inFlightRecords += batchableSize;
    return executor.submit(new BulkTask(batch, maxRetries, retryBackoffMs));
  }

  /**
   * Submission is possible when there are unsent records and:
   *
   * <ul>
   *   <li>flush is called, or
   *   <li>the linger timeout passes, or
   *   <li>there are sufficient records to fill a batch
   * </ul>
   */
  private synchronized boolean canSubmit(final long elapsedMs) {
    return !unsentRecords.isEmpty()
        && (flushRequested || elapsedMs >= lingerMs || unsentRecords.size() >= batchSize);
  }

  /** Start concurrently creating and sending batched requests using the client. */
  public void start() {
    farmer.start();
  }

  /**
   * Initiate shutdown.
   *
   * <p>Pending buffered records are not automatically flushed, so call {@link #flush(long)} before
   * this method if this is desirable.
   */
  public void stop() {
    LOGGER.trace("stop");
    stopRequested = true;
    synchronized (this) {
      // shutdown the pool under synchronization to avoid rejected submissions
      executor.shutdown();
      notifyAll();
    }
  }

  /**
   * Block upto {@code timeoutMs} till shutdown is complete.
   *
   * <p>This should only be called after a previous {@link #stop()} invocation.
   */
  public void awaitStop(final long timeoutMs) {
    LOGGER.trace("awaitStop {}", timeoutMs);
    assert stopRequested;
    try {
      if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw new ConnectException("Timed-out waiting for executor termination");
      }
    } catch (final InterruptedException e) {
      throw new ConnectException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * @return whether {@link #stop()} has been requested
   */
  public boolean isStopping() {
    return stopRequested;
  }

  /**
   * @return whether any task failed with an error
   */
  public boolean isFailed() {
    return error.get() != null;
  }

  /**
   * @return {@link #isTerminal()} or {@link #isFailed()}
   */
  public boolean isTerminal() {
    return isStopping() || isFailed();
  }

  /** Throw a {@link ConnectException} if {@link #isStopping()}. */
  public void throwIfStopping() {
    if (stopRequested) {
      throw new ConnectException("Stopping");
    }
  }

  /** Throw the relevant {@link ConnectException} if {@link #isFailed()}. */
  public void throwIfFailed() {
    if (isFailed()) {
      throw error.get();
    }
  }

  /** {@link #throwIfFailed()} and {@link #throwIfStopping()} */
  public void throwIfTerminal() {
    throwIfFailed();
    throwIfStopping();
  }

  /**
   * Add a record, may block upto {@code timeoutMs} if at capacity with respect to {@code
   * maxBufferedRecords}.
   *
   * <p>If any task has failed prior to or while blocked in the add, or if the timeout expires while
   * blocked, {@link ConnectException} will be thrown.
   */
  public synchronized void add(final DocWriteRequest<?> request, final long timeoutMs) {
    throwIfTerminal();

    for (DocWriteRequest<?> unsentRecord : unsentRecords) {
      LOGGER.info("Message in unsent buffer: " + unsentRecord.id());
    }
    if (bufferedRecords() >= maxBufferedRecords) {
      final long addStartTimeMs = time.milliseconds();
      for (long elapsedMs = time.milliseconds() - addStartTimeMs;
          !isTerminal() && elapsedMs < timeoutMs && bufferedRecords() >= maxBufferedRecords;
          elapsedMs = time.milliseconds() - addStartTimeMs) {
        try {
          wait(timeoutMs - elapsedMs);
        } catch (final InterruptedException e) {
          throw new ConnectException(e);
        }
      }
      throwIfTerminal();
      if (bufferedRecords() >= maxBufferedRecords) {
        throw new ConnectException("Add timeout expired before buffer availability");
      }
    }

    unsentRecords.addLast(request);
    notifyAll();
  }

  /**
   * Request a flush and block upto {@code timeoutMs} until all pending records have been flushed.
   *
   * <p>If any task has failed prior to or during the flush, {@link ConnectException} will be thrown
   * with that error.
   */
  public void flush(final long timeoutMs) {
    LOGGER.trace("flush {}", timeoutMs);
    final long flushStartTimeMs = time.milliseconds();
    try {
      flushRequested = true;
      synchronized (this) {
        notifyAll();
        for (long elapsedMs = time.milliseconds() - flushStartTimeMs;
            !isTerminal() && elapsedMs < timeoutMs && bufferedRecords() > 0;
            elapsedMs = time.milliseconds() - flushStartTimeMs) {
          wait(timeoutMs - elapsedMs);
        }
        throwIfTerminal();
        if (bufferedRecords() > 0) {
          throw new ConnectException(
              "Flush timeout expired with unflushed records: " + bufferedRecords());
        }
      }
    } catch (final InterruptedException e) {
      throw new ConnectException(e);
    } finally {
      flushRequested = false;
    }
  }

  private boolean responseContainsMalformedDocError(final BulkItemResponse bulkItemResponse) {
    return bulkItemResponse.getFailureMessage().contains("strict_dynamic_mapping_exception")
        || bulkItemResponse.getFailureMessage().contains("mapper_parsing_exception")
        || bulkItemResponse.getFailureMessage().contains("illegal_argument_exception")
        || bulkItemResponse.getFailureMessage().contains("action_request_validation_exception");
  }

  private synchronized void onBatchCompletion(final int batchSize) {
    inFlightRecords -= batchSize;
    assert inFlightRecords >= 0;
    notifyAll();
  }

  private void failAndStop(final Throwable t) {
    error.compareAndSet(null, toConnectException(t));
    stop();
  }

  /**
   * @return sum of unsent and in-flight record counts
   */
  public synchronized int bufferedRecords() {
    return unsentRecords.size() + inFlightRecords;
  }

  public enum BehaviorOnMalformedDoc {
    IGNORE,
    WARN,
    FAIL;

    public static final BehaviorOnMalformedDoc DEFAULT = FAIL;

    // Want values for "behavior.on.malformed.doc" property to be case-insensitive
    public static final ConfigDef.Validator VALIDATOR =
        new ConfigDef.Validator() {
          private final ConfigDef.ValidString validator = ConfigDef.ValidString.in(names());

          @Override
          public void ensureValid(final String name, final Object value) {
            if (value instanceof String) {
              final String lowerCaseStringValue = ((String) value).toLowerCase(Locale.ROOT);
              validator.ensureValid(name, lowerCaseStringValue);
            } else {
              validator.ensureValid(name, value);
            }
          }

          // Overridden here so that ConfigDef.toEnrichedRst shows possible values correctly
          @Override
          public String toString() {
            return validator.toString();
          }
        };

    public static String[] names() {
      final BehaviorOnMalformedDoc[] behaviors = values();
      final String[] result = new String[behaviors.length];

      for (int i = 0; i < behaviors.length; i++) {
        result[i] = behaviors[i].toString();
      }

      return result;
    }

    public static BehaviorOnMalformedDoc forValue(final String value) {
      return valueOf(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final class BulkTask implements Callable<BulkResponse> {

    final long batchId = BATCH_ID_GEN.incrementAndGet();

    final List<DocWriteRequest<?>> batch;

    final int maxRetries;

    final long retryBackoffMs;

    BulkTask(
        final List<DocWriteRequest<?>> batch, final int maxRetries, final long retryBackoffMs) {
      this.batch = batch;
      this.maxRetries = maxRetries;
      this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public BulkResponse call() throws Exception {
      try {
        final var rsp = execute();
        LOGGER.info("Response: {}, Status: {}", rsp, rsp.status());
        LOGGER.info("Response Items: {}", Arrays.stream(rsp.getItems()).toArray());
        LOGGER.info("Successfully executed batch {} of {} records", batchId, batch.size());
        onBatchCompletion(batch.size());
        return rsp;
      } catch (final Exception e) {
        failAndStop(e);
        throw e;
      }
    }

    private BulkResponse execute() throws Exception {
      return callWithRetry(
          "bulk processing",
          () -> {
            try {

              batch.forEach(x -> {
                if (x instanceof IndexRequest) {
                  Map<String, Object> requestMap = ((IndexRequest) x).sourceAsMap();
                  LOGGER.info("Request Map: " + requestMap.toString());

                }
              });

              final var response =
                  client.bulk(new BulkRequest().add(batch), RequestOptions.DEFAULT);
              if (!response.hasFailures()) {
                // We only logged failures, so log the success immediately after a failure ...
                LOGGER.info("Completed batch {} of {} records", batchId, batch.size());
                return response;
              }
              for (final var itemResponse : response.getItems()) {
                if (!itemResponse.getFailure().isAborted()) {
                  if (responseContainsMalformedDocError(itemResponse)) {
                    handleMalformedDoc(itemResponse);
                  }
                  LOGGER.info(itemResponse.getFailureMessage());
                  throw new RuntimeException(
                      "One of the item in the bulk response failed. Reason: "
                          + itemResponse.getFailureMessage());
                } else {
                  throw new ConnectException(
                      "One of the item in the bulk response aborted. Reason: "
                          + itemResponse.getFailureMessage());
                }
              }
              return response;
            } catch (final IOException e) {
              LOGGER.error(
                  "Failed to send bulk request from batch {} of {} records",
                  batchId,
                  batch.size(),
                  e);
              throw new ConnectException(e);
            }
          },
          maxRetries,
          retryBackoffMs,
          RuntimeException.class);
    }

    private void handleMalformedDoc(final BulkItemResponse bulkItemResponse) {
      // if the elasticsearch request failed because of a malformed document,
      // the behavior is configurable.
      switch (behaviorOnMalformedDoc) {
        case IGNORE:
          LOGGER.debug(
              "Encountered an illegal document error when executing batch {} of {}"
                  + " records. Ignoring and will not index record. Error was {}",
              batchId,
              batch.size(),
              bulkItemResponse.getFailureMessage());
          return;
        case WARN:
          LOGGER.warn(
              "Encountered an illegal document error when executing batch {} of {}"
                  + " records. Ignoring and will not index record. Error was {}",
              batchId,
              batch.size(),
              bulkItemResponse.getFailureMessage());
          return;
        case FAIL:
        default:
          LOGGER.error(
              "Encountered an illegal document error when executing batch {} of {}"
                  + " records. Error was {} (to ignore future records like this"
                  + " change the configuration property '{}' from '{}' to '{}').",
              batchId,
              batch.size(),
              bulkItemResponse.getFailureMessage(),
              OpensearchSinkConnectorConfig.BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
              BehaviorOnMalformedDoc.FAIL,
              BehaviorOnMalformedDoc.IGNORE);
          throw new ConnectException(
              "Bulk request failed: " + bulkItemResponse.getFailureMessage());
      }
    }
  }
}
