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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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

@DisabledOnJ9
final class LockSupportTaskBlockProfilingTest
    extends TaskBlockProfilingTestBase<LockSupportTaskBlockProfilingTest.JfrStats> {
  private static final IAttribute<IQuantity> UNBLOCKING_SPAN_ID =
      attr("unblockingSpanId", "unblockingSpanId", "unblockingSpanId", NUMBER);
  private static final IAttribute<IQuantity> TASK_BLOCK_EMITTED =
      attr("numTaskBlockEmitted", "numTaskBlockEmitted", "numTaskBlockEmitted", NUMBER);
  private static final IAttribute<IQuantity> TASK_BLOCK_SKIPPED_SPAN_ZERO =
      attr(
          "numTaskBlockSkippedSpanZero",
          "numTaskBlockSkippedSpanZero",
          "numTaskBlockSkippedSpanZero",
          NUMBER);
  private static final IAttribute<IQuantity> TASK_BLOCK_SKIPPED_TOO_SHORT =
      attr(
          "numTaskBlockSkippedTooShort",
          "numTaskBlockSkippedTooShort",
          "numTaskBlockSkippedTooShort",
          NUMBER);

  @Test
  @DisplayName("LockSupport parks emit span-attributed TaskBlock events")
  void lockSupportParksEmitTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(stats.taskBlockCount > 0, "Expected datadog.TaskBlock events");
    assertTrue(stats.taskBlockEmitted > 0, "Expected numTaskBlockEmitted counter");
    assertTrue(stats.taskBlockSkippedSpanZero > 0, "Expected spanless parks to be skipped");
    assertTrue(stats.taskBlockSkippedTooShort > 0, "Expected short parks to be skipped");
    assertTrue(stats.taskBlocksWithNonZeroBlocker > 0, "Expected blocker identity to be recorded");
    assertTrue(stats.taskBlocksWithUnblockingSpan > 0, "Expected unblocking span to be recorded");
    assertFalse(stats.hasZeroSpanId, "TaskBlock events must have non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId, "TaskBlock events must have non-zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertTrue(
        stats.hasExpectedOperation,
        "Expected TaskBlock events to include LockSupport span operation names");
    assertFalse(logHasLockSupportInstrumentationError(), "LockSupport instrumentation failed");
  }

  @Override
  protected String tempDirPrefix() {
    return "dd-profiler-locksupport-";
  }

  @Override
  protected String defaultLogName() {
    return "lockSupport";
  }

  @Override
  protected String serviceName() {
    return "smoke-test-locksupport-taskblock";
  }

  @Override
  protected Class<?> forkedAppClass() {
    return LockSupportTaskBlockForkedApp.class;
  }

  @Override
  protected JfrStats newStats() {
    return new JfrStats();
  }

  @Override
  protected void addEvents(JfrStats stats, IItemCollection events) {
    stats.add(events);
  }

  private boolean logHasLockSupportInstrumentationError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains(
            "Failed to handle exception in instrumentation for java.util.concurrent.locks.LockSupport");
  }

  public static final class LockSupportTaskBlockForkedApp {
    private static final int PARK_ITERATIONS = 20;
    private static final long LONG_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long SHORT_PARK_NANOS = 1L;
    private static final Object BLOCKER = new Object();

    public static void main(String[] args) throws Exception {
      LockSupportTaskBlockForkedApp app = new LockSupportTaskBlockForkedApp(GlobalTracer.get());
      app.runActiveSpanParks();
      app.runSpanlessParks();
      app.runTooShortParks();
      for (int i = 0; i < 5; i++) {
        app.runUnparkAttribution();
      }
      Thread.sleep(1500);
    }

    private final Tracer tracer;

    private LockSupportTaskBlockForkedApp(Tracer tracer) {
      this.tracer = tracer;
    }

    private void runActiveSpanParks() {
      for (int i = 0; i < PARK_ITERATIONS; i++) {
        Span span = tracer.buildSpan("locksupport.active").start();
        try (Scope scope = tracer.activateSpan(span)) {
          LockSupport.parkNanos(BLOCKER, LONG_PARK_NANOS);
        } finally {
          span.finish();
        }
      }
    }

    private void runSpanlessParks() {
      for (int i = 0; i < PARK_ITERATIONS; i++) {
        LockSupport.parkNanos(BLOCKER, LONG_PARK_NANOS);
      }
    }

    private void runTooShortParks() {
      for (int i = 0; i < PARK_ITERATIONS; i++) {
        Span span = tracer.buildSpan("locksupport.too-short").start();
        try (Scope scope = tracer.activateSpan(span)) {
          LockSupport.parkNanos(BLOCKER, SHORT_PARK_NANOS);
        } finally {
          span.finish();
        }
      }
    }

    private void runUnparkAttribution() throws Exception {
      CountDownLatch parkedThreadReady = new CountDownLatch(1);
      Thread parkedThread =
          new Thread(
              () -> {
                Span span = tracer.buildSpan("locksupport.unpark.parked").start();
                try (Scope scope = tracer.activateSpan(span)) {
                  parkedThreadReady.countDown();
                  LockSupport.parkNanos(BLOCKER, TimeUnit.SECONDS.toNanos(5));
                } finally {
                  span.finish();
                }
              },
              "locksupport-taskblock-parked");

      parkedThread.start();
      parkedThreadReady.await();
      Thread.sleep(50);

      Span unparkingSpan = tracer.buildSpan("locksupport.unpark.unparker").start();
      try (Scope scope = tracer.activateSpan(unparkingSpan)) {
        LockSupport.unpark(parkedThread);
      } finally {
        unparkingSpan.finish();
      }

      parkedThread.join(TimeUnit.SECONDS.toMillis(5));
      if (parkedThread.isAlive()) {
        throw new IllegalStateException("Parked thread did not finish");
      }
    }
  }

  static final class JfrStats {
    private long taskBlockCount;
    private long taskBlockEmitted;
    private long taskBlockSkippedSpanZero;
    private long taskBlockSkippedTooShort;
    private long taskBlocksWithNonZeroBlocker;
    private long taskBlocksWithUnblockingSpan;
    private boolean hasZeroSpanId;
    private boolean hasZeroLocalRootSpanId;
    private boolean hasMissingEventThread;
    private boolean hasExpectedOperation;

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
          taskBlockCount++;
          long spanId = spanIdAccessor.getMember(item).longValue();
          long localRootSpanId = localRootSpanIdAccessor.getMember(item).longValue();
          long blocker = blockerAccessor.getMember(item).longValue();
          long unblockingSpanId = unblockingSpanIdAccessor.getMember(item).longValue();
          String eventThread =
              eventThreadAccessor == null ? null : eventThreadAccessor.getMember(item);
          String operation = operationAccessor == null ? null : operationAccessor.getMember(item);
          hasZeroSpanId |= spanId == 0;
          hasZeroLocalRootSpanId |= localRootSpanId == 0;
          hasMissingEventThread |= eventThread == null || eventThread.isEmpty();
          hasExpectedOperation |=
              "locksupport.active".equals(operation)
                  || "locksupport.unpark.parked".equals(operation);
          if (blocker != 0) {
            taskBlocksWithNonZeroBlocker++;
          }
          if (unblockingSpanId != 0) {
            taskBlocksWithUnblockingSpan++;
          }
        }
      }
    }

    private void addWallClockEpochs(IItemCollection events) {
      IItemCollection epochs = events.apply(ItemFilters.type("datadog.WallClockSamplingEpoch"));
      for (IItemIterable items : epochs) {
        IMemberAccessor<IQuantity, IItem> emittedAccessor =
            TASK_BLOCK_EMITTED.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> spanZeroAccessor =
            TASK_BLOCK_SKIPPED_SPAN_ZERO.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> tooShortAccessor =
            TASK_BLOCK_SKIPPED_TOO_SHORT.getAccessor(items.getType());
        for (IItem item : items) {
          taskBlockEmitted += emittedAccessor.getMember(item).longValue();
          taskBlockSkippedSpanZero += spanZeroAccessor.getMember(item).longValue();
          taskBlockSkippedTooShort += tooShortAccessor.getMember(item).longValue();
        }
      }
    }
  }
}
