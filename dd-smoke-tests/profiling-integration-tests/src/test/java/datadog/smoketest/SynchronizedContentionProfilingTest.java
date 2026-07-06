package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.BLOCKER;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.OPERATION;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * Smoke test for native synchronized-contention TaskBlock coverage. Asserts that block-level {@code
 * synchronized(obj){}} and method-level {@code synchronized} contention emit {@code
 * datadog.TaskBlock} events with a non-zero {@code blocker} field through the native JVMTI {@code
 * MonitorContendedEnter}/{@code MonitorContendedEntered} path.
 */
@DisabledOnJ9
final class SynchronizedContentionProfilingTest {
  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            SynchronizedContentionProfilingTest.class, testInfo, "syncContention");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-synccontention-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  @DisplayName(
      "synchronized block and method contention emit span-attributed native TaskBlock events")
  void synchronizedContentionEmitsTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();

    // Each scenario must have produced at least one TaskBlock event.
    assertTrue(
        stats.blockScenarioCount > 0,
        "Expected TaskBlock events for synchronized block contention");
    assertTrue(
        stats.instanceMethodScenarioCount > 0,
        "Expected TaskBlock events for synchronized instance-method contention");
    assertTrue(
        stats.staticMethodScenarioCount > 0,
        "Expected TaskBlock events for synchronized static-method contention");

    // Every emitted event must carry a valid span context.
    assertFalse(stats.hasZeroSpanId, "TaskBlock events must have non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId, "TaskBlock events must have non-zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");

    // The blocker field must identify the contested monitor (non-zero).
    assertTrue(stats.blockersWithNonZeroValue > 0, "Expected non-zero blocker on TaskBlock events");

    // The three scenarios contend on three distinct locks, so the blocker values must not all be
    // identical — this proves the rewriter is recording per-monitor identity, not a constant.
    assertTrue(
        stats.distinctBlockerValues.size() > 1,
        "Expected distinct blocker values across the three contention scenarios");

    assertFalse(
        logHasSynchronizedContentionError(),
        "native synchronized-contention TaskBlock path must not produce errors");
  }

  private ProcessBuilder createProcessBuilder() throws IOException {
    return TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
        "smoke-test-synccontention-taskblock",
        com.datadog.smoketest.profiling.SynchronizedContentionForkedApp.class.getName(),
        dumpDir,
        logFilePath);
  }

  private JfrStats loadStats() throws Exception {
    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }
    return stats;
  }

  private boolean logHasSynchronizedContentionError() throws IOException {
    return TaskBlockProfilingTestSupport.logContainsAny(
        logFilePath,
        "NoClassDefFoundError",
        "Failed to handle exception in instrumentation",
        "VerifyError");
  }

  // ------------------------------------------------------------------ stats

  private static final class JfrStats {
    long blockScenarioCount;
    long instanceMethodScenarioCount;
    long staticMethodScenarioCount;
    long blockersWithNonZeroValue;
    final Set<Long> distinctBlockerValues = new HashSet<>();
    boolean hasZeroSpanId;
    boolean hasZeroLocalRootSpanId;
    boolean hasMissingEventThread;

    void add(final IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> spanIdAcc = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootSpanIdAcc =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAcc = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<String, IItem> operationAcc = OPERATION.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadAcc =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        for (IItem item : items) {
          String op = operationAcc == null ? null : operationAcc.getMember(item);
          if (!"sync.block".equals(op)
              && !"sync.instance-method".equals(op)
              && !"sync.static-method".equals(op)) {
            continue;
          }
          if ("sync.block".equals(op)) blockScenarioCount++;
          else if ("sync.instance-method".equals(op)) instanceMethodScenarioCount++;
          else staticMethodScenarioCount++;

          if (spanIdAcc != null) {
            long spanId = spanIdAcc.getMember(item).longValue();
            hasZeroSpanId |= spanId == 0;
          }
          if (rootSpanIdAcc != null) {
            long rootId = rootSpanIdAcc.getMember(item).longValue();
            hasZeroLocalRootSpanId |= rootId == 0;
          }
          if (blockerAcc != null) {
            long blocker = blockerAcc.getMember(item).longValue();
            if (blocker != 0) {
              blockersWithNonZeroValue++;
              distinctBlockerValues.add(blocker);
            }
          }
          String thread = threadAcc == null ? null : threadAcc.getMember(item);
          hasMissingEventThread |= thread == null || thread.isEmpty();
        }
      }
    }
  }
}
