package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CodePointLevenshteinScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedExactStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedIndexStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchOutcome;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchPolicy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchRequest;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Concurrent synthetic end-to-end load comparison over the Java service and PostgreSQL adapter. */
public final class PostgresLoadBenchmark {
  private static final String PLAINTEXT = "PLAINTEXT_CONTROL";
  private static final String EFS = "EFS_FULL";

  private PostgresLoadBenchmark() {}

  public static void main(String[] args) throws Exception {
    Profile profile = Profile.parse(args);
    int positiveCount = Math.max(1, (profile.recordCount() * 2) / 3);
    int hardNegativeCount = profile.recordCount() - positiveCount;
    List<FuzzySearchBenchmark.Fixture> corpus =
        FuzzySearchBenchmark.createCorpus(positiveCount, hardNegativeCount);
    List<FuzzySearchBenchmark.QueryCase> queries =
        FuzzySearchBenchmark.createQueries(corpus, Math.min(300, positiveCount));
    PostgresTestDataSource dataSource = new PostgresTestDataSource(profile.requestTimeoutMillis());
    PostgresTokenIndexRepository repository = new PostgresTokenIndexRepository(dataSource);
    repository.initializeSchema();
    PlaintextBenchmarkStore plaintextStore = new PlaintextBenchmarkStore(dataSource);
    plaintextStore.initializeSchema();

    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x27);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    ScopedKeyDeriver keyDeriver = new ScopedKeyDeriver(ignored -> rootKey);
    CanonicalTextNormalizer normalizer = new CanonicalTextNormalizer();
    List<ProtectedIndexStrategy> strategies =
        List.of(
            new ProtectedExactStrategy(new ExactBlindIndexer()),
            new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
            new ProtectedNgramStrategy(3, new NgramBlindIndexer()));
    EncryptedFuzzySearchService service =
        new EncryptedFuzzySearchService(
            keyDeriver,
            new AuthenticatedFieldCipher(),
            normalizer,
            strategies,
            repository,
            SearchPolicy.productionDefault()
                .allowingScorers(Set.of(CodePointLevenshteinScorer.ID)));

    if (profile.mode().equals("both") || profile.mode().equalsIgnoreCase(EFS)) {
      plaintextStore.clear();
      truncate(dataSource, "efs_encrypted_records");
      long started = System.nanoTime();
      for (FuzzySearchBenchmark.Fixture fixture : corpus) {
        service.indexValue(
            FuzzySearchBenchmark.TENANT_ID,
            FuzzySearchBenchmark.FIELD_ID,
            fixture.id(),
            FuzzySearchBenchmark.KEY_ID,
            fixture.value());
      }
      reportMode(
          EFS,
          profile,
          corpus,
          queries,
          dataSource,
          started,
          query -> {
            SearchOutcome outcome =
                service.search(
                    SearchRequest.approximate(
                        FuzzySearchBenchmark.TENANT_ID,
                        FuzzySearchBenchmark.FIELD_ID,
                        FuzzySearchBenchmark.KEY_ID,
                        query,
                        profile.candidateLimit(),
                        FuzzySearchBenchmark.MINIMUM_SIMILARITY));
            return new SearchResult(
                outcome.candidatesVerified(),
                outcome.candidatesVerified() >= profile.candidateLimit(),
                outcome.matches().size());
          },
          "efs_protected_tokens",
          "efs_encrypted_records");
    }

