package datadog.trace.civisibility.coverage.line;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.civisibility.coverage.ConcurrentCoverageStore;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.Utils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coverage probes with line-level granularity, reporting coverage data that contains specific lines
 * that are covered in a file.
 */
public class LineCoverageStore extends ConcurrentCoverageStore<LineProbes> {

  private static final Logger log = LoggerFactory.getLogger(LineCoverageStore.class);

  /**
   * Upper bound on the number of cached class analyses. Coverage stays correct beyond it (analysis
   * just isn't cached), this only guards memory for pathologically large suites.
   */
  private static final int MAX_ANALYSIS_CACHE_ENTRIES = 50_000;

  // TEMPORARY evaluation instrumentation (SDTEST-3847 follow-up): measures the ceiling of a
  // per-class "structural" analysis cache vs the current (class, probe-set) cache, without building
  // it. parses ≈ distinct (class, probe-set) analyzed; distinctClasses = what a per-class cache
  // would parse. A high parses/distinctClasses ratio means the structural cache has headroom.
  // Logged once per JVM at shutdown (telemetry isn't readable in test-environment). Remove or gate
  // behind a flag before merging.
  private static final AtomicLong ANALYSIS_LOOKUPS = new AtomicLong();
  private static final AtomicLong ANALYSIS_PARSES = new AtomicLong();
  private static final AtomicLong ANALYSIS_PARSE_NANOS = new AtomicLong();
  private static final Set<Long> ANALYZED_CLASS_IDS = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean STATS_HOOK_REGISTERED = new AtomicBoolean();

  private static void registerAnalysisStatsHook() {
    if (!STATS_HOOK_REGISTERED.compareAndSet(false, true)) {
      return;
    }
    Runtime.getRuntime()
        .addShutdownHook(new Thread(LineCoverageStore::logAnalysisStats, "dd-line-cov-stats"));
  }

  private static void logAnalysisStats() {
    long lookups = ANALYSIS_LOOKUPS.get();
    if (lookups == 0) {
      return;
    }
    long parses = ANALYSIS_PARSES.get();
    long distinctClasses = ANALYZED_CLASS_IDS.size();
    double analyzeMs = ANALYSIS_PARSE_NANOS.get() / 1_000_000.0;
    double hitRate = 100.0 * (lookups - parses) / lookups;
    double avgProbeSetsPerClass = distinctClasses == 0 ? 0 : (double) parses / distinctClasses;
    double structuralHitRate = 100.0 * (lookups - distinctClasses) / lookups;
    double structuralSavedMs = parses == 0 ? 0 : analyzeMs * (parses - distinctClasses) / parses;
    log.info(
        "Line coverage analysis cache stats: lookups={}, parses={} (hitRate={}%), "
            + "distinctClasses={}, avgProbeSetsPerClass={}, analyzeTime={}ms | per-class structural "
            + "cache would parse {} (hitRate={}%, ~{}ms saved)",
        lookups,
        parses,
        String.format("%.1f", hitRate),
        distinctClasses,
        String.format("%.2f", avgProbeSetsPerClass),
        String.format("%.0f", analyzeMs),
        distinctClasses,
        String.format("%.1f", structuralHitRate),
        String.format("%.0f", structuralSavedMs));
  }

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;
  // Module-wide cache: (class id + probe set) -> covered lines, shared across tests so a class
  // covered identically by many tests is parsed by Jacoco's Analyzer only once.
  private final Map<AnalysisCacheKey, BitSet> analysisCache;

