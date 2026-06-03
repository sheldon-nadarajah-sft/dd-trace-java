package datadog.trace.instrumentation.threadsleep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThreadSleepProfilingInstrumentationTest {

  @Test
  void classLoaderMatcher_doesNotMatchBootstrapClassLoader() {
    assertFalse(new ThreadSleepProfilingInstrumentation().classLoaderMatcher().matches(null));
  }

  @Test
  void classLoaderMatcher_matchesApplicationClassLoader() {
    assertTrue(
        new ThreadSleepProfilingInstrumentation()
            .classLoaderMatcher()
            .matches(ThreadSleepProfilingInstrumentationTest.class.getClassLoader()));
  }
}
