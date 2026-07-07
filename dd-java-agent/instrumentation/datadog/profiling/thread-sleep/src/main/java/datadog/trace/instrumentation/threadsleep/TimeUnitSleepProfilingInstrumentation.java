package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Brackets {@code TimeUnit.sleep(long)} once at the bootstrap method boundary. */
@AutoService(InstrumenterModule.class)
public class TimeUnitSleepProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public TimeUnitSleepProfilingInstrumentation() {
    super("thread-sleep");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.TimeUnit"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(timeUnitSleepMethod(), getClass().getName() + "$TimeUnitSleepAdvice");
  }

  private static ElementMatcher.Junction<MethodDescription> timeUnitSleepMethod() {
    return isMethod()
        .and(not(isStatic()))
        .and(named("sleep"))
        .and(takesArgument(0, long.class))
        .and(isDeclaredBy(named("java.util.concurrent.TimeUnit")));
  }

  public static final class TimeUnitSleepAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TaskBlockHelper.State before(@Advice.Argument(0) long timeout) {
      if (timeout <= 0) {
        return null;
      }
      return TaskBlockHelper.captureForSleep();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter TaskBlockHelper.State state) {
      TaskBlockHelper.finish(state);
    }
  }
}
