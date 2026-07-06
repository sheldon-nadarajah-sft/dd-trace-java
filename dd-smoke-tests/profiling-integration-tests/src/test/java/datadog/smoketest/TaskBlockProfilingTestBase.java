package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.agentShadowJar;
import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.javaPath;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import datadog.trace.api.config.ProfilingConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/**
 * Shared infrastructure for the native TaskBlock profiling smoke tests: forked-process JVM flags,
 * JFR dump-directory lifecycle, and JFR stream loading. Subclasses provide the scenario-specific
 * forked app, JFR attributes, and stats accumulation.
 */
abstract class TaskBlockProfilingTestBase<S> {
  static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  static final IAttribute<IQuantity> BLOCKER = attr("blocker", "blocker", "blocker", NUMBER);
  static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);

  private final Path logFileBase;

  Path dumpDir;
  Path logFilePath;

  protected TaskBlockProfilingTestBase() {
    logFileBase = Paths.get(buildDirectory(), "reports", "testProcess." + getClass().getName());
  }

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    Files.createDirectories(logFileBase);
    logFilePath =
        logFileBase.resolve(
            testInfo.getTestMethod().map(method -> method.getName()).orElse(defaultLogName())
                + ".log");
    dumpDir = Files.createTempDirectory(tempDirPrefix());
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  /**
   * Prefix passed to {@link Files#createTempDirectory}, e.g. {@code "dd-profiler-locksupport-"}.
   */
  protected abstract String tempDirPrefix();

  /** Log file name used when the test method name can't be resolved. */
  protected abstract String defaultLogName();

  /** Value for {@code -Ddd.service.name}. */
  protected abstract String serviceName();

  /** Forked-process main class, passed as the last classpath argument. */
  protected abstract Class<?> forkedAppClass();

  protected abstract S newStats();

  protected abstract void addEvents(S stats, IItemCollection events);

  protected ProcessBuilder createProcessBuilder() {
    String templateOverride = getClass().getClassLoader().getResource("overrides.jfp").getFile();
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                javaPath(),
                "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
                "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
                "-javaagent:" + agentShadowJar(),
                "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                "-Ddd.service.name=" + serviceName(),
                "-Ddd.env=smoketest",
                "-Ddd.version=99",
                "-Ddd.profiling.enabled=true",
                "-Ddd.profiling.ddprof.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_AUXILIARY_TYPE + "=async",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED + "=true",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL + "=10ms",
                "-Ddd.profiling.agentless=false",
                "-Ddd.profiling.start-delay=0",
                "-Ddd." + ProfilingConfig.PROFILING_START_FORCE_FIRST + "=true",
                "-Ddd.profiling.upload.period=1",
                "-Ddd.profiling.hotspots.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED + "=true",
                "-Ddd.profiling.debug.dump_path=" + dumpDir,
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK + "=true",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
                "-cp",
                System.getProperty("java.class.path"),
                forkedAppClass().getName()));
    if (System.getenv("TEST_LIBASYNC") != null) {
      command.add(
          command.size() - 3,
          "-Ddd."
              + ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH
              + "="
              + System.getenv("TEST_LIBASYNC"));
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  protected S loadStats() throws Exception {
    S stats = newStats();
    List<Path> jfrFiles;
    try (Stream<Path> files = Files.walk(dumpDir)) {
      jfrFiles =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".jfr"))
              .collect(Collectors.toList());
    }
    for (Path jfrFile : jfrFiles) {
      addEvents(stats, loadEvents(jfrFile));
    }
    return stats;
  }

  private IItemCollection loadEvents(Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(extractLastJfrStream(path).toFile());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load JFR " + path, e);
    }
  }

  private Path extractLastJfrStream(Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    int lastMagic = lastIndexOf(data, JFR_MAGIC);
    if (lastMagic <= 0) {
      return path;
    }

    Path extracted = dumpDir.resolve(path.getFileName() + ".ddprof.jfr");
    Files.write(extracted, Arrays.copyOfRange(data, lastMagic, data.length));
    return extracted;
  }

  private static int lastIndexOf(byte[] data, byte[] needle) {
    for (int i = data.length - needle.length; i >= 0; i--) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }
}
