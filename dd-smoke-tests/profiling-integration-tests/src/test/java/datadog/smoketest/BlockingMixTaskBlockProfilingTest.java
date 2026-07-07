package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;

/**
 * End-to-end mixed-blocking smoke / regression / demo test. Combines three roles in one fixture:
 *
 * <ol>
 *   <li><b>Cross-workstream smoke</b>: a single forked JVM under {@code -javaagent:} exercises
 *       {@code Thread.sleep}, {@code LockSupport.park*}, and native {@code synchronized}
 *       contention. Each population's events must be present.
 *   <li><b>NoDoubleBracket</b>: each blocking <em>interval</em> emits exactly one {@code
 *       datadog.TaskBlock} event. Multiple TaskBlocks for the same (thread, start time) point at a
 *       regression in the Java helper paths vs. the native JVMTI path or at overlapping helper
 *       invocations.
 *   <li><b>BlockingMix demo</b>: the forked app is meant to be copy-pasted as a reproducer when
 *       triaging coverage issues. The runbook below lists JFR inspection commands and the expected
 *       scenario thread-name distribution.
 * </ol>
 *
 * <h3>Demo runbook (manual, off-CI)</h3>
 *
 * <pre>
 *   # 1. Run the forked app standalone to produce a JFR
 *   ./gradlew :dd-smoke-tests:profiling-integration-tests:test \
 *       --tests "*BlockingMixTaskBlockProfilingTest*" \
 *       -Ddatadog.forkedTestRetainDumps=true
 *
 *   # 2. Inspect populations
 *   jfr summary {dumpDir}/*.jfr | grep -E "datadog.TaskBlock|wall=" -A1
 *
 *   # 3. List per-scenario thread counts
 *   jfr print --events "datadog.TaskBlock" {dumpDir}/*.jfr \
 *       | grep -oE "eventThread = \\{[^}]+\\}" | sort | uniq -c
 *
 *   # 4. Expected (steady state):
 *   #     N>=20 blockingmix-sleep
 *   #     N=20  blockingmix-park
 *   #     N=20  blockingmix-sync   (native JVMTI monitor callbacks)
 *
 *   # 5. Native counter snapshot:
 *   jfr print --events "datadog.DatadogProfilerConfig" {dumpDir}/*.jfr
 * </pre>
 */
@DisabledOnJ9
final class BlockingMixTaskBlockProfilingTest
    extends TaskBlockProfilingTestBase<BlockingMixTaskBlockProfilingTest.JfrStats> {

  private static final IAttribute<IQuantity> START_TIME =
      attr("startTime", "startTime", "startTime", NUMBER);
  private static final IAttribute<IQuantity> DURATION =
      attr("duration", "duration", "duration", NUMBER);
  private static final IAttribute<String> EVENT_THREAD_NAME =
      attr(
          "eventThread.threadName", "eventThread.threadName", "eventThread.threadName", PLAIN_TEXT);

  private static final String THREAD_SLEEP = "blockingmix-sleep";
  private static final String THREAD_PARK = "blockingmix-park";
  private static final String THREAD_SYNC = "blockingmix-sync";

  @Test
  @DisplayName("Mixed sleep+park+sync workload emits one TaskBlock per blocking interval")
  void mixedBlockingWorkloadEmitsExpectedPopulations() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();

    // ---- Smoke ----: every population must be present.
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SLEEP, 0L) > 0,
        "Expected blockingmix.sleep TaskBlock events (thread-sleep call-site module)");
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_PARK, 0L) > 0,
        "Expected blockingmix.park TaskBlock events (existing lock-support module)");
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SYNC, 0L) > 0,
        "Expected blockingmix.sync TaskBlock events (native JVMTI monitor callbacks)");

    // ---- NoDoubleBracket ----: no two TaskBlock events on the same thread with overlapping
    // intervals for the same operation. Double-bracketing manifests as two events with the same
    // start time (Java helper + native callback both firing for the same blocking interval).
    assertFalse(
        stats.hasDuplicateInterval,
        "Detected duplicate TaskBlock events for the same (thread, startTime) — double bracket "
            + "regression. Likely culprit: Java helper and native callback both firing for the "
            + "same blocking population. First duplicate: "
            + stats.firstDuplicateDescription);

    // ---- Span context ----: all TaskBlock events in this workload must be spanless.
    assertFalse(
        stats.hasNonZeroSpanId,
        "TaskBlock events from the mixed workload must all carry zero spanId");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId,
        "TaskBlock events from the mixed workload must all carry zero localRootSpanId");

    // ---- Health ----: no instrumentation classloading or rewrite failures in the forked log.
    assertFalse(
        logHasInstrumentationError("Failed to handle exception in instrumentation for"),
        "Instrumentation produced classloading / rewrite errors in the forked log");
  }

  // ------------------------------------------------------------------------------------------
  // Process / JFR plumbing
  // ------------------------------------------------------------------------------------------

  @Override
  protected String tempDirPrefix() {
    return "dd-profiler-blockingmix-";
  }

  @Override
  protected String defaultLogName() {
    return "blockingMix";
  }

  @Override
  protected String serviceName() {
    return "smoke-test-blockingmix-taskblock";
  }

  @Override
  protected Class<?> forkedAppClass() {
    return com.datadog.smoketest.profiling.BlockingMixForkedApp.class;
  }

  @Override
  protected JfrStats newStats() {
    return new JfrStats();
  }

  @Override
  protected void addEvents(JfrStats stats, IItemCollection events) {
    stats.add(events);
  }

  static final class JfrStats {
    final Map<String, Long> countByThread = new HashMap<>();
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    boolean hasDuplicateInterval;
    String firstDuplicateDescription;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      // Detect double-bracketing: events that share (thread, startTime). Using startTime alone is
      // intentionally strict — overlapping windows on different threads are fine, but two events
      // on the same thread starting at the same wall-clock instant indicate the helper AND
      // native path both fired for one blocking interval.
      Set<String> seenIntervals = new HashSet<>();
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> startTime = START_TIME.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadName = EVENT_THREAD_NAME.getAccessor(items.getType());
        if (span == null || root == null) {
          continue;
        }
        for (IItem item : items) {
          long spanId = span.getMember(item).longValue();
          long rootSpanId = root.getMember(item).longValue();
          String thread = threadName == null ? null : threadName.getMember(item);
          if (!THREAD_SLEEP.equals(thread)
              && !THREAD_PARK.equals(thread)
              && !THREAD_SYNC.equals(thread)) {
            continue;
          }
          countByThread.merge(thread, 1L, Long::sum);
          hasNonZeroSpanId |= spanId != 0L;
          hasNonZeroLocalRootSpanId |= rootSpanId != 0L;
          if (startTime != null && threadName != null) {
            String key = thread + "@" + startTime.getMember(item).longValue();
            if (!seenIntervals.add(key) && firstDuplicateDescription == null) {
              hasDuplicateInterval = true;
              firstDuplicateDescription = key;
            }
          }
        }
      }
    }
  }
}
