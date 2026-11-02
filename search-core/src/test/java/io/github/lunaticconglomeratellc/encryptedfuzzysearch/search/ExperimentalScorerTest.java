package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

public class ExperimentalScorerTest {
  @Test
  public void damerauLevenshteinRecognizesAdjacentTranspositionByCodePoint() {
    DamerauLevenshteinScorer scorer = new DamerauLevenshteinScorer();
    assertTrue(
        scorer.score("smtih", "smith") > new CodePointLevenshteinScorer().score("smtih", "smith"));
    assertEquals(scorer.score("a😀", "😀a"), 0.5);
    assertEquals(scorer.score("", ""), 1.0);
  }

  @Test
  public void weightedEditUsesOnlyExplicitDirectedConfusionCosts() {
    WeightedEditScorer scorer = new WeightedEditScorer(Map.of("O0", 0.2));
    assertEquals(scorer.score("ROB", "R0B"), 1.0 - 0.2 / 3.0, 0.000001);
    assertEquals(scorer.score("R0B", "ROB"), 2.0 / 3.0, 0.000001);
    assertEquals(new WeightedEditScorer(Map.of()).score("a", "b"), 0.0);
  }

  @Test
  public void weightedEditRejectsInvalidConfiguration() {
    assertThrows(NullPointerException.class, () -> new WeightedEditScorer(null));
    assertThrows(IllegalArgumentException.class, () -> new WeightedEditScorer(Map.of("x", 0.2)));
    assertThrows(IllegalArgumentException.class, () -> new WeightedEditScorer(Map.of("aa", 0.2)));
    assertThrows(IllegalArgumentException.class, () -> new WeightedEditScorer(Map.of("ab", 1.1)));
    assertThrows(NullPointerException.class, () -> new WeightedEditScorer(Map.of("ab", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WeightedEditScorer(Map.of("a".repeat(513) + "b", 0.2)));
  }

  @Test
  public void editDistanceProfilesEnforceTheirInputBound() {
    String tooLong = "a".repeat(ScoringLimits.MAX_EDIT_CODE_POINTS + 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new DamerauLevenshteinScorer().score(tooLong, tooLong));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WeightedEditScorer(Map.of()).score(tooLong, tooLong));
    assertEquals(
        new DamerauLevenshteinScorer()
            .score("a".repeat(ScoringLimits.MAX_EDIT_CODE_POINTS), "a".repeat(512)),
        1.0);
  }

  @Test
  public void weightedNgramAndTokenSetMeasureDifferentTextFeatures() {
    WeightedNgramScorer ngram = new WeightedNgramScorer(2);
    assertTrue(ngram.score("banana", "banaba") > 0.0);
    assertEquals(ngram.score("😀", "😀"), 1.0);
    assertEquals(ngram.score("", ""), 1.0);
    assertThrows(IllegalArgumentException.class, () -> new WeightedNgramScorer(0));
    assertThrows(IllegalArgumentException.class, () -> new WeightedNgramScorer(9));

    TokenSetScorer tokenSet = new TokenSetScorer();
    assertEquals(tokenSet.score("Smith, Alice", "alice smith"), 1.0);
    assertEquals(tokenSet.score("red blue", "red green"), 1.0 / 3.0);
    assertEquals(tokenSet.score("!!!", ""), 1.0);
  }

  @Test
  public void candidateLocalBm25RanksCandidateSetUsingLocalTermFrequencyOnly() {
    CandidateLocalBm25Scorer scorer = new CandidateLocalBm25Scorer();
    List<Double> scores =
        scorer.scoreCandidates(
            List.of("red fox"),
            List.of(List.of("red fox"), List.of("red fox red fox red fox"), List.of("blue whale")));
    assertEquals(scores.size(), 3);
    assertTrue(scores.get(0) > 0.0);
    assertEquals(scores.get(1), 1.0);
    assertEquals(scores.get(2), 0.0);
    assertEquals(scorer.scoreCandidates(List.of(""), List.of(List.of(""))), List.of(1.0));
    assertTrue(scorer.scoreCandidates(List.of("x"), List.of()).isEmpty());
  }

  @Test
  public void softTfIdfSupportsFuzzyOneToOneTokenMatchesWithLocalStatistics() {
    SoftTfIdfScorer scorer = new SoftTfIdfScorer(0.75);
    List<Double> scores =
        scorer.scoreCandidates(
            List.of("smith alice"),
            List.of(
                List.of("smyth alice"), List.of("smith alice smith alice"), List.of("jones bob")));
    assertTrue(scores.get(0) > 0.0);
    assertEquals(scores.get(1), 1.0);
    assertEquals(scores.get(2), 0.0);
    assertEquals(scorer.score("", ""), 1.0);
    assertThrows(IllegalArgumentException.class, () -> new SoftTfIdfScorer(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new SoftTfIdfScorer(1.1));
  }

  @Test
  public void candidateLocalRankersEnforceTokenAndComparisonBudgets() {
    String tooManyTokens = "x ".repeat(ScoringLimits.MAX_CANDIDATE_SET_TOKENS + 1);
    CandidateLocalBm25Scorer bm25 = new CandidateLocalBm25Scorer();
    assertThrows(
        IllegalArgumentException.class,
        () -> bm25.scoreCandidates(List.of(tooManyTokens), List.of(List.of("x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> bm25.scoreCandidates(List.of("x"), List.of(List.of(tooManyTokens))));

    SoftTfIdfScorer softTfIdf = new SoftTfIdfScorer();
    assertThrows(
        IllegalArgumentException.class,
        () -> softTfIdf.scoreCandidates(List.of("a".repeat(129)), List.of(List.of("b"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> softTfIdf.scoreCandidates(List.of(tooManyTokens), List.of(List.of("x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> softTfIdf.scoreCandidates(List.of("x"), List.of(List.of(tooManyTokens))));

    List<String> queryTokens = new ArrayList<>();
    for (int index = 0; index < 11; index++) {
      queryTokens.add("q" + index);
    }
    String candidateText =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> "c" + index)
            .collect(java.util.stream.Collectors.joining(" "));
    List<List<String>> candidateForms = new ArrayList<>();
    for (int index = 0; index < 1000; index++) {
      candidateForms.add(List.of(candidateText));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> softTfIdf.scoreCandidates(List.of(String.join(" ", queryTokens)), candidateForms));
  }

  @Test
  public void scorersRejectInvalidCandidateFormSetsAndScores() {
    FinalScorer scorer = new TokenSetScorer();
    assertThrows(
        IllegalArgumentException.class,
        () -> scorer.scoreCandidates(List.of(), List.of(List.of("x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> scorer.scoreCandidates(List.of("x"), List.of(List.of())));
    assertThrows(NullPointerException.class, () -> scorer.scoreCandidates(null, List.of()));
    assertThrows(NullPointerException.class, () -> scorer.scoreCandidates(List.of("x"), null));
    assertThrows(IllegalArgumentException.class, () -> FinalScorer.requireScore(-0.1));
    assertThrows(IllegalArgumentException.class, () -> FinalScorer.requireScore(1.1));
    assertThrows(IllegalArgumentException.class, () -> FinalScorer.requireScore(Double.NaN));
    assertEquals(scorer.scoreCandidates(List.of("A", "a"), List.of(List.of("a"))), List.of(1.0));
  }
}
