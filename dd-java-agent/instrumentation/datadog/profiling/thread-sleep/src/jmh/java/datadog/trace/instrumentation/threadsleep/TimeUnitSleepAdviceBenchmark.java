package datadog.trace.instrumentation.threadsleep;

import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TimeUnitSleepAdviceBenchmark {

  @Benchmark
  public TaskBlockHelper.State enterZeroTimeout() {
    return TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(0L);
  }

  @Benchmark
  public TaskBlockHelper.State enterNegativeTimeout() {
    return TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(-1L);
  }

  @Benchmark
  public TaskBlockHelper.State enterPositiveTimeout() {
    return TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.before(1L);
  }

  @Benchmark
  public void exitNullState() {
    TimeUnitSleepProfilingInstrumentation.TimeUnitSleepAdvice.after(null);
  }
}
