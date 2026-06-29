package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Read-through support, slice 1 (read path): a child {@link OptimizedTagMap} with a frozen parent
 * reads through to the parent on a local miss, while local entries shadow the parent (local-wins).
 * Removal/tombstones and bulk (iteration/serialize) union come in later slices.
 */
class TagMapReadThroughTest {

  private static OptimizedTagMap frozenParent() {
    OptimizedTagMap parent = (OptimizedTagMap) TagMap.create();
    parent.set("a", "parent-a");
    parent.set("b", "parent-b");
    parent.freeze();
    return parent;
  }

  @Test
  void readsThroughToParentOnMiss() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());

    assertEquals("parent-a", child.getString("a")); // miss locally -> read through
    assertEquals("parent-b", child.getString("b"));
    assertEquals("child-c", child.getString("c")); // local
    assertNull(child.getString("missing"));
    assertTrue(child.containsKey("a"));
    assertFalse(child.containsKey("missing"));
  }

  @Test
  void localEntryShadowsParent() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // same key as parent
    child.withParent(frozenParent());

    assertEquals("child-b", child.getString("b")); // local wins
    assertEquals("parent-a", child.getString("a")); // parent still visible
  }

  @Test
  void estimateSizeIsUpperBound() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");
    child.withParent(frozenParent());

    // true union = {a, b, c} = 3; estimate over-counts the shadowed "b": local 2 + parent 2 = 4
    assertEquals(4, child.estimateSize());
    assertTrue(child.estimateSize() >= 3, "estimateSize must be an upper bound on the true size");
  }

  @Test
  void emptinessSemantics() {
    OptimizedTagMap emptyOverEmpty = (OptimizedTagMap) TagMap.create();
    emptyOverEmpty.withParent((OptimizedTagMap) TagMap.create().freeze());
    assertTrue(emptyOverEmpty.isEmpty());
    assertTrue(emptyOverEmpty.isDefinitelyEmpty());

    OptimizedTagMap emptyOverNonEmpty = (OptimizedTagMap) TagMap.create();
    emptyOverNonEmpty.withParent(frozenParent());
    assertFalse(emptyOverNonEmpty.isEmpty(), "a non-empty parent makes the map non-empty");
    assertFalse(emptyOverNonEmpty.isDefinitelyEmpty());

    assertTrue(((OptimizedTagMap) TagMap.create()).isDefinitelyEmpty());
  }

  @Test
  void parentMustBeFrozen() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    OptimizedTagMap mutableParent = (OptimizedTagMap) TagMap.create();
    assertThrows(IllegalStateException.class, () -> child.withParent(mutableParent));
  }

  // --- slice 2: removal / tombstones ---

  @Test
  void removingParentKeyHidesItFromChildButNotFromParent() {
    OptimizedTagMap parent = frozenParent();
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.withParent(parent);

    assertEquals("parent-a", child.getString("a")); // visible before removal
    child.remove("a");

    assertNull(child.getString("a")); // tombstoned: no longer reads through
    assertFalse(child.containsKey("a"));
    assertEquals("parent-b", child.getString("b")); // other parent keys unaffected
    assertEquals("parent-a", parent.getString("a")); // frozen parent untouched
  }

  @Test
  void removeReturnsPriorVisibleValueViaParent() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.withParent(frozenParent());

    // Map.remove contract: the key was present (via read-through), so removal reports it.
    assertTrue(child.remove("a"), "removing a parent-exposed key should report it was present");
    assertNull(child.getString("a"));
  }

  @Test
  void reSettingARemovedKeyRestoresVisibility() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.withParent(frozenParent());

    child.remove("a");
    assertNull(child.getString("a"));

    child.set("a", "child-a"); // re-set clears the tombstone
    assertEquals("child-a", child.getString("a"));
  }

  @Test
  void removingAKeyThatIsBothLocalAndParentHidesBoth() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.withParent(frozenParent());

    assertEquals("child-b", child.getString("b"));
    child.remove("b");

    assertNull(child.getString("b"), "removal must hide both the local entry and the parent's");
    assertEquals("parent-b", frozenParent().getString("b")); // parent still has it
  }

  // --- slice 3a: bulk forEach union + exact size/isEmpty ---

  private static Map<String, Object> collect(OptimizedTagMap map) {
    Map<String, Object> out = new HashMap<>();
    map.forEach(e -> out.put(e.tag(), e.objectValue()));
    return out;
  }

  @Test
  void forEachEmitsDedupedUnionLocalWins() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");
    child.withParent(frozenParent()); // parent {a, b}

    Map<String, Object> u = collect(child);
    assertEquals(3, u.size(), "union {a, b, c} with b deduped");
    assertEquals("parent-a", u.get("a")); // read-through
    assertEquals("child-b", u.get("b")); // local wins (no duplicate emit)
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void forEachSkipsTombstonedParentKeys() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());
    child.remove("a"); // tombstone parent's "a"

    Map<String, Object> u = collect(child);
    assertEquals(2, u.size());
    assertFalse(u.containsKey("a"));
    assertEquals("parent-b", u.get("b"));
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void biConsumerForEachAlsoEmitsUnion() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());

    Map<String, Object> out = new HashMap<>();
    child.forEach(out, (m, e) -> m.put(e.tag(), e.objectValue())); // non-capturing: alloc-free path
    assertEquals(3, out.size());
    assertEquals("parent-a", out.get("a"));
    assertEquals("child-c", out.get("c"));
  }

  @Test
  void sizeIsExactUnion() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows
    child.set("c", "child-c");
    child.withParent(frozenParent());
    assertEquals(3, child.size()); // {a, b, c} — b deduped, not 4

    child.remove("a");
    assertEquals(2, child.size()); // {b, c}
  }

  @Test
  void isEmptyExactWhenAllParentKeysTombstonedAndNoLocal() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.withParent(frozenParent()); // parent {a, b}
    assertFalse(child.isEmpty());

    child.remove("a");
    child.remove("b");
    assertTrue(child.isEmpty(), "all parent keys tombstoned and no local entries -> empty");
    assertEquals(0, child.size());
  }

  // --- slice 3b: pull-based iterators / collection views ---

  @Test
  void iteratorEmitsDedupedUnion() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");
    child.withParent(frozenParent());

    Map<String, Object> u = new HashMap<>();
    Iterator<TagMap.EntryReader> it = child.iterator();
    while (it.hasNext()) {
      TagMap.EntryReader e = it.next();
      u.put(e.tag(), e.objectValue());
    }
    assertEquals(3, u.size());
    assertEquals("parent-a", u.get("a"));
    assertEquals("child-b", u.get("b")); // local wins, emitted once
    assertEquals("child-c", u.get("c"));
  }

  @Test
  void keySetReflectsUnionAndTombstones() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());

    Set<String> keys = child.keySet();
    assertEquals(3, keys.size()); // a, b, c
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("c"));

    child.remove("a");
    assertEquals(2, child.keySet().size());
    assertFalse(child.keySet().contains("a"));
  }

  @Test
  void valuesAndEntrySetReflectUnion() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.withParent(frozenParent());

    assertEquals(2, child.entrySet().size()); // {a, b} — b deduped
    assertTrue(child.values().contains("child-b")); // local-won value
    assertTrue(child.values().contains("parent-a"));
    assertFalse(child.values().contains("parent-b"), "shadowed parent value must not appear");
  }

  // --- slice 4: behavior-identical to a copy-down / flat map ---

  @Test
  void copyIsObservationallyIdentical() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b"); // shadows parent "b"
    child.set("c", "child-c");
    child.withParent(frozenParent()); // {a, b}

    OptimizedTagMap copy = (OptimizedTagMap) child.copy();
    assertEquals(child.size(), copy.size());
    assertEquals("parent-a", copy.getString("a")); // copy still reads through
    assertEquals("child-b", copy.getString("b"));
    assertEquals("child-c", copy.getString("c"));
    assertEquals(collect(child), collect(copy)); // same union
  }

  @Test
  void copyIsIndependentlyMutable() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());

    OptimizedTagMap copy = (OptimizedTagMap) child.copy();
    copy.set("c", "copy-c"); // mutate copy's local
    copy.remove("a"); // tombstone on copy only

    assertEquals("child-c", child.getString("c"), "original unaffected by copy mutation");
    assertEquals("parent-a", child.getString("a"), "original still reads through a");
    assertEquals("copy-c", copy.getString("c"));
    assertNull(copy.getString("a"));
  }

  @Test
  void copyPreservesTombstones() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.withParent(frozenParent());
    child.remove("a"); // tombstone "a"

    OptimizedTagMap copy = (OptimizedTagMap) child.copy();
    assertNull(copy.getString("a"), "tombstone must carry into the copy");
    assertEquals("parent-b", copy.getString("b"));
  }

  /** The contract that lets the consumer flip mergedTracerTags to a parent. */
  @Test
  void readThroughMatchesAnEquivalentFlatMap() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("b", "child-b");
    child.set("c", "child-c");
    child.withParent(frozenParent());

    OptimizedTagMap flat = (OptimizedTagMap) TagMap.create();
    flat.set("a", "parent-a");
    flat.set("b", "child-b");
    flat.set("c", "child-c");

    assertEquals(flat.size(), child.size());
    assertEquals(collect(flat), collect(child));
    assertEquals(flat.keySet(), child.keySet());
    for (String k : new String[] {"a", "b", "c", "missing"}) {
      assertEquals(flat.getString(k), child.getString(k), "mismatch for key " + k);
    }
  }

  @Test
  void immutableCopyOfReadThroughIsFrozenAndStillReadsThrough() {
    OptimizedTagMap child = (OptimizedTagMap) TagMap.create();
    child.set("c", "child-c");
    child.withParent(frozenParent());

    OptimizedTagMap frozen = (OptimizedTagMap) child.immutableCopy();
    assertTrue(frozen.isFrozen());
    assertEquals("parent-a", frozen.getString("a")); // union preserved
    assertEquals("child-c", frozen.getString("c"));
    assertThrows(IllegalStateException.class, () -> frozen.set("x", "y")); // frozen blocks writes
  }
}
