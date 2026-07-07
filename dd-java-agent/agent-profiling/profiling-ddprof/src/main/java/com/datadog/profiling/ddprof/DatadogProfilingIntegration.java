package com.datadog.profiling.ddprof;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.newInstance();
  private static final int SPAN_NAME_INDEX = DDPROF.operationNameOffset();
  private static final int RESOURCE_NAME_INDEX = DDPROF.resourceNameOffset();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  private static final boolean IS_ENDPOINT_COLLECTION_ENABLED =
      DatadogProfilerConfig.isEndpointTrackingEnabled();

  // don't use Config because it may use ThreadPoolExecutor to initialize itself
  private static final boolean IS_PROFILING_QUEUEING_TIME_ENABLED =
      DatadogProfilerConfig.isQueueTimeEnabled();

  // --- Async TaskBlock recording infrastructure (Thread.sleep deferred path) ---

  // Pre-cached native tid per thread. Traced threads populate this in onAttach(); spanless
  // Thread.sleep instrumentation lazily resolves it without calling onAttach(), because onAttach()
  // also changes wall-clock thread filtering state.
  private static final ThreadLocal<Integer> NATIVE_TID = new ThreadLocal<>();

  // Bounded queue for deferred TaskBlock events. offer() is non-blocking; a full queue drops events
  // and increments DROPPED_TASK_BLOCKS for diagnostics.
  // Entry layout:
  // [tid, startTicks, durationNanos, blocker, spanId, rootSpanId,
  //  anchorSampleId, suppressedSampleCount, observedBlockingState]
  private static final ArrayBlockingQueue<long[]> TASK_BLOCK_QUEUE = new ArrayBlockingQueue<>(2048);
  private static final AtomicLong DROPPED_TASK_BLOCKS = new AtomicLong();

  // TSC frequency used by the drain thread to convert durationNanos to endTicks.
  // Initial value avoids zero-frequency conversion before onStart() publishes the profiler value.
  private static volatile long TSC_FREQUENCY = 1_000_000_000L;

  private static volatile Thread drainThread;

  private final Stateful contextManager =
      new Stateful() {
        @Override
        public void close() {
          DDPROF.clearSpanContext();
          DDPROF.clearContextValue(SPAN_NAME_INDEX);
          DDPROF.clearContextValue(RESOURCE_NAME_INDEX);
        }

        @Override
        public void activate(Object context) {
          if (context instanceof ProfilerContext) {
            ProfilerContext profilerContext = (ProfilerContext) context;
            // setSpanContext() calls reapplyAppContext() internally, so no explicit call is needed.
            DDPROF.setSpanContext(
                profilerContext.getRootSpanId(),
                profilerContext.getSpanId(),
                profilerContext.getTraceIdHigh(),
                profilerContext.getTraceIdLow());
            DDPROF.setContextValue(SPAN_NAME_INDEX, profilerContext.getOperationName().toString());
            DDPROF.setContextValue(
                RESOURCE_NAME_INDEX, profilerContext.getResourceName().toString());
          }
        }
      };

  @Override
  public Stateful newScopeState(ProfilerContext profilerContext) {
    return contextManager;
  }

  @Override
  public void onStart() {
    TSC_FREQUENCY = DDPROF.getTscFrequency();
    if (drainThread == null || !drainThread.isAlive()) {
      drainThread =
          new Thread(DatadogProfilingIntegration::drainLoop, "dd-profiling-taskblock-drain");
      drainThread.setDaemon(true);
      drainThread.start();
    }
  }

  @Override
  public void onAttach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.addThread();
    }
    NATIVE_TID.set(DDPROF.getCurrentThreadId());
  }

  @Override
  public int getCurrentThreadId() {
    Integer tid = NATIVE_TID.get();
    return tid != null ? tid : -1;
  }

  private static int getOrResolveCurrentThreadId() {
    Integer tid = NATIVE_TID.get();
    if (tid != null) {
      return tid;
    }
    int resolvedTid = DDPROF.getCurrentThreadId();
    if (resolvedTid >= 0) {
      NATIVE_TID.set(resolvedTid);
    }
    return resolvedTid;
  }

  @Override
  public long blockEnter(int state) {
    return DDPROF.blockEnter(state);
  }

  @Override
  public void blockExit(long token) {
    DDPROF.blockExit(token);
  }

  @Override
  public void blockExit(long token, long[] snapshot) {
    DDPROF.blockExit(token, snapshot);
  }

  @Override
  public void enqueueTaskBlock(
      long startTicks, long durationNanos, long blocker, long spanId, long rootSpanId) {
    enqueueTaskBlock(startTicks, durationNanos, blocker, spanId, rootSpanId, 0L, 0L, 0);
  }

  @Override
  public void enqueueTaskBlock(
      long startTicks,
      long durationNanos,
      long blocker,
      long spanId,
      long rootSpanId,
      long anchorSampleId,
      long suppressedSampleCount,
      int observedBlockingState) {
    if (spanId != 0L) {
      return;
    }
    int tid = getOrResolveCurrentThreadId();
    if (tid < 0) {
      return;
    }
    if (!TASK_BLOCK_QUEUE.offer(
        new long[] {
          tid,
          startTicks,
          durationNanos,
          blocker,
          spanId,
          rootSpanId,
          anchorSampleId,
          suppressedSampleCount,
          observedBlockingState
        })) {
      DROPPED_TASK_BLOCKS.incrementAndGet();
    }
  }

  private static void drainLoop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        long[] entry = TASK_BLOCK_QUEUE.poll(100, TimeUnit.MILLISECONDS);
        if (entry == null) {
          continue;
        }
        TaskBlockDrain.drainTaskBlockEntry(
            entry, TSC_FREQUENCY, DDPROF::recordTaskBlockFromContextEvent);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void onDetach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.removeThread();
    }
    NATIVE_TID.remove();
  }

  @Override
  public String name() {
    return "ddprof";
  }

  @Override
  public long getCurrentTicks() {
    return DDPROF.getCurrentTicks();
  }

  @Override
  public void recordTaskBlockWithContext(
      long startTicks, long blocker, long unblockingSpanId, long spanId, long rootSpanId) {
    if (spanId != 0L) {
      return;
    }
    DDPROF.recordTaskBlockWithContextEvent(
        startTicks, blocker, unblockingSpanId, spanId, rootSpanId);
  }

  @Override
  public void parkEnter() {
    DDPROF.parkEnter();
  }

  @Override
  public void parkExit(long blocker, long unblockingSpanId) {
    DDPROF.parkExit(blocker, unblockingSpanId);
  }

  public void clearContext() {
    DDPROF.clearSpanContext();
    DDPROF.clearContextValue(SPAN_NAME_INDEX);
    DDPROF.clearContextValue(RESOURCE_NAME_INDEX);
  }

  @Override
  public ProfilingContextAttribute createContextAttribute(String attribute) {
    return new DatadogProfilerContextSetter(attribute, DDPROF);
  }

  @Override
  public ProfilingScope newScope() {
    return new DatadogProfilingScope(DDPROF);
  }

  @Override
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
    if (IS_ENDPOINT_COLLECTION_ENABLED && rootSpan != null) {
      CharSequence resourceName = rootSpan.getResourceName();
      CharSequence operationName = rootSpan.getOperationName();
      if (resourceName != null && operationName != null) {
        DDPROF.recordTraceRoot(
            rootSpan.getSpanId(), resourceName.toString(), operationName.toString());
      }
    }
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return NoOpEndpointTracker.INSTANCE;
  }

  @Override
  public Timing start(TimerType type) {
    if (IS_PROFILING_QUEUEING_TIME_ENABLED && type == TimerType.QUEUEING) {
      return DDPROF.newQueueTimeTracker();
    }
    return Timing.NoOp.INSTANCE;
  }

  /**
   * This implementation is actually stateless, so we don't actually need a tracker object, but
   * we'll create a singleton to avoid returning null and risking NPEs elsewhere.
   */
  private static final class NoOpEndpointTracker implements EndpointTracker {

    public static final NoOpEndpointTracker INSTANCE = new NoOpEndpointTracker();

    @Override
    public void endpointWritten(AgentSpan span) {}
  }

  static long droppedTaskBlocks() {
    return DROPPED_TASK_BLOCKS.get();
  }
}
