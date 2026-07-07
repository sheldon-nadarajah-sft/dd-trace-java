package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * End-to-end smoke test for the {@code thread-sleep} instrumentation: forks a real JVM with {@code
 * -javaagent:} attached and asserts that spanless {@code Thread.sleep} call sites emit {@code
 * datadog.TaskBlock} JFR events. The fixture deliberately mixes traced and untraced sleeps to
 * verify that active-span sleeps are left to normal wall-clock samples while TaskBlock summarizes
 * untraced blocked runs.
 *
 * <p>Runs on every JDK supported by the rest of the suite. The native JVMTI MonitorWait path covers
 * {@code Object.wait()}, not {@code Thread.sleep}, so this test is the direct coverage guard for
 * sleep call-site instrumentation.
 */
@DisabledOnJ9
final class ThreadSleepTaskBlockProfilingTest
    extends TaskBlockProfilingTestBase<ThreadSleepTaskBlockProfilingTest.JfrStats> {

  @Test
  @DisplayName("Spanless Thread.sleep emits zero-context TaskBlock events")
  void threadSleepEmitsTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(
        stats.spanlessTaskBlockCount > 0,
        "Expected datadog.TaskBlock events from spanless Thread.sleep call sites");
    assertTrue(
        stats.neverAttachedTaskBlockCount > 0,
        "Expected datadog.TaskBlock events from never-attached spanless Thread.sleep call sites");
    assertFalse(stats.hasActiveSpanTaskBlock, "Active-span sleeps must not emit TaskBlock events");
    assertFalse(stats.hasNonZeroSpanId, "Spanless TaskBlock events must carry zero spanId");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId,
        "Spanless TaskBlock events must carry zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        logHasInstrumentationError(
            "Failed to handle exception in instrumentation for "
                + com.datadog.smoketest.profiling.ThreadSleepTaskBlockForkedApp.class.getName()),
        "thread-sleep instrumentation produced classloading or rewrite errors in the forked log");
  }

  @Override
  protected String tempDirPrefix() {
    return "dd-profiler-threadsleep-";
  }

  @Override
  protected String defaultLogName() {
    return "threadSleep";
  }

  @Override
  protected String serviceName() {
    return "smoke-test-threadsleep-taskblock";
  }

  @Override
  protected Class<?> forkedAppClass() {
    return com.datadog.smoketest.profiling.ThreadSleepTaskBlockForkedApp.class;
  }

  @Override
  protected JfrStats newStats() {
    return new JfrStats();
  }

  @Override
  protected void addEvents(JfrStats stats, IItemCollection events) {
    stats.add(events);
  }

  /** Aggregate counts/flags collected across all JFR streams produced by the forked process. */
  static final class JfrStats {
    long spanlessTaskBlockCount;
    long neverAttachedTaskBlockCount;
    boolean hasActiveSpanTaskBlock;
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    boolean hasMissingEventThread;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> eventThread =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        if (span == null || root == null) {
          continue;
        }
        for (IItem item : items) {
          String threadName = eventThread == null ? null : eventThread.getMember(item);
          if ("threadsleep-active".equals(threadName)) {
            hasActiveSpanTaskBlock = true;
            continue;
          }
          boolean spanlessThread = "threadsleep-spanless".equals(threadName);
          boolean neverAttachedThread = "threadsleep-never-attached".equals(threadName);
          if (!spanlessThread && !neverAttachedThread) {
            continue;
          }
          long spanId = span.getMember(item).longValue();
          long rootSpanId = root.getMember(item).longValue();
          if (spanlessThread) {
            spanlessTaskBlockCount++;
          } else {
            neverAttachedTaskBlockCount++;
          }
          hasNonZeroSpanId |= spanId != 0L;
          hasNonZeroLocalRootSpanId |= rootSpanId != 0L;
          hasMissingEventThread |= threadName == null || threadName.isEmpty();
        }
      }
    }
  }
}
