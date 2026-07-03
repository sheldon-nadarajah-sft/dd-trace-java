package datadog.trace.instrumentation.robolectric;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Captures the emulated Android SDK for tests running under Robolectric.
 *
 * <p>Robolectric establishes the emulated SDK in {@code TestEnvironment#setUpApplicationState},
 * which runs on the per-SDK sandbox "main" thread right before the test body (the SDK is not yet
 * set when the JUnit test-start event fires, and is torn down before the finish event). This
 * instrumentation reads the SDK there and stashes it in {@link
 * datadog.trace.api.civisibility.android.AndroidTestContext}; the CI Visibility core drains it and
 * attaches the {@code test.android.*} tags to the test span (see {@code
 * TestEventsHandlerImpl#onTestFinish}).
 *
 * <p>Robolectric tests are JUnit tests, so their spans are still produced by the JUnit
 * instrumentation — this only enriches them with the Android metadata, and only loads when
 * Robolectric is on the classpath.
 */
@AutoService(InstrumenterModule.class)
public class RobolectricInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RobolectricInstrumentation() {
    super("ci-visibility", "robolectric");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.robolectric.internal.TestEnvironment";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RobolectricTestExtractor"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setUpApplicationState"), getClass().getName() + "$SetUpApplicationStateAdvice");
  }

  public static class SetUpApplicationStateAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      RobolectricTestExtractor.capture();
    }
  }
}
