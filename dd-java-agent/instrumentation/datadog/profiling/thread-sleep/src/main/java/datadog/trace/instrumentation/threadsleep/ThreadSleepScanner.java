package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.instrumentation.threadsleep.ThreadSleepCallSiteMethodVisitor.THREAD_INTERNAL;

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

  private static final int CLASSFILE_MAGIC = 0xCAFEBABE;

  private static final int CONSTANT_UTF8 = 1;
  private static final int CONSTANT_INTEGER = 3;
  private static final int CONSTANT_FLOAT = 4;
  private static final int CONSTANT_LONG = 5;
  private static final int CONSTANT_DOUBLE = 6;
  private static final int CONSTANT_CLASS = 7;
  private static final int CONSTANT_STRING = 8;
  private static final int CONSTANT_FIELDREF = 9;
  private static final int CONSTANT_METHODREF = 10;
  private static final int CONSTANT_INTERFACE_METHODREF = 11;
  private static final int CONSTANT_NAME_AND_TYPE = 12;
  private static final int CONSTANT_METHOD_HANDLE = 15;
  private static final int CONSTANT_METHOD_TYPE = 16;
  private static final int CONSTANT_DYNAMIC = 17;
  private static final int CONSTANT_INVOKE_DYNAMIC = 18;
  private static final int CONSTANT_MODULE = 19;
  private static final int CONSTANT_PACKAGE = 20;

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
      return new ConstantPoolScanner(classBytes).containsThreadSleepMethodRef();
    } catch (Exception e) {
      return true;
    }
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

  private static final class ConstantPoolScanner {
    private final byte[] bytes;
    private int offset;

    ConstantPoolScanner(byte[] bytes) {
      this.bytes = bytes;
    }

    boolean containsThreadSleepMethodRef() {
      if (readU4() != CLASSFILE_MAGIC) {
        throw new IllegalArgumentException("not a class file");
      }
      skip(4); // minor_version, major_version

      int constantPoolCount = readU2();
      String[] utf8 = new String[constantPoolCount];
      int[] classNameIndex = new int[constantPoolCount];
      int[] nameAndTypeNameIndex = new int[constantPoolCount];
      int[] nameAndTypeDescriptorIndex = new int[constantPoolCount];
      int[] methodClassIndex = new int[constantPoolCount];
      int[] methodNameAndTypeIndex = new int[constantPoolCount];

      for (int i = 1; i < constantPoolCount; i++) {
        int tag = readU1();
        switch (tag) {
          case CONSTANT_UTF8:
            utf8[i] = readUtf8(readU2());
            break;
          case CONSTANT_INTEGER:
          case CONSTANT_FLOAT:
            skip(4);
            break;
          case CONSTANT_LONG:
          case CONSTANT_DOUBLE:
            skip(8);
            i++;
            break;
          case CONSTANT_CLASS:
            classNameIndex[i] = readU2();
            break;
          case CONSTANT_STRING:
          case CONSTANT_METHOD_TYPE:
          case CONSTANT_MODULE:
          case CONSTANT_PACKAGE:
            skip(2);
            break;
          case CONSTANT_FIELDREF:
          case CONSTANT_INTERFACE_METHODREF:
            skip(4);
            break;
          case CONSTANT_METHODREF:
            methodClassIndex[i] = readU2();
            methodNameAndTypeIndex[i] = readU2();
            break;
          case CONSTANT_NAME_AND_TYPE:
            nameAndTypeNameIndex[i] = readU2();
            nameAndTypeDescriptorIndex[i] = readU2();
            break;
          case CONSTANT_METHOD_HANDLE:
            skip(3);
            break;
          case CONSTANT_DYNAMIC:
          case CONSTANT_INVOKE_DYNAMIC:
            skip(4);
            break;
          default:
            throw new IllegalArgumentException("unknown constant-pool tag " + tag);
        }
      }

      for (int i = 1; i < constantPoolCount; i++) {
        int methodClass = methodClassIndex[i];
        if (methodClass == 0) {
          continue;
        }
        int methodNameAndType = methodNameAndTypeIndex[i];
        String owner = utf8[classNameIndex[methodClass]];
        String name = utf8[nameAndTypeNameIndex[methodNameAndType]];
        String descriptor = utf8[nameAndTypeDescriptorIndex[methodNameAndType]];
        if (THREAD_INTERNAL.equals(owner)
            && SLEEP_NAME.equals(name)
            && isThreadSleepDescriptor(descriptor)) {
          return true;
        }
      }
      return false;
    }

    private int readU1() {
      require(1);
      return bytes[offset++] & 0xFF;
    }

    private int readU2() {
      require(2);
      int value = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
      offset += 2;
      return value;
    }

    private int readU4() {
      require(4);
      int value =
          ((bytes[offset] & 0xFF) << 24)
              | ((bytes[offset + 1] & 0xFF) << 16)
              | ((bytes[offset + 2] & 0xFF) << 8)
              | (bytes[offset + 3] & 0xFF);
      offset += 4;
      return value;
    }

    private String readUtf8(int length) {
      require(length);
      String value;
      try {
        value = new String(bytes, offset, length, "UTF-8");
      } catch (Exception e) {
        throw new IllegalArgumentException(e);
      }
      offset += length;
      return value;
    }

    private void skip(int length) {
      require(length);
      offset += length;
    }

    private void require(int length) {
      if (offset + length > bytes.length) {
        throw new IllegalArgumentException("truncated class file");
      }
    }
  }
}
