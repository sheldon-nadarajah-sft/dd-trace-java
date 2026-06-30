package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Deterministic allocation A/B for the dense known-tag store, using the REAL {@link KnownTagIds}
 * resolver (a {@code StringIndex} probe + a constant-returning {@code switch} — allocation-free,
 * exactly like production). An earlier synthetic prefix resolver allocated in {@code keyOf}
 * (substring) and {@code nameOf} (concat), contaminating the dense arm; this measures the store,
 * not the resolver.
 *
 * <p>Models how a real span's tags route: {@code today} = all custom (what ships now — every tag
 * buckets, since nothing is registered as known), {@code dense} = the same tag count with a
 * realistic fraction routed to the dense store (real known tag names) and the rest custom. Run with
 * {@code -prof gc}; the {@code gc.alloc.rate.norm} (B/op) delta at the same {@code tagCount} is
 * what enabling the dense store does to a real span's per-build allocation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
@Threads(1)
public class DenseStoreAllocBenchmark {

  // Real stored (dense-routed) tag names — a realistic web/db span's known set.
  static final String[] KNOWN =
      new String[] {
        DDTags.BASE_SERVICE,
        Tags.VERSION,
        Tags.COMPONENT,
        Tags.SPAN_KIND,
        Tags.HTTP_METHOD,
        Tags.HTTP_ROUTE,
        Tags.DB_TYPE,
        Tags.DB_INSTANCE,
        Tags.PEER_HOSTNAME,
        Tags.DB_USER,
        DDTags.LANGUAGE_TAG_KEY,
        Tags.PEER_PORT,
      };

  // today = all custom (all bucket, what ships now); dense = ~70% known + custom (a real span);
  // allKnown = 100% known (the trace-tier read-through parent's shape — exercises lazy buckets).
  @Param({"today", "dense", "allKnown"})
  String scenario;

  @Param({"7", "12"})
  int tagCount;

  private String[] keys;
  private String[] values;

  @Setup(Level.Trial)
  public void setup() {
    KnownTagIds.init(); // registers the real (allocation-free) resolver
    int knownCount;
    if ("allKnown".equals(scenario)) {
      knownCount = tagCount; // 100% known (<= KNOWN.length)
    } else if ("dense".equals(scenario)) {
      knownCount = (tagCount * 7) / 10; // ~70% known + custom
    } else {
      knownCount = 0; // today: all custom (all bucket)
    }
    this.keys = new String[tagCount];
    this.values = new String[tagCount];
    for (int i = 0; i < tagCount; i++) {
      this.keys[i] = i < knownCount ? KNOWN[i] : "custom.tag." + i;
      this.values[i] = "value-" + i;
    }
  }

  @Benchmark
  public TagMap buildMap() {
    TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public void buildAndSerialize(Blackhole bh) {
    TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    // models the read/serialize path: forEach is the alloc-free flyweight emit for dense
    m.forEach(reader -> bh.consume(reader.objectValue()));
    bh.consume(m);
  }
}
