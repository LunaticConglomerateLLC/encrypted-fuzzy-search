package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.CodePointLevenshteinScorer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Executes the benchmark-only raw-token control and checks it against the full EFS results. */
final class PlaintextBenchmarkRunner {
  private static final Comparator<ScoredCandidate> RESULT_ORDER =
      Comparator.comparingDouble(ScoredCandidate::score)
          .reversed()
          .thenComparing(Comparator.comparingInt(ScoredCandidate::overlapCount).reversed())
          .thenComparing(ScoredCandidate::recordId);
  private static final CanonicalTextNormalizer NORMALIZER = new CanonicalTextNormalizer();
  private static final CodePointLevenshteinScorer SCORER = new CodePointLevenshteinScorer();

  private PlaintextBenchmarkRunner() {}

  static void run(
      DataSource dataSource,
      List<FuzzySearchBenchmark.Fixture> corpus,
      List<FuzzySearchBenchmark.QueryCase> queryCases,
      List<List<String>> protectedCandidateIds,
      List<List<String>> protectedFinalIds,
      int protectedNoMatchFalsePositives)
      throws Exception {
    if (queryCases.size() != protectedCandidateIds.size()
        || queryCases.size() != protectedFinalIds.size()) {
      throw new IllegalArgumentException("paired benchmark query results must have equal sizes");
    }
    PlaintextBenchmarkStore store = new PlaintextBenchmarkStore(dataSource);
    store.initializeSchema();
    store.clear();
    CanonicalTextNormalizer normalizer = NORMALIZER;
    long logicalPostingBytes = 0L;
    long logicalPayloadBytes = 0L;
    long postingCount = 0L;
    Set<String> uniquePostings = new HashSet<>();
    Map<String, Integer> postingFrequency = new HashMap<>();

    long indexingStarted = System.nanoTime();
    for (FuzzySearchBenchmark.Fixture fixture : corpus) {
      String canonical = normalizer.normalize(fixture.value());
      store.upsert(
          FuzzySearchBenchmark.TENANT_ID,
          FuzzySearchBenchmark.FIELD_ID,
          fixture.id(),
          FuzzySearchBenchmark.KEY_ID,
          canonical);
      logicalPayloadBytes += canonical.getBytes(StandardCharsets.UTF_8).length;
      for (PlaintextBenchmarkStore.PlaintextToken token :
          PlaintextBenchmarkStore.createTokens(canonical)) {
        String identity = token.strategyId() + ':' + token.version() + ':' + token.value();
        uniquePostings.add(identity);
        postingFrequency.merge(identity, 1, Integer::sum);
        logicalPostingBytes += token.value().getBytes(StandardCharsets.UTF_8).length;
        postingCount++;
      }
    }
    long indexingElapsed = System.nanoTime() - indexingStarted;

    for (int index = 0; index < FuzzySearchBenchmark.WARMUP_QUERY_COUNT; index++) {
      runQuery(store, normalizer, queryCases.get(index).value());
    }

    List<Long> retrievalLatencies = new ArrayList<>(queryCases.size());
    List<Long> searchLatencies = new ArrayList<>(queryCases.size());
    int candidateTruePositives = 0;
    int totalCandidates = 0;
    int saturatedQueries = 0;
    int finalTruePositives = 0;
    int finalFalsePositives = 0;
    long fullSearchElapsed = 0L;

    for (int index = 0; index < queryCases.size(); index++) {
      FuzzySearchBenchmark.QueryCase query = queryCases.get(index);
      long searchStarted = System.nanoTime();
      String canonicalQuery = normalizer.normalize(query.value());
      List<PlaintextBenchmarkStore.PlaintextToken> queryTokens =
          PlaintextBenchmarkStore.createTokens(canonicalQuery);
      long retrievalStarted = System.nanoTime();
      List<PlaintextBenchmarkStore.PlaintextCandidate> candidates =
          store.findCandidates(
              FuzzySearchBenchmark.TENANT_ID,
              FuzzySearchBenchmark.FIELD_ID,
              FuzzySearchBenchmark.KEY_ID,
              queryTokens,
              FuzzySearchBenchmark.CANDIDATE_LIMIT);
      retrievalLatencies.add(System.nanoTime() - retrievalStarted);
      List<String> candidateIds =
          candidates.stream().map(PlaintextBenchmarkStore.PlaintextCandidate::recordId).toList();
      if (!candidateIds.equals(protectedCandidateIds.get(index))) {
        throw new IllegalStateException(
            "plaintext control candidate set differs from the paired EFS result");
      }
      totalCandidates += candidates.size();
      if (candidates.size() == FuzzySearchBenchmark.CANDIDATE_LIMIT) {
        saturatedQueries++;
      }
      if (candidateIds.contains(query.expectedId())) {
        candidateTruePositives++;
      }

      List<ScoredCandidate> accepted = score(canonicalQuery, candidates, normalizer);
      List<String> acceptedIds = accepted.stream().map(ScoredCandidate::recordId).toList();
      if (!acceptedIds.equals(protectedFinalIds.get(index))) {
        throw new IllegalStateException(
            "plaintext control final results differ from the paired EFS result");
      }
      if (acceptedIds.contains(query.expectedId())) {
        finalTruePositives++;
      }
      finalFalsePositives +=
          (int)
              acceptedIds.stream().filter(recordId -> !recordId.equals(query.expectedId())).count();
      long elapsed = System.nanoTime() - searchStarted;
      searchLatencies.add(elapsed);
      fullSearchElapsed += elapsed;
    }

    int noMatchFalsePositives = 0;
    for (int index = 0; index < FuzzySearchBenchmark.NO_MATCH_QUERY_COUNT; index++) {
      String canonicalQuery = normalizer.normalize("zzzzzzzzzz" + index);
      List<PlaintextBenchmarkStore.PlaintextToken> queryTokens =
          PlaintextBenchmarkStore.createTokens(canonicalQuery);
      List<PlaintextBenchmarkStore.PlaintextCandidate> candidates =
          store.findCandidates(
              FuzzySearchBenchmark.TENANT_ID,
              FuzzySearchBenchmark.FIELD_ID,
              FuzzySearchBenchmark.KEY_ID,
              queryTokens,
              FuzzySearchBenchmark.CANDIDATE_LIMIT);
      noMatchFalsePositives += score(canonicalQuery, candidates, normalizer).isEmpty() ? 0 : 1;
    }
    finalFalsePositives += noMatchFalsePositives;
    if (noMatchFalsePositives != protectedNoMatchFalsePositives) {
      throw new IllegalStateException("plaintext control no-match results differ from EFS");
    }

    long tokenRelationBytes =
        queryLong(
            dataSource,
            "SELECT pg_total_relation_size('" + PlaintextBenchmarkStore.TOKEN_TABLE + "')");
    long recordRelationBytes =
        queryLong(
            dataSource,
            "SELECT pg_total_relation_size('" + PlaintextBenchmarkStore.RECORD_TABLE + "')");
    String postgresVersion = queryString(dataSource, "SHOW server_version");
    int maximumPostingFrequency =
        postingFrequency.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    double averagePostingFrequency =
        postingFrequency.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);

