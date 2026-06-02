package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/** Helper for Java-level instrumentation that emits {@code datadog.TaskBlock} intervals. */
public final class TaskBlockHelper {
  static final long MIN_TASK_BLOCK_NANOS = 1_000_000L;
  private static final ThreadLocal<long[]> TASK_BLOCK_SUPPRESSION_SNAPSHOT =
      new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
          return new long[ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_SNAPSHOT_SIZE];
        }
      };

  private TaskBlockHelper() {}

  /** Captured state for a potential blocking interval. */
  public static final class State {
    final ProfilingContextIntegration profiling;
    final long startTicks;
    final long startNanos;
    final long blocker;
    final boolean isVirtual;
    final boolean deferred;
    final long blockToken;
    final long spanId;
    final long rootSpanId;

    State(
        final ProfilingContextIntegration profiling,
        final long startTicks,
        final long startNanos,
        final long blocker) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = false;
      this.deferred = false;
      this.blockToken = 0L;
      this.spanId = 0L;
      this.rootSpanId = 0L;
    }

    State(
        final ProfilingContextIntegration profiling,
        final long startTicks,
        final long startNanos,
        final long blocker,
        final boolean deferred,
        final long spanId,
        final long rootSpanId,
        final long blockToken) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = false;
      this.deferred = deferred;
      this.blockToken = blockToken;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }

    State(
        final ProfilingContextIntegration profiling,
        final long startTicks,
        final long startNanos,
        final long blocker,
        final long spanId,
        final long rootSpanId) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = true;
      this.deferred = false;
      this.blockToken = 0L;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static State capture(final long blocker) {
    return capture(
        blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan(), false);
  }

  public static State captureForSleep() {
    return captureSafely(0L, true);
  }

  static State captureSafely(final long blocker) {
    return captureSafely(blocker, false);
  }

  static State captureSafely(final long blocker, final boolean deferred) {
    try {
      return capture(
          blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan(), deferred);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State captureSafely(
      final long blocker, final ProfilingContextIntegration profiling, final AgentSpan span) {
    try {
      return capture(blocker, profiling, span, false);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State capture(
      final long blocker, final ProfilingContextIntegration profiling, final AgentSpan span) {
    return capture(blocker, profiling, span, false);
  }

  static State capture(
      final long blocker,
      final ProfilingContextIntegration profiling,
      final AgentSpan span,
      final boolean deferred) {
    if (profiling == null) {
      return null;
    }
    ProfilerContext context = ProfilerContexts.of(span);
    if (context == null) {
      return null;
    }
    long startTicks = profiling.getCurrentTicks();
    long startNanos = System.nanoTime();
    if (VirtualThreads.isCurrent()) {
      return new State(
          profiling, startTicks, startNanos, blocker, context.getSpanId(), context.getRootSpanId());
    }
    if (deferred) {
      long blockToken = profiling.blockEnter(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING);
      return new State(
          profiling,
          startTicks,
          startNanos,
          blocker,
          true,
          context.getSpanId(),
          context.getRootSpanId(),
          blockToken);
    }
    return new State(profiling, startTicks, startNanos, blocker);
  }

  public static void finish(final State state) {
    if (state == null) {
      return;
    }
    boolean blockExited = false;
    try {
      if (System.nanoTime() - state.startNanos < MIN_TASK_BLOCK_NANOS) {
        return;
      }
      if (state.deferred) {
        if (state.spanId == 0L) {
          return;
        }
        long durationNanos = System.nanoTime() - state.startNanos;
        long[] suppressionSnapshot = resetSuppressionSnapshot();
        if (state.blockToken != 0L) {
          state.profiling.blockExit(state.blockToken, suppressionSnapshot);
          blockExited = true;
        }
        state.profiling.enqueueTaskBlock(
            state.startTicks,
            durationNanos,
            state.blocker,
            state.spanId,
            state.rootSpanId,
            suppressionSnapshot[
                ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_ANCHOR_SAMPLE_ID],
            suppressionSnapshot[
                ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_SUPPRESSED_COUNT],
            (int)
                suppressionSnapshot[
                    ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_OBSERVED_STATE]);
      } else if (state.isVirtual) {
        state.profiling.recordTaskBlockWithContext(
            state.startTicks, state.blocker, 0L, state.spanId, state.rootSpanId);
      } else {
        state.profiling.recordTaskBlock(state.startTicks, state.blocker, 0L);
      }
    } catch (Throwable ignored) {
    } finally {
      if (!blockExited && state.blockToken != 0L) {
        try {
          state.profiling.blockExit(state.blockToken);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static long[] resetSuppressionSnapshot() {
    long[] snapshot = TASK_BLOCK_SUPPRESSION_SNAPSHOT.get();
    snapshot[ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_ANCHOR_SAMPLE_ID] = 0L;
    snapshot[ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_SUPPRESSED_COUNT] = 0L;
    snapshot[ProfilingContextIntegration.TASK_BLOCK_SUPPRESSION_OBSERVED_STATE] = 0L;
    return snapshot;
  }
}
