package datadog.trace.instrumentation.threadsleep;

import java.util.concurrent.TimeUnit;
import net.bytebuddy.matcher.ElementMatcher;
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
public class ThreadSleepClassLoaderMatcherBenchmark {

  @Benchmark
  public boolean matchesBootstrapClassLoader(BenchmarkState state) {
    return state.matcher.matches(null);
  }

  @Benchmark
  public boolean matchesApplicationClassLoader(BenchmarkState state) {
    return state.matcher.matches(state.applicationClassLoader);
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    ElementMatcher.Junction<ClassLoader> matcher;
    ClassLoader applicationClassLoader;

    @Setup(Level.Trial)
    public void setup() {
      matcher = new ThreadSleepProfilingInstrumentation().classLoaderMatcher();
      applicationClassLoader = ThreadSleepClassLoaderMatcherBenchmark.class.getClassLoader();
    }
  }
}
