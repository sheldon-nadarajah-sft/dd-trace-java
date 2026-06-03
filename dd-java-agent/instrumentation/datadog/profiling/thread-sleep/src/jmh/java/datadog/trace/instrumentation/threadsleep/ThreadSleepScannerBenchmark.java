package datadog.trace.instrumentation.threadsleep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
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
public class ThreadSleepScannerBenchmark {

  @Benchmark
  public boolean scanNoSleep(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.noSleepBytes);
  }

  @Benchmark
  public boolean scanThreadSleepJ(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.threadSleepJBytes);
  }

  @Benchmark
  public boolean scanThreadSleepJI(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.threadSleepJIBytes);
  }

  @Benchmark
  public boolean scanThreadSleepDuration(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.threadSleepDurationBytes);
  }

  @Benchmark
  public boolean scanTimeUnitOnly(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.timeUnitSleepBytes);
  }

  @Benchmark
  public boolean scanMalformed(BenchmarkState state) {
    return ThreadSleepScanner.scan(state.malformedBytes);
  }

  @State(Scope.Thread)
  public static class BenchmarkState {
    byte[] noSleepBytes;
    byte[] threadSleepJBytes;
    byte[] threadSleepJIBytes;
    byte[] threadSleepDurationBytes;
    byte[] timeUnitSleepBytes;
    byte[] malformedBytes;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      noSleepBytes = classBytes(NoSleepFixture.class);
      threadSleepJBytes = classBytes(ThreadSleepJFixture.class);
      threadSleepJIBytes = classBytes(ThreadSleepJIFixture.class);
      threadSleepDurationBytes = sleepDurationFixtureBytes();
      timeUnitSleepBytes = classBytes(TimeUnitSleepFixture.class);
      malformedBytes = new byte[] {0x00, 0x01};
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

  public static final class ThreadSleepJIFixture {
    public static void sleep(long millis, int nanos) throws InterruptedException {
      Thread.sleep(millis, nanos);
    }
  }

  public static final class TimeUnitSleepFixture {
    public static void sleep(long timeout) throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(timeout);
    }
  }

  static byte[] classBytes(Class<?> clazz) throws IOException {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Could not find class resource: " + resource);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  static byte[] sleepDurationFixtureBytes() {
    ClassWriter cw =
        new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
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
    cw.visit(
        Opcodes.V11,
        Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "datadog/trace/instrumentation/threadsleep/SleepDurationBenchmarkFixture",
        null,
        "java/lang/Object",
        null);
    MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "sleep",
            "(Ljava/time/Duration;)V",
            null,
            new String[] {"java/lang/InterruptedException"});
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(Ljava/time/Duration;)V", false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }
}
