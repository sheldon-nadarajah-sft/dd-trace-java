package datadog.trace.api.civisibility.android;

import javax.annotation.Nullable;

/** Immutable snapshot of the emulated Android SDK a test ran against (e.g. under Robolectric). */
public final class AndroidTestInfo {

  private final int apiLevel;
  @Nullable private final String release;
  @Nullable private final String codename;
  @Nullable private final String robolectricVersion;

  public AndroidTestInfo(
      int apiLevel,
      @Nullable String release,
      @Nullable String codename,
      @Nullable String robolectricVersion) {
    this.apiLevel = apiLevel;
    this.release = release;
    this.codename = codename;
    this.robolectricVersion = robolectricVersion;
  }

  /** The emulated Android API level (e.g. {@code 34}). */
  public int getApiLevel() {
    return apiLevel;
  }

  /** The Android release the API level maps to (e.g. {@code "14"}), or {@code null} if unknown. */
  @Nullable
  public String getRelease() {
    return release;
  }

  /** The Android release short code (e.g. {@code "U"}), or {@code null} if unknown. */
  @Nullable
  public String getCodename() {
    return codename;
  }

  /** The Robolectric version driving the test (e.g. {@code "4.16.1"}), or {@code null}. */
  @Nullable
  public String getRobolectricVersion() {
    return robolectricVersion;
  }
}
