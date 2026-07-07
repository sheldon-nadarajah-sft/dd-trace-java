package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_CLASS_TAG;
import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_METHODREF_TAG;
import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_NAME_AND_TYPE_TAG;
import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.THREAD_INTERNAL;

import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import net.bytebuddy.description.type.TypeDescription;

/**
 * Scans a class constant pool to determine whether a class may contain at least one direct {@code
 * Thread.sleep} call site, without visiting method bytecode or triggering the full {@code
 * COMPUTE_FRAMES} analysis.
 *
 * <p>Used by {@link ThreadSleepProfilingInstrumentation} to avoid attaching {@link
 * ThreadSleepRewritingVisitor} (and its {@code COMPUTE_FRAMES} cost) to classes that contain no
 * sleep call sites.
 *
 * <p>Fails open (returns {@code true}) on malformed bytes or I/O errors, but fails closed for
 * missing class resources. Runtime-generated classes commonly have no classpath resource, and
 * rewriting all of them is too expensive for a broad hierarchy instrumentation.
 */
public final class ThreadSleepScanner {

  private static final String SLEEP_NAME = "sleep";

  private ThreadSleepScanner() {}

  /**
   * Returns {@code true} if the class identified by {@code typeDescription} may contain at least
   * one direct {@code Thread.sleep} call site, {@code false} if it provably does not.
   */
  public static boolean containsThreadSleepCallSite(
      ClassLoader classLoader, TypeDescription typeDescription) {
    if (classLoader == null) {
      // Bootstrap classloader - type matcher already excludes java.* and bytes are unavailable.
      return false;
    }
    String resource = typeDescription.getInternalName() + ".class";
    try (InputStream is = classLoader.getResourceAsStream(resource)) {
      if (is == null) {
        return false;
      }
      return scan(readAllBytes(is));
    } catch (Exception e) {
      return true;
    }
  }

  /** Package-private for unit testing. */
  static boolean scan(byte[] classBytes) {
    try {
      return containsThreadSleepMethodRef(new ConstantPool(classBytes));
    } catch (Exception e) {
      return true;
    }
  }

  private static boolean containsThreadSleepMethodRef(ConstantPool cp) {
    int count = cp.getCount();
    for (int i = 1; i < count; i++) {
      if (cp.getType(i) != CONSTANT_METHODREF_TAG) {
        continue;
      }
      int methodOffset = cp.getOffset(i);
      int classIndex = cp.readUnsignedShort(methodOffset);
      int nameAndTypeIndex = cp.readUnsignedShort(methodOffset + 2);
      if (cp.getType(classIndex) != CONSTANT_CLASS_TAG
          || cp.getType(nameAndTypeIndex) != CONSTANT_NAME_AND_TYPE_TAG) {
        continue;
      }
      String owner = cp.readUTF8(cp.getOffset(cp.readUnsignedShort(cp.getOffset(classIndex))));
      int nameAndTypeOffset = cp.getOffset(nameAndTypeIndex);
      String name = cp.readUTF8(cp.getOffset(cp.readUnsignedShort(nameAndTypeOffset)));
      String descriptor = cp.readUTF8(cp.getOffset(cp.readUnsignedShort(nameAndTypeOffset + 2)));
      if (THREAD_INTERNAL.equals(owner)
          && SLEEP_NAME.equals(name)
          && isThreadSleepDescriptor(descriptor)) {
        return true;
      }
    }
    return false;
  }

  private static byte[] readAllBytes(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static boolean isThreadSleepDescriptor(String descriptor) {
    return ThreadSleepCallSiteMethodVisitor.SLEEP_J_DESC.equals(descriptor)
        || ThreadSleepCallSiteMethodVisitor.SLEEP_JI_DESC.equals(descriptor)
        || ThreadSleepCallSiteMethodVisitor.SLEEP_DURATION_DESC.equals(descriptor);
  }
}
