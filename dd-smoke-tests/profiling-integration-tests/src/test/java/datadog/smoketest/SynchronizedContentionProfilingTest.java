package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
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
 * Smoke test for native synchronized-contention TaskBlock coverage. Asserts that block-level {@code
 * synchronized(obj){}} and method-level {@code synchronized} contention emit {@code
 * datadog.TaskBlock} zero-context events with a non-zero {@code blocker} field through the native
 * JVMTI {@code MonitorContendedEnter}/{@code MonitorContendedEntered} path.
 */
@DisabledOnJ9
final class SynchronizedContentionProfilingTest
    extends TaskBlockProfilingTestBase<SynchronizedContentionProfilingTest.JfrStats> {

  @Test
  @DisplayName("synchronized block and method contention emit zero-context native TaskBlock events")
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

    // Every emitted event must be spanless; active-span monitor blocks are rejected by native.
    assertFalse(stats.hasNonZeroSpanId, "TaskBlock events must carry zero spanId");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId, "TaskBlock events must carry zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");

    // The blocker field must identify the contested monitor (non-zero).
    assertTrue(stats.blockersWithNonZeroValue > 0, "Expected non-zero blocker on TaskBlock events");

    // The three scenarios contend on three distinct locks, so the blocker values must not all be
    // identical — this proves the rewriter is recording per-monitor identity, not a constant.
    assertTrue(
        stats.distinctBlockerValues.size() > 1,
        "Expected distinct blocker values across the three contention scenarios");

    assertFalse(
        logHasInstrumentationError("Failed to handle exception in instrumentation", "VerifyError"),
        "native synchronized-contention TaskBlock path must not produce errors");
  }

  @Override
  protected String tempDirPrefix() {
    return "dd-profiler-synccontention-";
  }

  @Override
  protected String defaultLogName() {
    return "syncContention";
  }

  @Override
  protected String serviceName() {
    return "smoke-test-synccontention-taskblock";
  }

  @Override
  protected Class<?> forkedAppClass() {
    return com.datadog.smoketest.profiling.SynchronizedContentionForkedApp.class;
  }

  @Override
  protected JfrStats newStats() {
    return new JfrStats();
  }

  @Override
  protected void addEvents(JfrStats stats, IItemCollection events) {
    stats.add(events);
  }

  // ------------------------------------------------------------------ stats

  static final class JfrStats {
    long blockScenarioCount;
    long instanceMethodScenarioCount;
    long staticMethodScenarioCount;
    long blockersWithNonZeroValue;
    final Set<Long> distinctBlockerValues = new HashSet<>();
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    boolean hasMissingEventThread;

    void add(final IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> spanIdAcc = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootSpanIdAcc =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAcc = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadAcc =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = threadAcc == null ? null : threadAcc.getMember(item);
          if (!"sync-block-contender".equals(thread)
              && !"sync-instance-contender".equals(thread)
              && !"sync-static-contender".equals(thread)) {
            continue;
          }
          if ("sync-block-contender".equals(thread)) blockScenarioCount++;
          else if ("sync-instance-contender".equals(thread)) instanceMethodScenarioCount++;
          else staticMethodScenarioCount++;

          if (spanIdAcc != null) {
            long spanId = spanIdAcc.getMember(item).longValue();
            hasNonZeroSpanId |= spanId != 0;
          }
          if (rootSpanIdAcc != null) {
            long rootId = rootSpanIdAcc.getMember(item).longValue();
            hasNonZeroLocalRootSpanId |= rootId != 0;
          }
          if (blockerAcc != null) {
            long blocker = blockerAcc.getMember(item).longValue();
            if (blocker != 0) {
              blockersWithNonZeroValue++;
              distinctBlockerValues.add(blocker);
            }
          }
          hasMissingEventThread |= thread == null || thread.isEmpty();
        }
      }
    }
  }
}
