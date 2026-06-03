package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Bracket {@code Thread.sleep} call sites at class-load time so a {@code datadog.TaskBlock} JFR
 * event covers the sleep interval.
 *
 * <p>Why caller-site rewriting rather than {@code @Advice} on {@code Thread.sleep} itself: {@code
 * Thread.sleep} is native ({@code sleep0}) on all JDK versions and is never exposed as {@code
 * Object.wait()}, so JVMTI {@code MonitorWait}/{@code Waited} callbacks do not fire for it on any
 * JDK. The native wallprecheck OS-thread-state filter can suppress {@code SIGVTALRM} for sleeping
 * threads (when {@code wallprecheck=true}), but it does not emit a {@code datadog.TaskBlock} event.
 * Caller-site rewriting is the Java-level way to bracket direct {@code Thread.sleep} call sites
 * with a TaskBlock interval.
 *
 * <p>Coverage is purely opt-in by the user's bytecode: any supported direct {@code
 * Thread.sleep(...)} call site in a non-JDK class is wrapped. {@code TimeUnit.sleep(long)} is
 * instrumented separately at the bootstrap method boundary. Reflection-driven sleeps and JNI-driven
 * sleeps remain uncovered (intentional: out-of-band call paths).
 *
 * <p>Active on every JDK when enabled via {@code profiling.ddprof.wall.precheck=true} (opt-in;
 * default is off). The native JVMTI monitor callbacks cover {@code Object.wait()} and synchronized
 * contention but not {@code Thread.sleep}, so sleep coverage is provided exclusively by this
 * call-site instrumentation. Platform-thread sleeps are queued for async native emission only when
 * the sleeping thread is untraced, and arm native blocked-run state for wallprecheck suppression.
 * Virtual thread sleeps bypass the platform-thread queue and native carrier-thread blocked state,
 * and pass explicit zero span/root context directly.
 *
 * <p><b>Performance note:</b> matched classes are scanned cheaply first. The {@link
 * net.bytebuddy.asm.AsmVisitorWrapper} and its {@code COMPUTE_FRAMES} cost are only attached when a
 * supported sleep call site is found, or when scanning fails open.
 */
@AutoService(InstrumenterModule.class)
public class ThreadSleepProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  private static final ElementMatcher.Junction<ClassLoader> NON_BOOTSTRAP_CLASS_LOADER =
      new ElementMatcher.Junction.ForNonNullValues<ClassLoader>() {
        @Override
        protected boolean doMatch(ClassLoader target) {
          return true;
        }
      };

  public ThreadSleepProfilingInstrumentation() {
    super("thread-sleep");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && Config.get().isDatadogProfilerEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DATADOG_PROFILER_WALL_PRECHECK,
                PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT);
  }

  @Override
  public String hierarchyMarkerType() {
    // null = no specific marker type; match broadly across all user-loaded classes.
    return null;
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Direct Thread.sleep call sites can appear under any application/framework loader, but the
    // scanner cannot inspect bootstrap/null classloader bytes and already fails closed there.
    return NON_BOOTSTRAP_CLASS_LOADER;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match every loaded class - sleep call sites can appear anywhere. The per-method
    // visitor is a pass-through for methods without supported sleep calls, so cost of
    // inspecting irrelevant classes is bounded.
    //
    // JDK / agent / bytebuddy internals are excluded to avoid bootstrap re-entry and
    // self-instrumentation; in particular Thread.sleep's own callers inside java.lang.* would
    // create a class-load loop because TaskBlockHelper itself sits in agent-bootstrap.
    //
    // JDK-generated dynamic proxy classes do not contain Thread.sleep INVOKESTATIC call sites, so
    // retaining jdk.proxy* would add class-load overhead with zero coverage benefit.
    return not(
        nameStartsWith("java.")
            .or(nameStartsWith("javax."))
            .or(nameStartsWith("jdk."))
            .or(nameStartsWith("sun."))
            .or(nameStartsWith("com.sun."))
            .or(nameStartsWith("datadog."))
            .or(nameStartsWith("net.bytebuddy.")));
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(
        (builder, typeDescription, classLoader, module, pd) ->
            ThreadSleepScanner.containsThreadSleepCallSite(classLoader, typeDescription)
                ? builder.visit(new ThreadSleepRewritingVisitor())
                : builder);
  }
}
