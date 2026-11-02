package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CandidateLocalBm25Scorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CodePointLevenshteinScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.DamerauLevenshteinScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FinalScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FuzzySearchResult;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedExactStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedIndexStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedToken;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchCandidate;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchOutcome;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchPolicy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchRequest;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SoftTfIdfScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenSetScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.WeightedEditScorer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.WeightedNgramScorer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic, synthetic, single-process benchmark for real PostgreSQL search behavior. */
public final class FuzzySearchBenchmark {
  static final int POSITIVE_RECORD_COUNT = 400;
  static final int HARD_NEGATIVE_COUNT = 200;
  static final int RECORD_COUNT = POSITIVE_RECORD_COUNT + HARD_NEGATIVE_COUNT;
  static final int QUERY_COUNT = 300;
  static final int NO_MATCH_QUERY_COUNT = 50;
  static final int WARMUP_QUERY_COUNT = 30;
  static final int CANDIDATE_LIMIT = 100;
  static final double MINIMUM_SIMILARITY = 0.80;
  static final long CORPUS_SEED = 20261019L;
  static final String TENANT_ID = "benchmark-tenant";
  static final String FIELD_ID = "name";
  static final String KEY_ID = "benchmark-key-v1";
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxy";

  private FuzzySearchBenchmark() {}

