package com.datadog.profiling.ddprof;

import java.math.BigInteger;

final class TaskBlockDrain {
  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
  private static final BigInteger LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE);

  private TaskBlockDrain() {}

  static void drainTaskBlockEntry(long[] entry, long tscFrequency, Recorder recorder) {
    int tid = (int) entry[0];
    long startTicks = entry[1];
    long durationNanos = entry[2];
    long blocker = entry[3];
    long spanId = entry[4];
    long rootSpanId = entry[5];
    long anchorSampleId = entry.length > 6 ? entry[6] : 0L;
    long suppressedSampleCount = entry.length > 7 ? entry[7] : 0L;
    int observedBlockingState = entry.length > 8 ? (int) entry[8] : 0;
    try {
      recordTaskBlockEntry(
          tid,
          startTicks,
          durationNanos,
          blocker,
          spanId,
          rootSpanId,
          anchorSampleId,
          suppressedSampleCount,
          observedBlockingState,
          tscFrequency,
          recorder);
    } catch (Throwable ignored) {
    }
  }

  static void recordTaskBlockEntry(
      int tid,
      long startTicks,
      long durationNanos,
      long blocker,
      long spanId,
      long rootSpanId,
      long anchorSampleId,
      long suppressedSampleCount,
      int observedBlockingState,
      long tscFrequency,
      Recorder recorder) {
    long endTicks = saturatingAdd(startTicks, nanosToTicks(durationNanos, tscFrequency));
    recorder.recordTaskBlockFromContextEvent(
        tid,
        startTicks,
        endTicks,
        blocker,
        0L,
        spanId,
        rootSpanId,
        anchorSampleId,
        suppressedSampleCount,
        observedBlockingState);
  }

  private static long nanosToTicks(long durationNanos, long frequency) {
    if (durationNanos <= 0L || frequency <= 0L) {
      return 0L;
    }
    long seconds = durationNanos / 1_000_000_000L;
    long nanos = durationNanos % 1_000_000_000L;
    return saturatingAdd(
        saturatingMultiply(seconds, frequency), fractionalNanosToTicks(nanos, frequency));
  }

  private static long fractionalNanosToTicks(long nanos, long frequency) {
    if (nanos == 0L) {
      return 0L;
    }
    return BigInteger.valueOf(nanos)
        .multiply(BigInteger.valueOf(frequency))
        .divide(NANOS_PER_SECOND)
        .min(LONG_MAX_VALUE)
        .longValue();
  }

  private static long saturatingMultiply(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static long saturatingAdd(long left, long right) {
    long result = left + right;
    if (((left ^ result) & (right ^ result)) < 0L) {
      return Long.MAX_VALUE;
    }
    return result;
  }

  interface Recorder {
    void recordTaskBlockFromContextEvent(
        int tid,
        long startTicks,
        long endTicks,
        long blocker,
        long unblockingSpanId,
        long spanId,
        long rootSpanId,
        long anchorSampleId,
        long suppressedSampleCount,
        int observedBlockingState);
  }
}
