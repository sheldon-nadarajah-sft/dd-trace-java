package datadog.trace.instrumentation.threadsleep;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import org.junit.jupiter.api.Test;

class TimeUnitSleepProfilingInstrumentationTest {

  @Test
  void targetsTimeUnit() {
    assertArrayEquals(
        new String[] {"java.util.concurrent.TimeUnit"},
        new TimeUnitSleepProfilingInstrumentation().knownMatchingTypes());
  }

  @Test
  void zeroAndNegativeTimeoutsSkipCapture() {
    assertNull(TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(0L));
    assertNull(TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(-1L));
  }

  @Test
  void positiveTimeoutAndExitAreSafeWithoutProfilerState() {
    TaskBlockHelper.State state =
        assertDoesNotThrow(
            () -> TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(1L));

    assertDoesNotThrow(
        () -> TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.after(state));
  }

  @Test
  void exitAcceptsNullState() {
    assertDoesNotThrow(() -> TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.after(null));
  }
}
