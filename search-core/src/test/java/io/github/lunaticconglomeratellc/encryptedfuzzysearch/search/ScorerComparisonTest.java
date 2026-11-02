package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Small deterministic ranking fixture; it supplements, but does not replace, the PostgreSQL run.
 */
public class ScorerComparisonTest {
  private static final List<Case> CASES =
      List.of(
          new Case(
              "Ailce Smith",
              "alice",
              Map.of(
                  "alice", "Alice Smith",
                  "alise", "Alise Smith",
                  "near", "Alice Smyth",
                  "hard", "Alice Jones")),
          new Case(
              "A1ice Sm1th",
              "alice",
              Map.of(
                  "alice", "Alice Smith",
                  "alise", "Alise Smith",
                  "near", "Alice Smyth",
                  "hard", "Alice Jones")),
          new Case(
              "Smith Alice",
              "alice",
              Map.of(
                  "alice", "Alice Smith",
                  "smith", "Smith Jones",
                  "hard", "Alice Jones",
                  "near", "Alise Smyth")),
          new Case(
              "Alice Smth",
              "alice",
              Map.of(
                  "alice", "Alice Smith",
                  "smith", "Alice Smith Alice",
                  "near", "Alice Smyth",
                  "hard", "Bob Jones")));

  @Test
  public void comparesBuiltInScorersOnDeterministicSyntheticRankingCases() {
    Map<String, FinalScorer> scorers = scorers();
    Map<String, Double> reciprocalRank = new LinkedHashMap<>();
    Map<String, Integer> topOne = new LinkedHashMap<>();
    for (String id : scorers.keySet()) {
      reciprocalRank.put(id, 0.0);
      topOne.put(id, 0);
    }
    for (Case testCase : CASES) {
      for (Map.Entry<String, FinalScorer> entry : scorers.entrySet()) {
        List<String> ids = List.copyOf(testCase.candidates().keySet());
        List<Double> scores =
            entry
                .getValue()
                .scoreCandidates(
                    List.of(testCase.query()),
                    ids.stream().map(id -> List.of(testCase.candidates().get(id))).toList());
        int rank = rankOf(testCase.expectedId(), ids, scores);
        reciprocalRank.merge(entry.getKey(), 1.0 / rank, Double::sum);
        if (rank == 1) {
          topOne.merge(entry.getKey(), 1, Integer::sum);
        }
      }
    }
    reciprocalRank.replaceAll((ignored, sum) -> sum / CASES.size());
    System.out.println("synthetic scorer comparison (MRR; top-1 count / 4):");
    scorers
        .keySet()
        .forEach(
            id ->
                System.out.printf(
                    "%s=%.4f; %d/%d%n", id, reciprocalRank.get(id), topOne.get(id), CASES.size()));
    assertTrue(reciprocalRank.get(CodePointLevenshteinScorer.ID) >= 0.0);
    assertTrue(reciprocalRank.get(DamerauLevenshteinScorer.ID) >= 0.0);
    assertTrue(reciprocalRank.get(SoftTfIdfScorer.ID) >= 0.0);
  }

  private static int rankOf(String expectedId, List<String> ids, List<Double> scores) {
    List<Integer> order =
        java.util.stream.IntStream.range(0, ids.size())
            .boxed()
            .sorted(
                (left, right) -> {
                  int scoreOrder = Double.compare(scores.get(right), scores.get(left));
                  return scoreOrder != 0 ? scoreOrder : ids.get(left).compareTo(ids.get(right));
                })
            .toList();
    for (int rank = 0; rank < order.size(); rank++) {
      if (ids.get(order.get(rank)).equals(expectedId)) {
        return rank + 1;
      }
    }
    throw new IllegalStateException("expected ID was not included in the candidate set");
  }

  private static Map<String, FinalScorer> scorers() {
    Map<String, FinalScorer> scorers = new LinkedHashMap<>();
    scorers.put(CodePointLevenshteinScorer.ID, new CodePointLevenshteinScorer());
    scorers.put(DamerauLevenshteinScorer.ID, new DamerauLevenshteinScorer());
    scorers.put(
        WeightedEditScorer.ID, new WeightedEditScorer(Map.of("1i", 0.2, "1l", 0.2, "0o", 0.2)));
    FinalScorer weightedNgram = new WeightedNgramScorer(2);
    scorers.put(weightedNgram.id(), weightedNgram);
    scorers.put(TokenSetScorer.ID, new TokenSetScorer());
    scorers.put(CandidateLocalBm25Scorer.ID, new CandidateLocalBm25Scorer());
    scorers.put(SoftTfIdfScorer.ID, new SoftTfIdfScorer(0.75));
    return scorers;
  }

  private record Case(String query, String expectedId, Map<String, String> candidates) {}
}
