/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.inference.pytorch.process;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.core.ml.utils.Intervals;
import org.elasticsearch.xpack.ml.inference.pytorch.results.AckResult;
import org.elasticsearch.xpack.ml.inference.pytorch.results.ErrorResult;
import org.elasticsearch.xpack.ml.inference.pytorch.results.PyTorchInferenceResult;
import org.elasticsearch.xpack.ml.inference.pytorch.results.PyTorchResult;
import org.elasticsearch.xpack.ml.inference.pytorch.results.ThreadSettings;

import java.time.Instant;
import java.util.Iterator;
import java.util.LongSummaryStatistics;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.elasticsearch.core.Strings.format;

public class PyTorchResultProcessor {

    public record RecentStats(long requestsProcessed, Double avgInferenceTime, long cacheHitCount) {}

    public record ResultStats(
        LongSummaryStatistics timingStats,
        LongSummaryStatistics timingStatsExcludingCacheHits,
        int errorCount,
        long cacheHitCount,
        int numberOfPendingResults,
        Instant lastUsed,
        long peakThroughput,
        RecentStats recentStats
    ) {}

    private static final Logger logger = LogManager.getLogger(PyTorchResultProcessor.class);
    static long REPORTING_PERIOD_MS = TimeValue.timeValueMinutes(1).millis();

    private final ConcurrentMap<String, PendingResult> pendingResults = new ConcurrentHashMap<>();
    private final String deploymentId;
    private final Consumer<ThreadSettings> threadSettingsConsumer;
    private volatile boolean isStopping;
    private final LongSummaryStatistics timingStats;
    private final LongSummaryStatistics timingStatsExcludingCacheHits;
    private int errorCount;
    private long cacheHitCount;
    private long peakThroughput;

    private LongSummaryStatistics lastPeriodSummaryStats;
    private long lastPeriodCacheHitCount;
    private RecentStats lastPeriodStats;
    private long currentPeriodEndTimeMs;
    private long lastResultTimeMs;
    private final long startTime;
    private final LongSupplier currentTimeMsSupplier;

    public PyTorchResultProcessor(String deploymentId, Consumer<ThreadSettings> threadSettingsConsumer) {
        this(deploymentId, threadSettingsConsumer, System::currentTimeMillis);
    }

    // for testing
    PyTorchResultProcessor(String deploymentId, Consumer<ThreadSettings> threadSettingsConsumer, LongSupplier currentTimeSupplier) {
        this.deploymentId = Objects.requireNonNull(deploymentId);
        this.timingStats = new LongSummaryStatistics();
        this.timingStatsExcludingCacheHits = new LongSummaryStatistics();
        this.lastPeriodSummaryStats = new LongSummaryStatistics();
        this.threadSettingsConsumer = Objects.requireNonNull(threadSettingsConsumer);
        this.currentTimeMsSupplier = currentTimeSupplier;
        this.startTime = currentTimeSupplier.getAsLong();
        this.currentPeriodEndTimeMs = startTime + REPORTING_PERIOD_MS;
    }

    public void registerRequest(String requestId, ActionListener<PyTorchResult> listener) {
        pendingResults.computeIfAbsent(requestId, k -> new PendingResult(listener));
    }

    /**
     * Call this method when the caller is no longer waiting on the request response.
     * Note that the pending result listener will not be notified.
     *
     * @param requestId The request ID that is no longer being waited on
     */
    public void ignoreResponseWithoutNotifying(String requestId) {
        pendingResults.remove(requestId);
    }

    public void process(PyTorchProcess process) {
        logger.debug(() -> "[" + deploymentId + "] Results processing started");
        try {
            Iterator<PyTorchResult> iterator = process.readResults();
            while (iterator.hasNext()) {
                PyTorchResult result = iterator.next();

                if (result.inferenceResult() != null) {
                    processInferenceResult(result);
                }
                ThreadSettings threadSettings = result.threadSettings();
                if (threadSettings != null) {
                    threadSettingsConsumer.accept(threadSettings);
                    processThreadSettings(result);
                }
                if (result.ackResult() != null) {
                    processAcknowledgement(result);
                }
                if (result.errorResult() != null) {
                    processErrorResult(result);
                }
            }
        } catch (Exception e) {
            // No need to report error as we're stopping
            if (isStopping == false) {
                logger.error(() -> "[" + deploymentId + "] Error processing results", e);
            }
            pendingResults.forEach(
                (id, pendingResult) -> pendingResult.listener.onResponse(
                    new PyTorchResult(
                        id,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ErrorResult(
                            isStopping
                                ? "inference canceled as process is stopping"
                                : "inference native process died unexpectedly with failure [" + e.getMessage() + "]"
                        )
                    )
                )
            );
            pendingResults.clear();
        } finally {
            pendingResults.forEach(
                (id, pendingResult) -> pendingResult.listener.onResponse(
                    new PyTorchResult(id, false, null, null, null, null, new ErrorResult("inference canceled as process is stopping"))
                )
            );
            pendingResults.clear();
        }
        logger.debug(() -> "[" + deploymentId + "] Results processing finished");
    }

    void processInferenceResult(PyTorchResult result) {
        PyTorchInferenceResult inferenceResult = result.inferenceResult();
        assert inferenceResult != null;
        Long timeMs = result.timeMs();
        if (timeMs == null) {
            assert false : "time_ms should be set for an inference result";
            timeMs = 0L;
        }

        logger.trace(() -> format("[%s] Parsed inference result with id [%s]", deploymentId, result.requestId()));
        updateStats(timeMs, Boolean.TRUE.equals(result.isCacheHit()));
        PendingResult pendingResult = pendingResults.remove(result.requestId());
        if (pendingResult == null) {
            logger.debug(() -> format("[%s] no pending result for inference [%s]", deploymentId, result.requestId()));
        } else {
            pendingResult.listener.onResponse(result);
        }
    }

