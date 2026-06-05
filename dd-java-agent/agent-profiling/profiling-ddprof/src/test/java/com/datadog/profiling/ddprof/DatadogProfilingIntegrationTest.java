package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DatadogProfilingIntegrationTest {

  @Test
  void drainTaskBlockEntryDropsFailedEventAndContinues() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger recorded = new AtomicInteger();
    TaskBlockDrain.Recorder recorder =
        (tid,
            startTicks,
            endTicks,
            blocker,
            unblockingSpanId,
            spanId,
            rootSpanId,
            anchorSampleId,
            suppressedSampleCount,
            observedBlockingState) -> {
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("simulated bridge failure");
          }
          recorded.incrementAndGet();
        };

    assertDoesNotThrow(
        () -> TaskBlockDrain.drainTaskBlockEntry(entry(1), 1_000_000_000L, recorder));
    assertDoesNotThrow(
        () -> TaskBlockDrain.drainTaskBlockEntry(entry(2), 1_000_000_000L, recorder));

    assertEquals(2, attempts.get(), "second entry must still be attempted after first failure");
    assertEquals(1, recorded.get(), "second entry must be recorded after first failure is dropped");
  }

  private static long[] entry(long blocker) {
    return new long[] {1L, 2L, 3_000_000L, blocker, 0L, 0L, 4L, 5L, 6L};
  }
}
