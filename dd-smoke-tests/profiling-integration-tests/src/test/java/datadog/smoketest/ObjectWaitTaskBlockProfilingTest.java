package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * Smoke test for native {@code Object.wait} TaskBlock coverage through the profiler's JVMTI {@code
 * MonitorWait}/{@code MonitorWaited} callbacks.
 */
@DisabledOnJ9
final class ObjectWaitTaskBlockProfilingTest
    extends TaskBlockProfilingTestBase<ObjectWaitTaskBlockProfilingTest.JfrStats> {
  private static final IAttribute<IQuantity> UNBLOCKING_SPAN_ID =
      attr("unblockingSpanId", "unblockingSpanId", "unblockingSpanId", NUMBER);
  private static final IAttribute<IQuantity> TASK_BLOCK_EMITTED =
      attr("numTaskBlockEmitted", "numTaskBlockEmitted", "numTaskBlockEmitted", NUMBER);

  @Test
  @DisplayName("Object.wait emits span-attributed native TaskBlock events")
  void objectWaitsEmitTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(stats.taskBlockCount > 0, "Expected datadog.TaskBlock events");
    assertFalse(
        stats.hasMissingTaskBlockAttribute,
        "TaskBlock events must include spanId, localRootSpanId, blocker, and unblockingSpanId");
    assertFalse(
        stats.hasMissingTaskBlockEmittedAttribute,
        "WallClockSamplingEpoch events must include numTaskBlockEmitted");
    assertTrue(stats.taskBlockEmitted > 0, "Expected numTaskBlockEmitted counter");
    assertTrue(stats.taskBlocksWithNonZeroBlocker > 0, "Expected monitor identity to be recorded");
    assertFalse(stats.hasZeroSpanId, "TaskBlock events must have non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId, "TaskBlock events must have non-zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertTrue(
        stats.hasExpectedOperation,
        "Expected TaskBlock events to include the objectwait.active span operation name");
    // notify/notifyAll are not instrumented, so the unblocking thread is not identified.
    assertFalse(
        stats.hasNonZeroUnblockingSpanId,
        "Object.wait TaskBlocks must report unblockingSpanId == 0 (notify is still native)");
    assertFalse(logHasObjectWaitInstrumentationError(), "Object.wait instrumentation failed");
  }

  @Override
  protected String tempDirPrefix() {
    return "dd-profiler-objectwait-";
  }

  @Override
  protected String defaultLogName() {
    return "objectWait";
  }

  @Override
  protected String serviceName() {
    return "smoke-test-objectwait-taskblock";
  }

  @Override
  protected Class<?> forkedAppClass() {
    return ObjectWaitTaskBlockForkedApp.class;
  }

  @Override
  protected JfrStats newStats() {
    return new JfrStats();
  }

  @Override
  protected void addEvents(JfrStats stats, IItemCollection events) {
    stats.add(events);
  }

  private boolean logHasObjectWaitInstrumentationError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains("Failed to handle exception in instrumentation for java.lang.Object");
  }

  public static final class ObjectWaitTaskBlockForkedApp {
    private static final int WAIT_ITERATIONS = 20;
    private static final long LONG_WAIT_MILLIS = 50L;
    private static final Object BLOCKER = new Object();

    public static void main(String[] args) throws Exception {
      ObjectWaitTaskBlockForkedApp app = new ObjectWaitTaskBlockForkedApp(GlobalTracer.get());
      app.runActiveSpanWaits();
      app.runSpanlessWaits();
      app.runTooShortWaits();
      Thread.sleep(1500);
    }

    private final Tracer tracer;

    private ObjectWaitTaskBlockForkedApp(Tracer tracer) {
      this.tracer = tracer;
    }

    private void runActiveSpanWaits() throws InterruptedException {
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        Span span = tracer.buildSpan("objectwait.active").start();
        try (Scope scope = tracer.activateSpan(span)) {
          synchronized (BLOCKER) {
            BLOCKER.wait(LONG_WAIT_MILLIS);
          }
        } finally {
          span.finish();
        }
      }
    }

    private void runSpanlessWaits() throws InterruptedException {
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        synchronized (BLOCKER) {
          BLOCKER.wait(LONG_WAIT_MILLIS);
        }
      }
    }

    private void runTooShortWaits() throws InterruptedException {
      // Exercises the wait(long, int) path. The native task-block threshold is 1 ms, so a 1-ms
      // request typically rounds out just over it, but path coverage matters more than duration
      // here; the assertion focuses on callbacks not crashing rather than which side of the
      // threshold the interval lands on.
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        Span span = tracer.buildSpan("objectwait.too-short").start();
        try (Scope scope = tracer.activateSpan(span)) {
          synchronized (BLOCKER) {
            BLOCKER.wait(0L, 1);
          }
        } finally {
          span.finish();
        }
      }
    }
  }

  static final class JfrStats {
    private long taskBlockCount;
    private long taskBlockEmitted;
    private long taskBlocksWithNonZeroBlocker;
    private boolean hasZeroSpanId;
    private boolean hasZeroLocalRootSpanId;
    private boolean hasMissingEventThread;
    private boolean hasExpectedOperation;
    private boolean hasNonZeroUnblockingSpanId;
    private boolean hasMissingTaskBlockAttribute;
    private boolean hasMissingTaskBlockEmittedAttribute;

    private void add(IItemCollection events) {
      addTaskBlocks(events);
      addWallClockEpochs(events);
    }

    private void addTaskBlocks(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> spanIdAccessor = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> localRootSpanIdAccessor =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAccessor = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> unblockingSpanIdAccessor =
            UNBLOCKING_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> eventThreadAccessor =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        IMemberAccessor<String, IItem> operationAccessor = OPERATION.getAccessor(items.getType());
        for (IItem item : items) {
          String operation = operationAccessor == null ? null : operationAccessor.getMember(item);
          // Filter strictly to events emitted by our forked app; the JVM may emit other
          // TaskBlock events (LockSupport from agent code, etc.) that we don't want to mix in.
          if (!"objectwait.active".equals(operation) && !"objectwait.too-short".equals(operation)) {
            continue;
          }
          taskBlockCount++;
          if (spanIdAccessor != null) {
            long spanId = spanIdAccessor.getMember(item).longValue();
            hasZeroSpanId |= spanId == 0;
          } else {
            hasMissingTaskBlockAttribute = true;
          }
          if (localRootSpanIdAccessor != null) {
            long localRootSpanId = localRootSpanIdAccessor.getMember(item).longValue();
            hasZeroLocalRootSpanId |= localRootSpanId == 0;
          } else {
            hasMissingTaskBlockAttribute = true;
          }
          if (blockerAccessor != null) {
            long blocker = blockerAccessor.getMember(item).longValue();
            if (blocker != 0) {
              taskBlocksWithNonZeroBlocker++;
            }
          } else {
            hasMissingTaskBlockAttribute = true;
          }
          if (unblockingSpanIdAccessor != null) {
            long unblockingSpanId = unblockingSpanIdAccessor.getMember(item).longValue();
            if (unblockingSpanId != 0) {
              hasNonZeroUnblockingSpanId = true;
            }
          } else {
            hasMissingTaskBlockAttribute = true;
          }
          String eventThread =
              eventThreadAccessor == null ? null : eventThreadAccessor.getMember(item);
          hasMissingEventThread |= eventThread == null || eventThread.isEmpty();
          hasExpectedOperation |= "objectwait.active".equals(operation);
        }
      }
    }

    private void addWallClockEpochs(IItemCollection events) {
      IItemCollection epochs = events.apply(ItemFilters.type("datadog.WallClockSamplingEpoch"));
      for (IItemIterable items : epochs) {
        IMemberAccessor<IQuantity, IItem> emittedAccessor =
            TASK_BLOCK_EMITTED.getAccessor(items.getType());
        if (emittedAccessor == null) {
          hasMissingTaskBlockEmittedAttribute = true;
          continue;
        }
        for (IItem item : items) {
          taskBlockEmitted += emittedAccessor.getMember(item).longValue();
        }
      }
    }
  }
}