    if (profile.mode().equals("both") || profile.mode().equalsIgnoreCase(PLAINTEXT)) {
      truncate(dataSource, "efs_encrypted_records");
      plaintextStore.clear();
      long started = System.nanoTime();
      for (FuzzySearchBenchmark.Fixture fixture : corpus) {
        plaintextStore.upsert(
            FuzzySearchBenchmark.TENANT_ID,
            FuzzySearchBenchmark.FIELD_ID,
            fixture.id(),
            FuzzySearchBenchmark.KEY_ID,
            normalizer.normalize(fixture.value()));
      }
      reportMode(
          PLAINTEXT,
          profile,
          corpus,
          queries,
          dataSource,
          started,
          query -> {
            String canonical = normalizer.normalize(query);
            List<PlaintextBenchmarkStore.PlaintextCandidate> candidates =
                plaintextStore.findCandidates(
                    FuzzySearchBenchmark.TENANT_ID,
                    FuzzySearchBenchmark.FIELD_ID,
                    FuzzySearchBenchmark.KEY_ID,
                    PlaintextBenchmarkStore.createTokens(canonical),
                    profile.candidateLimit());
            List<PlaintextBenchmarkRunner.ScoredCandidate> accepted =
                PlaintextBenchmarkRunner.score(canonical, candidates, normalizer);
            return new SearchResult(
                candidates.size(), candidates.size() >= profile.candidateLimit(), accepted.size());
          },
          PlaintextBenchmarkStore.TOKEN_TABLE,
          PlaintextBenchmarkStore.RECORD_TABLE);
    }
  }

  private static void reportMode(
      String mode,
      Profile profile,
      List<FuzzySearchBenchmark.Fixture> corpus,
      List<FuzzySearchBenchmark.QueryCase> queries,
      PostgresTestDataSource dataSource,
      long indexingStarted,
      SearchOperation operation,
      String tokenTable,
      String recordTable)
      throws Exception {
    long indexingNanos = System.nanoTime() - indexingStarted;
    long coldStarted = System.nanoTime();
    operation.search(queries.get(0).value());
    double coldMillis = (System.nanoTime() - coldStarted) / 1_000_000.0;

    long gcCountBefore = gcCount();
    long gcTimeBefore = gcTimeMillis();
    long processCpuBefore = processCpuNanos();
    long usedHeapBefore = usedHeapBytes();
    runPhase(profile, queries, operation, profile.warmupSeconds(), false);
    PhaseResult measured = runPhase(profile, queries, operation, profile.durationSeconds(), true);
    long usedHeapAfter = usedHeapBytes();
    long gcCount = Math.max(0, gcCount() - gcCountBefore);
    long gcTime = Math.max(0, gcTimeMillis() - gcTimeBefore);
    long processCpuNanos = Math.max(0, processCpuNanos() - processCpuBefore);
    long tokenRelationBytes =
        queryLong(dataSource, "SELECT pg_total_relation_size('" + tokenTable + "')");
    long recordRelationBytes =
        queryLong(dataSource, "SELECT pg_total_relation_size('" + recordTable + "')");
    String postgresVersion = queryString(dataSource, "SHOW server_version");
    String postgresSharedBuffers = queryString(dataSource, "SHOW shared_buffers");
    String postgresWorkMem = queryString(dataSource, "SHOW work_mem");
    String postgresMaxConnections = queryString(dataSource, "SHOW max_connections");

    System.out.println("=== load_benchmark_mode=" + mode + " ===");
    System.out.println(
        "fixture=synthetic-random-codepoints-seed-" + FuzzySearchBenchmark.CORPUS_SEED);
    System.out.println(
        "records="
            + corpus.size()
            + ", labeled_queries="
            + queries.size()
            + ", candidate_limit="
            + profile.candidateLimit());
    System.out.println(
        "concurrency="
            + profile.concurrency()
            + ", warmup_seconds="
            + profile.warmupSeconds()
            + ", measurement_seconds="
            + profile.durationSeconds()
            + ", no_match_percent="
            + profile.noMatchPercent()
            + ", request_timeout_ms="
            + profile.requestTimeoutMillis());
    System.out.println("java_version=" + System.getProperty("java.version"));
    System.out.println("postgres_version=" + postgresVersion);
    System.out.println(
        "postgres_config=shared_buffers:"
            + postgresSharedBuffers
            + ",work_mem:"
            + postgresWorkMem
            + ",max_connections:"
            + postgresMaxConnections);
    System.out.println("os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    System.out.println("processors=" + Runtime.getRuntime().availableProcessors());
    System.out.println("jvm_max_heap_bytes=" + Runtime.getRuntime().maxMemory());
    System.out.printf("cold_first_search_latency_ms=%.3f%n", coldMillis);
    System.out.printf(
        "index_records_per_second=%.2f%n", corpus.size() / (indexingNanos / 1_000_000_000.0));
    System.out.printf(
        "warm_latency_ms_p50=%.3f%n", percentileMillis(measured.latenciesNanos(), 0.50));
    System.out.printf(
        "warm_latency_ms_p95=%.3f%n", percentileMillis(measured.latenciesNanos(), 0.95));
    System.out.printf(
        "warm_latency_ms_p99=%.3f%n", percentileMillis(measured.latenciesNanos(), 0.99));
    System.out.printf(
        "completed_throughput_qps=%.2f%n", measured.completed() / measured.elapsedSeconds());
    System.out.println(
        "completed="
            + measured.completed()
            + ", failures="
            + measured.failures()
            + ", timeouts="
            + measured.timeouts()
            + ", completed_over_timeout_threshold="
            + measured.overTimeout()
            + ", candidates_per_completed_search="
            + (measured.completed() == 0
                ? "0.000"
                : "%.3f".formatted((double) measured.candidateTotal() / measured.completed())));
    System.out.printf(
        "candidate_limit_saturation_rate=%.4f (%d/%d)%n",
        measured.completed() == 0 ? 0.0 : (double) measured.saturated() / measured.completed(),
        measured.saturated(),
        measured.completed());
    System.out.printf(
        "matches_per_completed_search=%.3f%n",
        measured.completed() == 0 ? 0.0 : (double) measured.matchTotal() / measured.completed());
    System.out.println(
        "indexing_mode=serial seed phase; warmup and measured searches run with fixed worker concurrency");
    System.out.println(
        "jvm_used_heap_before_bytes="
            + usedHeapBefore
            + ", jvm_used_heap_after_bytes="
            + usedHeapAfter
            + ", gc_count_delta="
            + gcCount
            + ", gc_time_ms_delta="
            + gcTime
            + ", process_cpu_ms_delta="
            + (processCpuNanos / 1_000_000));
    System.out.println(
        "postgres_token_relation_bytes="
            + tokenRelationBytes
            + ", postgres_record_relation_bytes="
            + recordRelationBytes);
    System.out.println(
        "privacy=synthetic-only disposable local database; no values, normalized forms, tokens, or keys are emitted");
  }

  private static PhaseResult runPhase(
      Profile profile,
      List<FuzzySearchBenchmark.QueryCase> queries,
      SearchOperation operation,
      int durationSeconds,
      boolean collect)
      throws Exception {
    if (durationSeconds == 0) {
      return PhaseResult.empty();
    }
    ExecutorService executor = Executors.newFixedThreadPool(profile.concurrency());
    CountDownLatch ready = new CountDownLatch(profile.concurrency());
    CountDownLatch start = new CountDownLatch(1);
    List<WorkerStats> stats = new ArrayList<>(profile.concurrency());
    List<Future<?>> workers = new ArrayList<>(profile.concurrency());
    long[] deadline = {0L};
    for (int workerIndex = 0; workerIndex < profile.concurrency(); workerIndex++) {
      WorkerStats workerStats = new WorkerStats();
      stats.add(workerStats);
      int workerId = workerIndex;
      workers.add(
          executor.submit(
              () -> {
                ready.countDown();
                try {
                  start.await();
                  long operationIndex = workerId;
                  while (System.nanoTime() < deadline[0]) {
                    String query =
                        nextQuery(queries, profile.noMatchPercent(), workerId, operationIndex++);
                    long operationStarted = System.nanoTime();
                    SearchResult result = operation.search(query);
                    long elapsed = System.nanoTime() - operationStarted;
                    if (collect) {
                      workerStats.completed++;
                      workerStats.candidateTotal += result.candidateCount();
                      workerStats.matchTotal += result.matchCount();
                      if (result.saturated()) {
                        workerStats.saturated++;
                      }
                      if (TimeUnit.NANOSECONDS.toMillis(elapsed) > profile.requestTimeoutMillis()) {
                        workerStats.overTimeout++;
                      }
                      workerStats.latencies.add(elapsed);
                    }
                  }
                } catch (SQLException failure) {
                  if ("57014".equals(failure.getSQLState())) {
                    workerStats.timeouts++;
                  } else {
                    workerStats.failures++;
                  }
                } catch (Exception failure) {
                  workerStats.failures++;
                }
              }));
    }
    ready.await();
    long phaseStart = System.nanoTime();
    deadline[0] = phaseStart + TimeUnit.SECONDS.toNanos(durationSeconds);
    start.countDown();
    for (Future<?> worker : workers) {
      worker.get(durationSeconds + 60L, TimeUnit.SECONDS);
    }
    executor.shutdown();
    executor.awaitTermination(60, TimeUnit.SECONDS);
    long elapsedNanos = System.nanoTime() - phaseStart;
    return PhaseResult.from(stats, elapsedNanos);
  }

  private static String nextQuery(
      List<FuzzySearchBenchmark.QueryCase> queries,
      int noMatchPercent,
      int workerId,
      long operationIndex) {
    long mixedIndex = operationIndex + (workerId * 7L);
    if (Math.floorMod(mixedIndex, 100) < noMatchPercent) {
      return "zzzzzzzzzz" + Math.floorMod(mixedIndex, 10_000);
    }
    return queries.get(Math.floorMod(mixedIndex, queries.size())).value();
  }

  private static void truncate(PostgresTestDataSource dataSource, String table) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE TABLE " + table + " CASCADE");
    }
  }

  private static long queryLong(PostgresTestDataSource dataSource, String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static String queryString(PostgresTestDataSource dataSource, String sql)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }

  private static double percentileMillis(List<Long> samples, double percentile) {
    if (samples.isEmpty()) {
      return 0.0;
    }
    List<Long> sorted = samples.stream().sorted().toList();
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, index)) / 1_000_000.0;
  }

  private static long usedHeapBytes() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionCount)
        .filter(value -> value >= 0)
        .sum();
  }

  private static long gcTimeMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionTime)
        .filter(value -> value >= 0)
        .sum();
  }

  private static long processCpuNanos() {
    java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
    if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
      return extended.getProcessCpuTime();
    }
    return 0L;
  }

  @FunctionalInterface
  private interface SearchOperation {
    SearchResult search(String query) throws Exception;
  }

  private record SearchResult(int candidateCount, boolean saturated, int matchCount) {}

  private record PhaseResult(
      long completed,
      long failures,
      long timeouts,
      long overTimeout,
      long candidateTotal,
      long matchTotal,
      long saturated,
      List<Long> latenciesNanos,
      double elapsedSeconds) {
    static PhaseResult empty() {
      return new PhaseResult(0, 0, 0, 0, 0, 0, 0, List.of(), 0.0);
    }

    static PhaseResult from(List<WorkerStats> stats, long elapsedNanos) {
      List<Long> latencies = new ArrayList<>();
      stats.forEach(worker -> latencies.addAll(worker.latencies));
      return new PhaseResult(
          stats.stream().mapToLong(worker -> worker.completed).sum(),
          stats.stream().mapToLong(worker -> worker.failures).sum(),
          stats.stream().mapToLong(worker -> worker.timeouts).sum(),
          stats.stream().mapToLong(worker -> worker.overTimeout).sum(),
          stats.stream().mapToLong(worker -> worker.candidateTotal).sum(),
          stats.stream().mapToLong(worker -> worker.matchTotal).sum(),
          stats.stream().mapToLong(worker -> worker.saturated).sum(),
          List.copyOf(latencies),
          elapsedNanos / 1_000_000_000.0);
    }
  }

  private static final class WorkerStats {
    private long completed;
    private long failures;
    private long timeouts;
    private long overTimeout;
    private long candidateTotal;
    private long matchTotal;
    private long saturated;
    private final List<Long> latencies = new ArrayList<>();
  }

  record Profile(
      int recordCount,
      int candidateLimit,
      int concurrency,
      int durationSeconds,
      int warmupSeconds,
      int noMatchPercent,
      int requestTimeoutMillis,
      String mode) {
    static Profile parse(String[] args) {
      int recordCount = 600;
      int candidateLimit = 100;
      int concurrency = 4;
      int durationSeconds = 10;
      int warmupSeconds = 2;
      int noMatchPercent = 10;
      int requestTimeoutMillis = 5_000;
      String mode = "both";
      for (String argument : args) {
        String[] parts = argument.replaceFirst("^--", "").split("=", 2);
        if (parts.length != 2) {
          throw new IllegalArgumentException("expected --name=value benchmark arguments");
        }
        switch (parts[0]) {
          case "records" -> recordCount = Integer.parseInt(parts[1]);
          case "candidate-limit" -> candidateLimit = Integer.parseInt(parts[1]);
          case "concurrency" -> concurrency = Integer.parseInt(parts[1]);
          case "duration-seconds" -> durationSeconds = Integer.parseInt(parts[1]);
          case "warmup-seconds" -> warmupSeconds = Integer.parseInt(parts[1]);
          case "no-match-percent" -> noMatchPercent = Integer.parseInt(parts[1]);
          case "request-timeout-ms" -> requestTimeoutMillis = Integer.parseInt(parts[1]);
          case "mode" -> mode = parts[1];
          default -> throw new IllegalArgumentException("unknown benchmark option: " + parts[0]);
        }
      }
      if (recordCount < 3 || recordCount > 100_000) {
        throw new IllegalArgumentException("records must be between 3 and 100000");
      }
      if (candidateLimit < 1 || candidateLimit > 10_000) {
        throw new IllegalArgumentException("candidate-limit must be between 1 and 10000");
      }
      if (concurrency < 1 || concurrency > 64) {
        throw new IllegalArgumentException("concurrency must be between 1 and 64");
      }
      if (durationSeconds < 1 || durationSeconds > 3600) {
        throw new IllegalArgumentException("duration-seconds must be between 1 and 3600");
      }
      if (warmupSeconds < 0 || warmupSeconds > 600) {
        throw new IllegalArgumentException("warmup-seconds must be between 0 and 600");
      }
      if (noMatchPercent < 0 || noMatchPercent > 100) {
        throw new IllegalArgumentException("no-match-percent must be between 0 and 100");
      }
      if (requestTimeoutMillis < 1 || requestTimeoutMillis > 120_000) {
        throw new IllegalArgumentException("request-timeout-ms must be between 1 and 120000");
      }
      if (!Set.of("both", EFS, PLAINTEXT).contains(mode)) {
        throw new IllegalArgumentException("mode must be both, EFS_FULL, or PLAINTEXT_CONTROL");
      }
      return new Profile(
          recordCount,
          candidateLimit,
          concurrency,
          durationSeconds,
          warmupSeconds,
          noMatchPercent,
          requestTimeoutMillis,
          mode);
    }
  }
}
