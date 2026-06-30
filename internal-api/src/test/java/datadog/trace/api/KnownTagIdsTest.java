package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity test for the keyOf substrate (slice 1): the {@link KnownTagIds} registry + the {@link
 * KnownTags.Resolver} it registers. Verifies name &harr; id resolution without any dense store —
 * {@code keyOf}/{@code nameOf} depend only on globalSerial + name, not on the (dormant) positional
 * layout.
 */
class KnownTagIdsTest {

  /** (name, id) pairs — the full registry. keyOf returns the id verbatim (incl. INTERCEPTED). */
  static Stream<Arguments> knownTags() {
    return Stream.of(
        Arguments.of(Tags.ERROR, KnownTagIds.ERROR),
        Arguments.of(DDTags.PARENT_ID, KnownTagIds.PARENT_ID),
        Arguments.of(DDTags.BASE_SERVICE, KnownTagIds.BASE_SERVICE),
        Arguments.of(Tags.VERSION, KnownTagIds.VERSION),
        Arguments.of(KnownTagIds.ENV, KnownTagIds.ENV_ID),
        Arguments.of(DDTags.DJM_ENABLED, KnownTagIds.DJM_ENABLED),
        Arguments.of(DDTags.DSM_ENABLED, KnownTagIds.DSM_ENABLED),
        Arguments.of(DDTags.TRACER_HOST, KnownTagIds.TRACER_HOST_ID),
        Arguments.of(DDTags.DD_INTEGRATION, KnownTagIds.INTEGRATION_ID),
        Arguments.of(DDTags.DD_SVC_SRC, KnownTagIds.SVC_SRC_ID),
        Arguments.of(Tags.PEER_SERVICE, KnownTagIds.PEER_SERVICE),
        Arguments.of(DDTags.PEER_SERVICE_REMAPPED_FROM, KnownTagIds.PEER_SERVICE_REMAPPED_FROM),
        Arguments.of(Tags.HTTP_METHOD, KnownTagIds.HTTP_METHOD),
        Arguments.of(Tags.HTTP_ROUTE, KnownTagIds.HTTP_ROUTE),
        Arguments.of(Tags.HTTP_URL, KnownTagIds.HTTP_URL),
        Arguments.of(Tags.PEER_HOSTNAME, KnownTagIds.PEER_HOSTNAME),
        Arguments.of(Tags.PEER_HOST_IPV4, KnownTagIds.PEER_HOST_IPV4),
        Arguments.of(Tags.PEER_HOST_IPV6, KnownTagIds.PEER_HOST_IPV6),
        Arguments.of(Tags.PEER_PORT, KnownTagIds.PEER_PORT),
        Arguments.of(Tags.COMPONENT, KnownTagIds.COMPONENT),
        Arguments.of(Tags.SPAN_KIND, KnownTagIds.SPAN_KIND),
        Arguments.of(DDTags.LANGUAGE_TAG_KEY, KnownTagIds.LANGUAGE),
        Arguments.of(Tags.DB_TYPE, KnownTagIds.DB_TYPE),
        Arguments.of(Tags.DB_INSTANCE, KnownTagIds.DB_INSTANCE),
        Arguments.of(Tags.DB_USER, KnownTagIds.DB_USER),
        Arguments.of(Tags.DB_OPERATION, KnownTagIds.DB_OPERATION),
        Arguments.of(Tags.DB_POOL_NAME, KnownTagIds.DB_POOL_NAME));
  }

  /**
   * The subset flagged INTERCEPTED (sign bit) — must agree with the interceptor's needsIntercept.
   */
  static Stream<Arguments> interceptedTags() {
    return Stream.of(
        Arguments.of(KnownTagIds.ERROR),
        Arguments.of(KnownTagIds.PEER_SERVICE),
        Arguments.of(KnownTagIds.HTTP_METHOD),
        Arguments.of(KnownTagIds.HTTP_URL),
        Arguments.of(KnownTagIds.SPAN_KIND));
  }

  @Test
  void resolverIsActiveOnceReferenced() {
    // referencing any constant triggers KnownTagIds.<clinit> -> KnownTags.register
    assertTrue(KnownTagIds.ERROR != 0L);
    assertTrue(KnownTags.isActive());
    assertEquals(KnownTagIds.SLOT_COUNT, KnownTags.slotCount());
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void keyOfResolvesNameToId(String name, long id) {
    assertEquals(id, KnownTags.keyOf(name), "keyOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void nameOfResolvesIdToName(String name, long id) {
    assertEquals(name, KnownTags.nameOf(id), "nameOf(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("knownTags")
  void nameHashMatchesEntryHash(String name, long id) {
    assertEquals((int) TagMap.Entry._hash(name), KnownTags.nameHash(id), "nameHash(" + name + ")");
  }

  @ParameterizedTest
  @MethodSource("interceptedTags")
  void interceptedTagsCarryFlag(long id) {
    assertTrue(KnownTags.isIntercepted(id), "isIntercepted");
  }

  @Test
  void nonInterceptedTagsDoNotCarryFlag() {
    Set<Long> intercepted = new HashSet<>();
    interceptedTags().forEach(a -> intercepted.add((Long) a.get()[0]));
    knownTags()
        .forEach(
            a -> {
              long id = (Long) a.get()[1];
              if (!intercepted.contains(id)) {
                assertFalse(KnownTags.isIntercepted(id), "not intercepted: " + a.get()[0]);
              }
            });
  }

  @Test
  void unknownNamesResolveToZero() {
    assertEquals(0L, KnownTags.keyOf("definitely.not.a.known.tag"));
    assertEquals(0L, KnownTags.keyOf("http.statuscode")); // close-but-not-listed
    assertEquals(0L, KnownTags.keyOf(""));
  }

  @Test
  void unknownIdsResolveToNullName() {
    assertNull(KnownTags.nameOf(0L));
    assertNull(KnownTags.nameOf(KnownTags.tagId(9999, "made.up")));
  }

  @Test
  void errorIsReservedTheRestAreStored() {
    assertTrue(KnownTags.isReserved(KnownTagIds.ERROR), "ERROR reserved");
    assertFalse(KnownTags.isStored(KnownTagIds.ERROR), "ERROR not stored");
    knownTags()
        .forEach(
            a -> {
              long id = (Long) a.get()[1];
              if (id != KnownTagIds.ERROR) {
                assertTrue(KnownTags.isStored(id), "stored: " + a.get()[0]);
                assertFalse(KnownTags.isReserved(id), "not reserved: " + a.get()[0]);
              }
            });
  }

  @Test
  void globalSerialsAreUnique() {
    List<Long> serials = new ArrayList<>();
    knownTags().forEach(a -> serials.add((long) KnownTags.globalSerial((Long) a.get()[1])));
    assertEquals(serials.size(), new HashSet<>(serials).size(), "globalSerials must be unique");
  }
}