    System.out.println("=== benchmark_mode=PLAINTEXT_CONTROL ===");
    System.out.println("storage=plaintext_payload_and_raw_ngrams; fixture=synthetic-only");
    System.out.println("postgres_version=" + postgresVersion);
    System.out.println("java_version=" + System.getProperty("java.version"));
    System.out.println("records=" + corpus.size() + ", typo_queries=" + queryCases.size());
    System.out.println(
        "candidate_limit="
            + FuzzySearchBenchmark.CANDIDATE_LIMIT
            + ", similarity_threshold="
            + FuzzySearchBenchmark.MINIMUM_SIMILARITY);
    System.out.printf(
        "index_records_per_second=%.2f%n", corpus.size() / (indexingElapsed / 1_000_000_000.0));
    System.out.printf(
        "candidate_recall=%.4f (%d/%d)%n",
        (double) candidateTruePositives / queryCases.size(),
        candidateTruePositives,
        queryCases.size());
    System.out.printf(
        "candidate_precision=%.4f (%d/%d candidates)%n",
        totalCandidates == 0 ? 0.0 : (double) candidateTruePositives / totalCandidates,
        candidateTruePositives,
        totalCandidates);
    System.out.printf(
        "final_recall=%.4f (%d/%d)%n",
        (double) finalTruePositives / queryCases.size(), finalTruePositives, queryCases.size());
    System.out.printf(
        "final_precision=%.4f%n",
        (double) finalTruePositives / (finalTruePositives + finalFalsePositives));
    System.out.printf(
        "no_match_false_positive_rate=%.4f (%d/%d)%n",
        (double) noMatchFalsePositives / FuzzySearchBenchmark.NO_MATCH_QUERY_COUNT,
        noMatchFalsePositives,
        FuzzySearchBenchmark.NO_MATCH_QUERY_COUNT);
    System.out.printf(
        "candidates_per_typo_query=%.2f%n", (double) totalCandidates / queryCases.size());
    System.out.printf(
        "candidate_limit_saturation_rate=%.4f (%d/%d)%n",
        (double) saturatedQueries / queryCases.size(), saturatedQueries, queryCases.size());
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
        "full_search_throughput_qps=%.2f%n",
        queryCases.size() / (fullSearchElapsed / 1_000_000_000.0));
    System.out.println(
        "token_postings=" + postingCount + ", unique_raw_tokens=" + uniquePostings.size());
    System.out.printf(
        "max_token_frequency=%d, mean_frequency_per_unique_token=%.3f%n",
        maximumPostingFrequency, averagePostingFrequency);
    System.out.println(
        "logical_token_bytes="
            + logicalPostingBytes
            + ", logical_plaintext_payload_bytes="
            + logicalPayloadBytes);
    System.out.printf(
        "logical_index_to_payload_ratio=%.3f%n",
        logicalPayloadBytes == 0 ? 0.0 : (double) logicalPostingBytes / logicalPayloadBytes);
    System.out.println(
        "postgres_token_relation_bytes="
            + tokenRelationBytes
            + ", postgres_record_relation_bytes="
            + recordRelationBytes);
    System.out.println("candidate_equivalence=pass; final_result_equivalence=pass");
    System.out.println(
        "measurement_mode=single-threaded local Docker database, 30 warm-up queries excluded from timed samples");
    System.out.println(
        "privacy=synthetic-only diagnostic control; raw values/tokens are stored in disposable local tables and never printed");
  }

  private static List<ScoredCandidate> runQuery(
      PlaintextBenchmarkStore store, CanonicalTextNormalizer normalizer, String query)
      throws Exception {
    String canonicalQuery = normalizer.normalize(query);
    List<PlaintextBenchmarkStore.PlaintextToken> queryTokens =
        PlaintextBenchmarkStore.createTokens(canonicalQuery);
    List<PlaintextBenchmarkStore.PlaintextCandidate> candidates =
        store.findCandidates(
            FuzzySearchBenchmark.TENANT_ID,
            FuzzySearchBenchmark.FIELD_ID,
            FuzzySearchBenchmark.KEY_ID,
            queryTokens,
            FuzzySearchBenchmark.CANDIDATE_LIMIT);
    return score(canonicalQuery, candidates, normalizer);
  }

  static List<ScoredCandidate> score(
      String canonicalQuery,
      List<PlaintextBenchmarkStore.PlaintextCandidate> candidates,
      CanonicalTextNormalizer normalizer) {
    List<ScoredCandidate> accepted = new ArrayList<>();
    for (PlaintextBenchmarkStore.PlaintextCandidate candidate : candidates) {
      String canonicalCandidate = normalizer.normalize(candidate.value());
      double similarity = SCORER.score(canonicalQuery, canonicalCandidate);
      if (similarity >= FuzzySearchBenchmark.MINIMUM_SIMILARITY) {
        accepted.add(
            new ScoredCandidate(candidate.recordId(), similarity, candidate.overlapCount()));
      }
    }
    accepted.sort(RESULT_ORDER);
    return List.copyOf(accepted);
  }

  private static long queryLong(DataSource dataSource, String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static String queryString(DataSource dataSource, String sql) throws Exception {
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

  record ScoredCandidate(String recordId, double score, int overlapCount) {}
}
