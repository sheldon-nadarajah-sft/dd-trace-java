package datadog.trace.api.civisibility.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AndroidTestContextTest {

  @AfterEach
  void reset() {
    AndroidTestContext.clear();
  }

  @Test
  void getAndClearReturnsTheStoredInfoOnce() {
    AndroidTestInfo info = new AndroidTestInfo(34, "14", "U", "4.16.1");
    AndroidTestContext.set(info);

    AndroidTestInfo drained = AndroidTestContext.getAndClear();
    assertNotNull(drained);
    assertEquals(info, drained);
    assertEquals(34, drained.getApiLevel());
    assertEquals("14", drained.getRelease());
    assertEquals("U", drained.getCodename());
    assertEquals("4.16.1", drained.getRobolectricVersion());

    // A second drain returns nothing: the value is consumed.
    assertNull(AndroidTestContext.getAndClear());
  }

  @Test
  void getAndClearReturnsNullWhenNothingRecorded() {
    assertNull(AndroidTestContext.getAndClear());
  }

  @Test
  void clearRemovesTheStoredInfo() {
    AndroidTestContext.set(new AndroidTestInfo(30, null, null, null));
    AndroidTestContext.clear();
    assertNull(AndroidTestContext.getAndClear());
  }
}
