package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.*;

public interface ProfilingContextIntegration extends Profiling, EndpointCheckpointer, Timer {
  /** Native {@code OSThreadState::SLEEPING}; used for Thread.sleep precheck state. */
  int BLOCKING_STATE_SLEEPING = 7;

  int TASK_BLOCK_SUPPRESSION_SNAPSHOT_SIZE = 3;
  int TASK_BLOCK_SUPPRESSION_ANCHOR_SAMPLE_ID = 0;
  int TASK_BLOCK_SUPPRESSION_SUPPRESSED_COUNT = 1;
  int TASK_BLOCK_SUPPRESSION_OBSERVED_STATE = 2;

  /**
   * invoked when the profiler is started, implementations must not initialise JFR before this is
   * called.
   */
  default void onStart() {}

  /** Invoked when a trace first propagates to a thread */
  default void onAttach() {}

  /** Invoked when a thread exits */
  default void onDetach() {}

  default Stateful newScopeState(ProfilerContext profilerContext) {
    return Stateful.DEFAULT;
  }

  default int encode(CharSequence constant) {
    return 0;
  }

  default int encodeOperationName(CharSequence constant) {
    return 0;
  }

  default int encodeResourceName(CharSequence constant) {
    return 0;
  }

  /** Returns the current TSC tick count for the calling thread. */
  default long getCurrentTicks() {
    return 0L;
  }

  /**
   * Emits a TaskBlock event for virtual threads.
   *
   * <p>Virtual threads are multiplexed on OS carrier threads; the native OTEP TLS sidecar is
   * carrier-scoped and cannot be trusted between capture (block entry) and emit (block exit). Java
   * call sites that detect a virtual thread must capture span/root ids at block entry and pass them
   * here explicitly so the native deferred-capture path can use them instead of the TLS sidecar.
   * This virtual-thread path intentionally carries span/root ids only, not custom profiling context
   * attributes.
   *
   * @param startTicks TSC tick captured at block entry
   * @param blocker identity hash code of the blocking object, or 0 if none
   * @param unblockingSpanId the span ID of the thread that unblocked this thread, or 0 if unknown
   * @param spanId span ID captured at block-entry time
   * @param rootSpanId root span ID captured at block-entry time
   */
  default void recordTaskBlockWithContext(
      long startTicks, long blocker, long unblockingSpanId, long spanId, long rootSpanId) {}

  /**
   * Returns the OS-level native thread ID for the calling thread, or {@code -1} if unavailable.
   * Implementations may pre-cache this value in thread-local storage on {@link #onAttach()} to
   * avoid repeated JNI round-trips on the hot path.
   */
  default int getCurrentThreadId() {
    return -1;
  }

  /**
   * Marks the current platform thread as entering an untraced blocking interval that may be used by
   * the native wall-clock timer to skip later signals after the first MethodSample in the run.
   *
   * @return an opaque token to pass to {@link #blockExit(long)}, or {@code 0} when no native state
   *     was armed
   */
  default long blockEnter(int state) {
    return 0L;
  }

  /** Clears a native blocked interval previously armed by {@link #blockEnter(int)}. */
  default void blockExit(long token) {}

  /**
   * Clears a native blocked interval and stores reconstruction fields into {@code snapshot}.
   *
   * <p>The snapshot layout is {@link #TASK_BLOCK_SUPPRESSION_ANCHOR_SAMPLE_ID}, {@link
   * #TASK_BLOCK_SUPPRESSION_SUPPRESSED_COUNT}, and {@link #TASK_BLOCK_SUPPRESSION_OBSERVED_STATE}.
   */
  default void blockExit(long token, long[] snapshot) {
    blockExit(token);
  }

  /**
   * Enqueues a TaskBlock interval for asynchronous recording off the critical request path. The
   * actual JFR write is performed by a background drain thread; the calling thread only pays the
   * cost of a non-blocking queue offer.
   *
   * <p>Called from the {@code Thread.sleep} instrumentation finish path for platform threads. Other
   * paths use the synchronous {@link #recordTaskBlock} / {@link #recordTaskBlockWithContext}
   * methods instead.
   *
   * @param startTicks TSC tick captured at sleep entry
   * @param durationNanos wall-clock duration of the sleep in nanoseconds
   * @param blocker identity hash of the blocking object, or 0 for sleeps
   * @param spanId span ID captured at sleep entry; native TaskBlock eligibility currently accepts
   *     only zero
   * @param rootSpanId root span ID captured at sleep entry; zero for accepted events
   */
  default void enqueueTaskBlock(
      long startTicks, long durationNanos, long blocker, long spanId, long rootSpanId) {}

  /**
   * Enqueues a TaskBlock with MethodSample reconstruction metadata captured from native suppression
   * state.
   */
  default void enqueueTaskBlock(
      long startTicks,
      long durationNanos,
      long blocker,
      long spanId,
      long rootSpanId,
      long anchorSampleId,
      long suppressedSampleCount,
      int observedBlockingState) {
    enqueueTaskBlock(startTicks, durationNanos, blocker, spanId, rootSpanId);
  }

  /**
   * Called when the current thread is about to enter {@code LockSupport.park*}. The native profiler
   * snapshots the OTEP TLS context, records the start tick for {@code datadog.TaskBlock} emission
   * on unpark when the context is zero, and arms native blocked-run state for wall-clock pre-send
   * suppression after the first MethodSample in the park run. When {@code wallprecheck} is disabled
   * (the default), wall-clock signals are still delivered to parked threads.
   */
  default void parkEnter() {}

  /**
   * Called when the current thread has returned from {@code LockSupport.park*}. Clears the park
   * state and may emit a TaskBlock JFR event.
   */
  default void parkExit(long blocker, long unblockingSpanId) {}

  String name();

  final class NoOp implements ProfilingContextIntegration {

    public static final ProfilingContextIntegration INSTANCE =
        new ProfilingContextIntegration.NoOp();

    @Override
    public ProfilingContextAttribute createContextAttribute(String attribute) {
      return ProfilingContextAttribute.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }

    @Override
    public void onAttach() {}

    @Override
    public void onDetach() {}

    @Override
    public String name() {
      return "none";
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }
}
