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
 * set when the JUnit test-start event fires, and is torn down before the finish event).
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
      RobolectricTestAnnotator.annotate();
    }
  }
}