  /**
   * Runs against the Compose test database and emits aggregate-only results (no fixture values).
   */
  public static void main(String[] args) throws Exception {
    PostgresTestDataSource dataSource = new PostgresTestDataSource();
    PostgresTokenIndexRepository repository = new PostgresTokenIndexRepository(dataSource);
    repository.initializeSchema();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE TABLE efs_encrypted_records CASCADE");
    }

    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x27);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    ScopedKeyDeriver keyDeriver = new ScopedKeyDeriver(ignored -> rootKey);
    NgramBlindIndexer ngramIndexer = new NgramBlindIndexer();
    ProtectedNgramStrategy bigrams = new ProtectedNgramStrategy(2, ngramIndexer);
    ProtectedNgramStrategy trigrams = new ProtectedNgramStrategy(3, ngramIndexer);
    CanonicalTextNormalizer normalizer = new CanonicalTextNormalizer();
    ExactBlindIndexer exactIndexer = new ExactBlindIndexer();
    List<ProtectedIndexStrategy> strategies =
        List.of(new ProtectedExactStrategy(exactIndexer), bigrams, trigrams);
    List<FinalScorer> scorers = scorers();
    EncryptedFuzzySearchService service =
        new EncryptedFuzzySearchService(
            keyDeriver,
            new AuthenticatedFieldCipher(),
            normalizer,
            strategies,
            repository,
            SearchPolicy.productionDefault()
                .allowingScorers(
                    scorers.stream()
                        .map(FinalScorer::id)
                        .collect(java.util.stream.Collectors.toSet())));

    List<Fixture> corpus = createCorpus();
    long indexStarted = System.nanoTime();
    for (Fixture fixture : corpus) {
      service.indexValue(TENANT_ID, FIELD_ID, fixture.id(), KEY_ID, fixture.value());
    }
    long indexElapsed = System.nanoTime() - indexStarted;

    List<QueryCase> queryCases = createQueries(corpus);
    for (int index = 0; index < WARMUP_QUERY_COUNT; index++) {
      QueryCase query = queryCases.get(index);
      service.search(
          TENANT_ID, FIELD_ID, KEY_ID, query.value(), CANDIDATE_LIMIT, MINIMUM_SIMILARITY);
    }

    List<Long> retrievalLatencies = new ArrayList<>(QUERY_COUNT);
    List<Long> searchLatencies = new ArrayList<>(QUERY_COUNT);
    List<List<String>> protectedCandidateIds = new ArrayList<>(QUERY_COUNT);
    List<List<String>> protectedFinalIds = new ArrayList<>(QUERY_COUNT);
    Set<String> uniqueTokens = new HashSet<>();
    Map<String, Integer> tokenFrequencies = new HashMap<>();
    long tokenCount = 0;
    long logicalTokenBytes = 0;
    long ciphertextBytes = 0;
    int candidateTruePositives = 0;
    int candidateCount = 0;
    int saturatedCandidateQueries = 0;
    int finalTruePositives = 0;
    int finalFalsePositives = 0;

    for (Fixture fixture : corpus) {
      List<ProtectedToken> recordTokens =
          createTokens(keyDeriver, exactIndexer, bigrams, trigrams, normalizer, fixture.value());
      tokenCount += recordTokens.size();
      for (ProtectedToken token : recordTokens) {
        String identity =
            token.strategyId()
                + ':'
                + token.formatVersion()
                + ':'
                + Base64.getEncoder().encodeToString(token.value());
        uniqueTokens.add(identity);
        tokenFrequencies.merge(identity, 1, Integer::sum);
        logicalTokenBytes += token.value().length;
      }
    }

    for (QueryCase query : queryCases) {
      List<ProtectedToken> queryTokens =
          createTokens(keyDeriver, exactIndexer, bigrams, trigrams, normalizer, query.value());
      long retrievalStarted = System.nanoTime();
      List<SearchCandidate> candidates =
          repository.findCandidates(TENANT_ID, FIELD_ID, KEY_ID, queryTokens, CANDIDATE_LIMIT);
      retrievalLatencies.add(System.nanoTime() - retrievalStarted);
      protectedCandidateIds.add(candidates.stream().map(SearchCandidate::recordId).toList());
      candidateCount += candidates.size();
      if (candidates.size() == CANDIDATE_LIMIT) {
        saturatedCandidateQueries++;
      }
      if (candidates.stream()
          .anyMatch(candidate -> candidate.recordId().equals(query.expectedId()))) {
        candidateTruePositives++;
      }

      long searchStarted = System.nanoTime();
      List<FuzzySearchResult> results =
          service.search(
              TENANT_ID, FIELD_ID, KEY_ID, query.value(), CANDIDATE_LIMIT, MINIMUM_SIMILARITY);
      searchLatencies.add(System.nanoTime() - searchStarted);
      protectedFinalIds.add(results.stream().map(FuzzySearchResult::recordId).toList());
      if (results.stream().anyMatch(result -> result.recordId().equals(query.expectedId()))) {
        finalTruePositives++;
      }
      finalFalsePositives +=
          (int)
              results.stream()
                  .filter(result -> !result.recordId().equals(query.expectedId()))
                  .count();
    }

    int noMatchFalsePositives = 0;
    for (int index = 0; index < NO_MATCH_QUERY_COUNT; index++) {
      String query = "zzzzzzzzzz" + index;
      if (!service
          .search(TENANT_ID, FIELD_ID, KEY_ID, query, CANDIDATE_LIMIT, MINIMUM_SIMILARITY)
          .isEmpty()) {
        noMatchFalsePositives++;
      }
    }
    finalFalsePositives += noMatchFalsePositives;

    printScorerComparisons(service, queryCases, scorers);

    long physicalTokenBytes =
        queryLong(dataSource, "SELECT pg_total_relation_size('efs_protected_tokens')");
    long physicalRecordBytes =
        queryLong(dataSource, "SELECT pg_total_relation_size('efs_encrypted_records')");
    ciphertextBytes =
        queryLong(
            dataSource,
            "SELECT COALESCE(SUM(OCTET_LENGTH(ciphertext)), 0) FROM efs_encrypted_records");
    String postgresVersion = queryString(dataSource, "SHOW server_version");
    int maximumTokenFrequency =
        tokenFrequencies.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    double averageTokenFrequency =
        tokenFrequencies.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
    double candidatePrecision =
        candidateCount == 0 ? 0.0 : (double) candidateTruePositives / candidateCount;
    double candidateRecall = (double) candidateTruePositives / queryCases.size();
    double finalRecall = (double) finalTruePositives / queryCases.size();
    long searchElapsed = searchLatencies.stream().mapToLong(Long::longValue).sum();

    System.out.println("=== benchmark_mode=EFS_FULL ===");
    System.out.println("EFS synthetic benchmark (aggregate-only; no fixture values emitted)");
    System.out.println("corpus_id=synthetic-random-codepoints-seed-20261019-v1");
    System.out.println(
        "records="
            + RECORD_COUNT
            + ", typo_queries="
            + QUERY_COUNT
            + ", no_match_queries="
            + NO_MATCH_QUERY_COUNT);
    System.out.println(
        "candidate_limit=" + CANDIDATE_LIMIT + ", similarity_threshold=" + MINIMUM_SIMILARITY);
    System.out.println("postgres_version=" + postgresVersion);
    System.out.println("java_version=" + System.getProperty("java.version"));
    System.out.println("os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    System.out.println("processors=" + Runtime.getRuntime().availableProcessors());
    System.out.println("jvm_max_heap_bytes=" + Runtime.getRuntime().maxMemory());
    System.out.printf(
        "index_records_per_second=%.2f%n", RECORD_COUNT / (indexElapsed / 1_000_000_000.0));
    System.out.printf(
        "candidate_recall=%.4f (%d/%d)%n",
        candidateRecall, candidateTruePositives, queryCases.size());
    System.out.printf(
        "candidate_precision=%.4f (%d/%d candidates)%n",
        candidatePrecision, candidateTruePositives, candidateCount);
    System.out.printf(
        "final_recall=%.4f (%d/%d)%n", finalRecall, finalTruePositives, queryCases.size());
    System.out.printf(
        "final_precision=%.4f%n",
        (double) finalTruePositives / (finalTruePositives + finalFalsePositives));
    System.out.printf(
        "no_match_false_positive_rate=%.4f (%d/%d)%n",
        (double) noMatchFalsePositives / NO_MATCH_QUERY_COUNT,
        noMatchFalsePositives,
        NO_MATCH_QUERY_COUNT);
    System.out.printf(
        "candidates_per_typo_query=%.2f%n", (double) candidateCount / queryCases.size());
    System.out.printf(
        "candidate_limit_saturation_rate=%.4f (%d/%d)%n",
        (double) saturatedCandidateQueries / queryCases.size(),
        saturatedCandidateQueries,
        queryCases.size());
    System.out.printf(
        "candidate_retrieval_latency_ms_p50=%.3f%n", percentileMillis(retrievalLatencies, 0.50));
    System.out.printf(
        "candidate_retrieval_latency_ms_p95=%.3f%n", percentileMillis(retrievalLatencies, 0.95));
    System.out.printf(
        "candidate_retrieval_latency_ms_p99=%.3f%n", percentileMillis(retrievalLatencies, 0.99));
    System.out.printf("full_search_latency_ms_p50=%.3f%n", percentileMillis(searchLatencies, 0.50));
    System.out.printf("full_search_latency_ms_p95=%.3f%n", percentileMillis(searchLatencies, 0.95));
    System.out.printf("full_search_latency_ms_p99=%.3f%n", percentileMillis(searchLatencies, 0.99));
    System.out.printf(
        "full_search_throughput_qps=%.2f%n", queryCases.size() / (searchElapsed / 1_000_000_000.0));
    System.out.println(
        "token_postings=" + tokenCount + ", unique_protected_tokens=" + uniqueTokens.size());
    System.out.printf(
        "max_token_frequency=%d, mean_frequency_per_unique_token=%.3f%n",
        maximumTokenFrequency, averageTokenFrequency);
    System.out.println(
        "logical_token_bytes="
            + logicalTokenBytes
            + ", logical_ciphertext_bytes="
            + ciphertextBytes);
    System.out.printf(
        "logical_index_amplification=%.3f%n", (double) logicalTokenBytes / ciphertextBytes);
    System.out.println(
        "postgres_token_relation_bytes="
            + physicalTokenBytes
            + ", postgres_record_relation_bytes="
            + physicalRecordBytes);
    System.out.println(
        "measurement_mode=single-threaded local Docker database, 30 warm-up queries excluded from timed samples");
    System.out.println(
        "limitations=single synthetic corpus and local environment; measurements are exploratory, not SLOs or recall guarantees");

    PlaintextBenchmarkRunner.run(
        dataSource,
        corpus,
        queryCases,
        protectedCandidateIds,
        protectedFinalIds,
        noMatchFalsePositives);
  }

  private static List<FinalScorer> scorers() {
    return List.of(
        new CodePointLevenshteinScorer(),
        new DamerauLevenshteinScorer(),
        new WeightedEditScorer(Map.of("1i", 0.2, "1l", 0.2, "0o", 0.2)),
        new WeightedNgramScorer(2),
        new TokenSetScorer(),
        new CandidateLocalBm25Scorer(),
        new SoftTfIdfScorer(0.75));
  }

  private static void printScorerComparisons(
      EncryptedFuzzySearchService service, List<QueryCase> queryCases, List<FinalScorer> scorers)
      throws Exception {
    System.out.println("v0.2 candidate-set final scorer comparison (same retrieved candidates):");
    for (FinalScorer scorer : scorers) {
      int truePositives = 0;
      int falsePositives = 0;
      int topOne = 0;
      int noMatchFalsePositives = 0;
      double reciprocalRankSum = 0.0;
      for (QueryCase query : queryCases) {
        SearchOutcome outcome =
            service.search(
                SearchRequest.approximate(
                        TENANT_ID,
                        FIELD_ID,
                        KEY_ID,
                        query.value(),
                        CANDIDATE_LIMIT,
                        MINIMUM_SIMILARITY)
                    .withScorer(scorer));
        List<FuzzySearchResult> results = outcome.matches();
        int targetRank = 0;
        for (int rank = 0; rank < results.size(); rank++) {
          if (results.get(rank).recordId().equals(query.expectedId())) {
            truePositives++;
            targetRank = rank + 1;
            if (rank == 0) {
              topOne++;
            }
          } else {
            falsePositives++;
          }
        }
        if (targetRank != 0) {
          reciprocalRankSum += 1.0 / targetRank;
        }
      }
      for (int index = 0; index < NO_MATCH_QUERY_COUNT; index++) {
        String query = "zzzzzzzzzz" + index;
        if (!service
            .search(
                SearchRequest.approximate(
                        TENANT_ID, FIELD_ID, KEY_ID, query, CANDIDATE_LIMIT, MINIMUM_SIMILARITY)
                    .withScorer(scorer))
            .matches()
            .isEmpty()) {
          noMatchFalsePositives++;
        }
      }
      falsePositives += noMatchFalsePositives;
      String precision =
          truePositives + falsePositives == 0
              ? "undefined (no accepted results)"
              : "%.4f".formatted((double) truePositives / (truePositives + falsePositives));
      System.out.printf(
          "%s: final_recall=%.4f, final_precision=%s, mrr=%.4f, top1=%d/%d, no_match_fp=%d/%d%n",
          scorer.id(),
          (double) truePositives / queryCases.size(),
          precision,
          reciprocalRankSum / queryCases.size(),
          topOne,
          queryCases.size(),
          noMatchFalsePositives,
          NO_MATCH_QUERY_COUNT);
    }
  }

  static List<Fixture> createCorpus() {
    return createCorpus(POSITIVE_RECORD_COUNT, HARD_NEGATIVE_COUNT);
  }

  static List<Fixture> createCorpus(int positiveCount, int hardNegativeCount) {
    if (positiveCount < 1 || hardNegativeCount < 0) {
      throw new IllegalArgumentException(
          "corpus requires a positive record and non-negative negatives");
    }
    SplittableRandom random = new SplittableRandom(CORPUS_SEED);
    Set<String> uniqueValues = new HashSet<>();
    List<Fixture> fixtures = new ArrayList<>(positiveCount + hardNegativeCount);
    while (fixtures.size() < positiveCount) {
      String generated = randomValue(random);
      if (uniqueValues.add(generated)) {
        fixtures.add(new Fixture("entity-%04d".formatted(fixtures.size()), generated));
      }
    }
    for (int index = 0; index < hardNegativeCount; index++) {
      String prefix = fixtures.get(index % positiveCount).value().substring(0, 8);
      String generated = prefix + randomValue(random).substring(8);
      if (uniqueValues.add(generated)) {
        fixtures.add(new Fixture("hard-negative-%04d".formatted(index), generated));
      }
    }
    return List.copyOf(fixtures);
  }

  private static String randomValue(SplittableRandom random) {
    StringBuilder value = new StringBuilder(18);
    for (int index = 0; index < 18; index++) {
      value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return value.toString();
  }

  static List<QueryCase> createQueries(List<Fixture> corpus) {
    return createQueries(corpus, Math.min(QUERY_COUNT, corpus.size()));
  }

  static List<QueryCase> createQueries(List<Fixture> corpus, int queryCount) {
    if (queryCount < 1 || queryCount > corpus.size()) {
      throw new IllegalArgumentException("query count must be between 1 and corpus size");
    }
    List<QueryCase> queries = new ArrayList<>(queryCount);
    for (int index = 0; index < queryCount; index++) {
      Fixture fixture = corpus.get(index);
      char original = fixture.value().charAt(8);
      char replacement = original == 'a' ? 'b' : 'a';
      String edited = fixture.value().substring(0, 8) + replacement + fixture.value().substring(9);
      queries.add(new QueryCase(fixture.id(), edited));
    }
    return List.copyOf(queries);
  }

  private static List<ProtectedToken> createTokens(
      ScopedKeyDeriver keyDeriver,
      ExactBlindIndexer exactIndexer,
      ProtectedNgramStrategy bigrams,
      ProtectedNgramStrategy trigrams,
      CanonicalTextNormalizer normalizer,
      String value)
      throws Exception {
    String canonical = normalizer.normalize(value);
    byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
    SecretKey indexKey = keyDeriver.deriveIndexKey(KEY_ID, TENANT_ID, FIELD_ID);
    List<ProtectedToken> tokens = new ArrayList<>();
    tokens.add(
        new ProtectedToken(
            "efs-exact",
            exactIndexer.formatVersion(),
            exactIndexer.createToken(indexKey, canonicalBytes)));
    tokens.addAll(bigrams.createProtectedTokens(indexKey, canonical));
    tokens.addAll(trigrams.createProtectedTokens(indexKey, canonical));
    Arrays.fill(canonicalBytes, (byte) 0);
    return List.copyOf(tokens);
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
    List<Long> sorted = samples.stream().sorted().toList();
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, index)) / 1_000_000.0;
  }

  record Fixture(String id, String value) {}

  record QueryCase(String expectedId, String value) {}
}