    void processThreadSettings(PyTorchResult result) {
        ThreadSettings threadSettings = result.threadSettings();
        assert threadSettings != null;

        logger.trace(() -> format("[%s] Parsed thread settings result with id [%s]", deploymentId, result.requestId()));
        PendingResult pendingResult = pendingResults.remove(result.requestId());
        if (pendingResult == null) {
            logger.debug(() -> format("[%s] no pending result for thread settings [%s]", deploymentId, result.requestId()));
        } else {
            pendingResult.listener.onResponse(result);
        }
    }

    void processAcknowledgement(PyTorchResult result) {
        AckResult ack = result.ackResult();
        assert ack != null;

        logger.trace(() -> format("[%s] Parsed ack result with id [%s]", deploymentId, result.requestId()));
        PendingResult pendingResult = pendingResults.remove(result.requestId());
        if (pendingResult == null) {
            logger.debug(() -> format("[%s] no pending result for ack [%s]", deploymentId, result.requestId()));
        } else {
            pendingResult.listener.onResponse(result);
        }
    }

    void processErrorResult(PyTorchResult result) {
        ErrorResult errorResult = result.errorResult();
        assert errorResult != null;

        // Only one result is processed at any time, but we need to stop this happening part way through another thread getting stats
        synchronized (this) {
            errorCount++;
        }

        logger.trace(() -> format("[%s] Parsed error with id [%s]", deploymentId, result.requestId()));
        PendingResult pendingResult = pendingResults.remove(result.requestId());
        if (pendingResult == null) {
            logger.debug(() -> format("[%s] no pending result for error [%s]", deploymentId, result.requestId()));
        } else {
            pendingResult.listener.onResponse(result);
        }
    }

    public synchronized ResultStats getResultStats() {
        long currentMs = currentTimeMsSupplier.getAsLong();
        long currentPeriodStartTimeMs = startTime + Intervals.alignToFloor(currentMs - startTime, REPORTING_PERIOD_MS);

        // Do we have results from the previous period?
        RecentStats rs = null;
        if (lastResultTimeMs >= currentPeriodStartTimeMs) {
            // if there is a result for the last period then set it.
            // lastPeriodStats will be null when more than one period
            // has passed without a result.
            rs = lastPeriodStats;
        } else if (lastResultTimeMs >= currentPeriodStartTimeMs - REPORTING_PERIOD_MS) {
            // there was a result in the last period but not one
            // in this period to close off the last period stats.
            // The stats are valid return them here
            rs = new RecentStats(lastPeriodSummaryStats.getCount(), lastPeriodSummaryStats.getAverage(), lastPeriodCacheHitCount);
            peakThroughput = Math.max(peakThroughput, lastPeriodSummaryStats.getCount());
        }

        if (rs == null) {
            // no results processed in the previous period
            rs = new RecentStats(0L, null, 0L);
        }

        return new ResultStats(
            cloneSummaryStats(timingStats),
            cloneSummaryStats(timingStatsExcludingCacheHits),
            errorCount,
            cacheHitCount,
            pendingResults.size(),
            lastResultTimeMs > 0 ? Instant.ofEpochMilli(lastResultTimeMs) : null,
            this.peakThroughput,
            rs
        );
    }

    private LongSummaryStatistics cloneSummaryStats(LongSummaryStatistics stats) {
        return new LongSummaryStatistics(stats.getCount(), stats.getMin(), stats.getMax(), stats.getSum());
    }

    private synchronized void updateStats(long timeMs, boolean isCacheHit) {
        timingStats.accept(timeMs);

        lastResultTimeMs = currentTimeMsSupplier.getAsLong();
        if (lastResultTimeMs > currentPeriodEndTimeMs) {
            // rolled into the next period
            peakThroughput = Math.max(peakThroughput, lastPeriodSummaryStats.getCount());
            // TODO min inference time
            if (lastResultTimeMs > currentPeriodEndTimeMs + REPORTING_PERIOD_MS) {
                // We have skipped one or more periods,
                // there is no data for the last period
                lastPeriodStats = null;
            } else {
                lastPeriodStats = new RecentStats(
                    lastPeriodSummaryStats.getCount(),
                    lastPeriodSummaryStats.getAverage(),
                    lastPeriodCacheHitCount
                );
            }

            lastPeriodCacheHitCount = 0;
            lastPeriodSummaryStats = new LongSummaryStatistics();
            lastPeriodSummaryStats.accept(timeMs);

            // set to the end of the current bucket
            currentPeriodEndTimeMs = startTime + Intervals.alignToCeil(lastResultTimeMs - startTime, REPORTING_PERIOD_MS);
        } else {
            lastPeriodSummaryStats.accept(timeMs);
        }

        if (isCacheHit) {
            cacheHitCount++;
            lastPeriodCacheHitCount++;
        } else {
            // don't include cache hits when recording inference time
            timingStatsExcludingCacheHits.accept(timeMs);
        }
    }

    public void stop() {
        isStopping = true;
    }

    public static class PendingResult {
        public final ActionListener<PyTorchResult> listener;

        public PendingResult(ActionListener<PyTorchResult> listener) {
            this.listener = Objects.requireNonNull(listener);
        }
    }
}