  private LineCoverageStore(
      Function<Boolean, LineProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver,
      Map<AnalysisCacheKey, BitSet> analysisCache) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
    this.analysisCache = analysisCache;
  }

  @Nullable
  @Override
  protected TestReport report(
      DDTraceId testSessionId, Long testSuiteId, long testSpanId, Collection<LineProbes> probes) {
    Map<Class<?>, ExecutionDataAdapter> combinedExecutionData = new IdentityHashMap<>();
    Collection<String> combinedNonCodeResources = new HashSet<>();

    for (LineProbes probe : probes) {
      for (Map.Entry<Class<?>, ExecutionDataAdapter> e : probe.getExecutionData().entrySet()) {
        combinedExecutionData.merge(e.getKey(), e.getValue(), ExecutionDataAdapter::merge);
      }
      combinedNonCodeResources.addAll(probe.getNonCodeResources());
    }

    if (combinedExecutionData.isEmpty() && combinedNonCodeResources.isEmpty()) {
      return null;
    }

    Map<String, BitSet> coveredLinesBySourcePath = new HashMap<>();
    for (Map.Entry<Class<?>, ExecutionDataAdapter> e : combinedExecutionData.entrySet()) {
      ExecutionDataAdapter executionDataAdapter = e.getValue();
      // Back-fill Jacoco's aggregate coverage (total coverage percentage and report uploads). Skip
      // classes with no covered probes: the per-test array is allocated on method entry, so a
      // method that throws before its first probe fires would otherwise yield an empty entry.
      if (!executionDataAdapter.mergeIntoJacocoProbes()) {
        continue;
      }
      String className = executionDataAdapter.getClassName();

      Class<?> clazz = e.getKey();
      Collection<String> sourcePaths = sourcePathResolver.getSourcePaths(clazz);
      if (sourcePaths.size() != 1) {
        log.debug(
            "Skipping coverage reporting for {} because source path could not be determined",
            className);
        metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
        continue;
      }
      String sourcePath = sourcePaths.iterator().next();

      BitSet coveredLines = analyzeClass(clazz, executionDataAdapter);
      if (coveredLines != null) {
        coveredLinesBySourcePath.computeIfAbsent(sourcePath, key -> new BitSet()).or(coveredLines);
      }
    }

    List<TestReportFileEntry> fileEntries = new ArrayList<>(coveredLinesBySourcePath.size());
    for (Map.Entry<String, BitSet> e : coveredLinesBySourcePath.entrySet()) {
      String sourcePath = e.getKey();
      BitSet coveredLines = e.getValue();
      fileEntries.add(new TestReportFileEntry(sourcePath, coveredLines));
    }

    for (String nonCodeResource : combinedNonCodeResources) {
      Collection<String> resourcePaths = sourcePathResolver.getResourcePaths(nonCodeResource);
      if (resourcePaths.isEmpty()) {
        log.debug(
            "Skipping coverage reporting for {} because resource path could not be determined",
            nonCodeResource);
        metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
        continue;
      }
      for (String resourcePath : resourcePaths) {
        fileEntries.add(new TestReportFileEntry(resourcePath, null));
      }
    }

    TestReport report = new TestReport(testSessionId, testSuiteId, testSpanId, fileEntries);
    metrics.add(
        CiVisibilityDistributionMetric.CODE_COVERAGE_FILES,
        report.getTestReportFileEntries().size());
    return report;
  }

  /**
   * Resolves the covered lines for a class given a test's probe activations. Parsing the class with
   * Jacoco's {@link Analyzer} is the dominant cost of reporting, and the result depends only on the
   * class bytecode and the probe set, so it is memoized: the same class covered identically by
   * different tests is parsed once.
   *
   * @return the covered lines, or {@code null} if the class could not be analyzed
   */
  @Nullable
  private BitSet analyzeClass(Class<?> clazz, ExecutionDataAdapter executionDataAdapter) {
    AnalysisCacheKey key =
        new AnalysisCacheKey(
            executionDataAdapter.getClassId(), executionDataAdapter.getProbeActivations());
    ANALYSIS_LOOKUPS.incrementAndGet(); // eval instrumentation (SDTEST-3847)
    ANALYZED_CLASS_IDS.add(executionDataAdapter.getClassId()); // eval instrumentation
    BitSet cached = analysisCache.get(key);
    if (cached != null) {
      return cached;
    }

    try (InputStream is = Utils.getClassStream(clazz)) {
      long startNanos = System.nanoTime(); // eval instrumentation
      BitSet coveredLines = new BitSet();
      ExecutionDataStore store = new ExecutionDataStore();
      store.put(executionDataAdapter.toExecutionData());
      Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(coveredLines));
      analyzer.analyzeClass(is, null);
      ANALYSIS_PARSES.incrementAndGet(); // eval instrumentation
      ANALYSIS_PARSE_NANOS.addAndGet(System.nanoTime() - startNanos); // eval instrumentation

      if (analysisCache.size() < MAX_ANALYSIS_CACHE_ENTRIES) {
        analysisCache.putIfAbsent(key, coveredLines);
      }
      return coveredLines;

    } catch (Exception exception) {
      log.debug(
          "Skipping coverage reporting for {} because of error",
          executionDataAdapter.getClassName(),
          exception);
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      return null;
    }
  }

  /** Cache key identifying a class (by Jacoco class id) covered by a specific set of probes. */
  static final class AnalysisCacheKey {
    private final long classId;
    private final boolean[] probes;
    private final int hash;

    AnalysisCacheKey(long classId, boolean[] probes) {
      this.classId = classId;
      this.probes = probes;
      this.hash = 31 * Long.hashCode(classId) + Arrays.hashCode(probes);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AnalysisCacheKey)) {
        return false;
      }
      AnalysisCacheKey other = (AnalysisCacheKey) o;
      return classId == other.classId && hash == other.hash && Arrays.equals(probes, other.probes);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  public static final class Factory implements CoverageStore.Factory {

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;
    private final Map<AnalysisCacheKey, BitSet> analysisCache = new ConcurrentHashMap<>();

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
      registerAnalysisStatsHook(); // eval instrumentation (SDTEST-3847)
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new LineCoverageStore(this::createProbes, metrics, sourcePathResolver, analysisCache);
    }

    private LineProbes createProbes(boolean isTestThread) {
      return new LineProbes(metrics, isTestThread);
    }
  }
}
