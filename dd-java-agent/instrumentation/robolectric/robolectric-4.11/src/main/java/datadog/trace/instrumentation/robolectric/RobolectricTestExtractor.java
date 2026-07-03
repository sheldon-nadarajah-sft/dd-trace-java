package datadog.trace.instrumentation.robolectric;

import datadog.trace.api.civisibility.android.AndroidTestContext;
import datadog.trace.api.civisibility.android.AndroidTestInfo;
import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.versioning.AndroidVersions;

/**
 * Reads the emulated Android SDK and Robolectric version (once the sandbox has established the SDK)
 * and hands them to the CI Visibility core via {@link AndroidTestContext}.
 */
public final class RobolectricTestExtractor {

  /** Matches the version in a {@code robolectric-<version>.jar} file name. */
  private static final Pattern ROBOLECTRIC_JAR = Pattern.compile("^robolectric-(.+)\\.jar$");

  private RobolectricTestExtractor() {}

  public static void capture() {
    int apiLevel = RuntimeEnvironment.getApiLevel();
    if (apiLevel <= 0) {
      return;
    }
    String release = null;
    String codename = null;
    AndroidVersions.AndroidRelease androidRelease = AndroidVersions.getReleaseForSdkInt(apiLevel);
    if (androidRelease != null) {
      release = androidRelease.getVersion();
      codename = androidRelease.getShortCode();
    }
    AndroidTestContext.set(new AndroidTestInfo(apiLevel, release, codename, robolectricVersion()));
  }

  private static String robolectricVersion() {
    try {
      // RuntimeEnvironment is re-loaded by the sandbox classloader with no CodeSource, but the
      // runner runs outside the sandbox (it creates it), so it is delegated to the application
      // classloader and its CodeSource points at the real robolectric-<version>.jar.
      ProtectionDomain protectionDomain = RobolectricTestRunner.class.getProtectionDomain();
      CodeSource codeSource = protectionDomain != null ? protectionDomain.getCodeSource() : null;
      URL location = codeSource != null ? codeSource.getLocation() : null;
      if (location == null) {
        return null;
      }
      Matcher matcher = ROBOLECTRIC_JAR.matcher(new File(location.getPath()).getName());
      return matcher.matches() ? matcher.group(1) : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
