package datadog.trace.api.civisibility.android;

import javax.annotation.Nullable;

/**
 * Thread-local hand-off for {@link AndroidTestInfo} between the Robolectric instrumentation and the
 * CI Visibility core.
 *
 * <p>Robolectric runs each test on a per-SDK sandbox "main" thread, and the emulated SDK is only
 * established while the test body executes. The Robolectric instrumentation captures the SDK on
 * that sandbox thread (via {@link #set}) while it is available, and the core drains it (via {@link
 * #getAndClear}) on the same thread when the test finishes.
 */
public abstract class AndroidTestContext {

  private static final ThreadLocal<AndroidTestInfo> CURRENT = new ThreadLocal<>();

  private AndroidTestContext() {}

  /** Records the emulated Android environment for the test executing on the current thread. */
  public static void set(AndroidTestInfo info) {
    CURRENT.set(info);
  }

  /**
   * Returns and clears the info recorded for the current thread, or {@code null} if none (the
   * common, non-Robolectric case).
   */
  @Nullable
  public static AndroidTestInfo getAndClear() {
    AndroidTestInfo info = CURRENT.get();
    if (info != null) {
      CURRENT.remove();
    }
    return info;
  }

  /** Clears any info recorded for the current thread. */
  public static void clear() {
    CURRENT.remove();
  }
}
