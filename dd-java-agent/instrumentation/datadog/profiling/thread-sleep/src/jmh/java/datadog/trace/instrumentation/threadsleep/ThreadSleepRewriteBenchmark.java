package datadog.trace.instrumentation.threadsleep;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
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
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ThreadSleepRewriteBenchmark {

  @Benchmark
  public byte[] scanAndMaybeRewriteNoSleep(BenchmarkState state) {
    return scanAndMaybeRewrite(state.noSleepBytes);
  }

  @Benchmark
  public byte[] scanAndMaybeRewriteThreadSleepJ(BenchmarkState state) {
    return scanAndMaybeRewrite(state.threadSleepJBytes);
  }

  @Benchmark
  public byte[] scanAndMaybeRewriteMultipleSleeps(BenchmarkState state) {
    return scanAndMaybeRewrite(state.multipleSleepsBytes);
  }

  @Benchmark
  public byte[] scanAndMaybeRewriteTimeUnitOnly(BenchmarkState state) {
    return scanAndMaybeRewrite(state.timeUnitSleepBytes);
  }

  @Benchmark
  public byte[] rewriteThreadSleepJ(BenchmarkState state) {
    return rewrite(state.threadSleepJBytes);
  }

  private static byte[] scanAndMaybeRewrite(byte[] bytes) {
    if (!ThreadSleepScanner.scan(bytes)) {
      return bytes;
    }
    return rewrite(bytes);
  }

  private static byte[] rewrite(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer =
        new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
          @Override
          protected String getCommonSuperClass(final String type1, final String type2) {
            if (type1.equals(type2)) {
              return type1;
            }
            try {
              return super.getCommonSuperClass(type1, type2);
            } catch (Exception ignored) {
              return "java/lang/Object";
            }
          }
        };
    ClassVisitor visitor = new ThreadSleepRewritingVisitor.ThreadSleepClassVisitor(writer);
    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    byte[] noSleepBytes;
    byte[] threadSleepJBytes;
    byte[] multipleSleepsBytes;
    byte[] timeUnitSleepBytes;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      noSleepBytes = ThreadSleepScannerBenchmark.classBytes(NoSleepFixture.class);
      threadSleepJBytes = ThreadSleepScannerBenchmark.classBytes(ThreadSleepJFixture.class);
      multipleSleepsBytes = ThreadSleepScannerBenchmark.classBytes(MultipleSleepsFixture.class);
      timeUnitSleepBytes = ThreadSleepScannerBenchmark.classBytes(TimeUnitSleepFixture.class);
    }
  }

  public static final class NoSleepFixture {
    public static long work(long value) {
      return Math.abs(value);
    }
  }

  public static final class ThreadSleepJFixture {
    public static void sleep(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  public static final class MultipleSleepsFixture {
    public static void sleep() throws InterruptedException {
      Thread.sleep(1L);
      Thread.sleep(2L);
      Thread.sleep(3L, 1);
    }
  }

  public static final class TimeUnitSleepFixture {
    public static void sleep(long timeout) throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(timeout);
    }
  }
}
