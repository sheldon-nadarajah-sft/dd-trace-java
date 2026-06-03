package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TaskBlockHelperBenchmark {

  @Benchmark
  public TaskBlockHelper.State captureForSleep() {
    return TaskBlockHelper.captureForSleep();
  }

  @Benchmark
  public void captureAndFinishForSleep() {
    TaskBlockHelper.finish(TaskBlockHelper.captureForSleep());
  }

  @Benchmark
  public void finishNull() {
    TaskBlockHelper.finish(null);
  }

  @Benchmark
  public void finishTooShort(BenchmarkState state) {
    TaskBlockHelper.finish(state.tooShortState);
  }

  @Benchmark
  public void finishEligibleSynchronous(BenchmarkState state) {
    TaskBlockHelper.finish(state.eligibleSynchronousState);
  }

  @Benchmark
  public void finishEligibleDeferredWithBlockToken(BenchmarkState state) {
    TaskBlockHelper.finish(state.eligibleDeferredState);
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    private static final long START_TICKS = 42;
    private static final long BLOCKER = 7;
    private static final long BLOCK_TOKEN = 17;

    private final ProfilingContextIntegration profiling = ProfilingContextIntegration.NoOp.INSTANCE;

    TaskBlockHelper.State tooShortState;
    TaskBlockHelper.State eligibleSynchronousState;
    TaskBlockHelper.State eligibleDeferredState;

    @Setup(Level.Invocation)
    public void setup() {
      long now = System.nanoTime();
      tooShortState = new TaskBlockHelper.State(profiling, START_TICKS, now, BLOCKER);
      eligibleSynchronousState =
          new TaskBlockHelper.State(
              profiling, START_TICKS, now - 2 * TaskBlockHelper.MIN_TASK_BLOCK_NANOS, BLOCKER);
      eligibleDeferredState =
          new TaskBlockHelper.State(
              profiling,
              START_TICKS,
              now - 2 * TaskBlockHelper.MIN_TASK_BLOCK_NANOS,
              BLOCKER,
              true,
              0L,
              0L,
              BLOCK_TOKEN);
    }
  }
}
