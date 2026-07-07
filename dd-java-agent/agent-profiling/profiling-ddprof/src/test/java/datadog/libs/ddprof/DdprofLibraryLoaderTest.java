package datadog.libs.ddprof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DdprofLibraryLoaderTest {

  @Test
  void resolvesFourArgOverloadWhenPresent() throws Exception {
    Method m = DdprofLibraryLoader.resolveWallPrecheckGetInstance(WithWallPrecheckSupport.class);
    assertNotNull(m);
    assertArrayEquals(
        new Class<?>[] {String.class, String.class, boolean.class, boolean.class},
        m.getParameterTypes());
  }

  @Test
  void returnsNullWhenFourArgOverloadAbsent() {
    assertNull(
        DdprofLibraryLoader.resolveWallPrecheckGetInstance(WithoutWallPrecheckSupport.class));
  }

  @Test
  void returnsNullWhenFourArgOverloadIsNotPublic() {
    assertNull(
        DdprofLibraryLoader.resolveWallPrecheckGetInstance(WithNonPublicWallPrecheckSupport.class));
  }

  public static final class WithWallPrecheckSupport {
    public static Object getInstance(String a, String b, boolean c, boolean d) {
      return null;
    }
  }

  public static final class WithoutWallPrecheckSupport {
    public static Object getInstance(String a, String b) {
      return null;
    }
  }

  public static final class WithNonPublicWallPrecheckSupport {
    private static Object getInstance(String a, String b, boolean c, boolean d) {
      return null;
    }
  }
}
