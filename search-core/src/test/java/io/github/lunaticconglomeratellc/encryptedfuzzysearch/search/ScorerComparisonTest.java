package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

/** Deterministic scorer comparisons supplementing, but not replacing, the PostgreSQL benchmark. */
public class ScorerComparisonTest {
  private static final SecretKey SYNTHETIC_INDEX_KEY = syntheticIndexKey();
  private static final ProtectedNgramStrategy BIGRAMS =
      new ProtectedNgramStrategy(2, new NgramBlindIndexer());
  private static final ProtectedNgramStrategy TRIGRAMS =
      new ProtectedNgramStrategy(3, new NgramBlindIndexer());
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

  @Test
  public void evaluatesStratifiedScorersAndCalibrationOnEntityDisjointSplits()
      throws GeneralSecurityException {
    List<EvaluationCase> tuning = evaluationCases(0, 240);
    List<EvaluationCase> heldOut = evaluationCases(240, 480);
    Set<String> tuningEntities =
        tuning.stream().map(EvaluationCase::entityId).collect(java.util.stream.Collectors.toSet());
    Set<String> heldOutEntities =
        heldOut.stream().map(EvaluationCase::entityId).collect(java.util.stream.Collectors.toSet());
    Set<String> overlap = new HashSet<>(tuningEntities);
    overlap.retainAll(heldOutEntities);
    assertTrue(overlap.isEmpty(), "tuning and held-out splits must be entity-disjoint");
    assertTrue(tuningEntities.size() == 240 && heldOutEntities.size() == 240);
    Set<String> tuningValues = candidateValues(tuning);
    Set<String> heldOutValues = candidateValues(heldOut);
    tuningValues.retainAll(heldOutValues);
    assertTrue(tuningValues.isEmpty(), "tuning and held-out records must be disjoint");
    for (Stratum stratum : Stratum.values()) {
      assertTrue(tuning.stream().filter(testCase -> testCase.stratum() == stratum).count() == 40);
      assertTrue(heldOut.stream().filter(testCase -> testCase.stratum() == stratum).count() == 40);
    }

    Map<String, FinalScorer> scorers = scorers();
    System.out.println("entity-disjoint synthetic scorer evaluation");
    System.out.println("split=tuning:240_entities,held_out:240_entities;candidate_limit=100");
    System.out.println(
        "stratum,scorer,queries,mean_candidates,candidate_recall,candidate_precision,threshold,final_recall,final_precision,mrr,top1");

    for (Map.Entry<String, FinalScorer> entry : scorers.entrySet()) {
      String scorerId = entry.getKey();
      FinalScorer scorer = entry.getValue();
      double threshold = selectThreshold(scorer, tuning);
      Map<Stratum, RankingMetrics> metrics = evaluateByStratum(scorer, heldOut, threshold);
      for (Stratum stratum : Stratum.values()) {
        RankingMetrics result = metrics.get(stratum);
        System.out.printf(
            "%s,%s,%d,%.2f,%.4f,%s,%.4f,%.4f,%s,%.4f,%d/%d%n",
            stratum.id,
            scorerId,
            result.queries,
            result.meanCandidates(),
            result.candidateRecall(),
            result.candidatePrecisionText(),
            threshold,
            result.recall(),
            result.precisionText(),
            result.mrr(),
            result.topOne,
            result.queries);
      }

      List<Double> trainingScores = new ArrayList<>();
      List<Boolean> trainingLabels = new ArrayList<>();
      for (EvaluationCase evaluationCase : tuning) {
        addLabeledScores(scorer, evaluationCase, trainingScores, trainingLabels);
      }
      List<Double> heldOutScores = new ArrayList<>();
      List<Boolean> heldOutLabels = new ArrayList<>();
      for (EvaluationCase evaluationCase : heldOut) {
        addLabeledScores(scorer, evaluationCase, heldOutScores, heldOutLabels);
      }
      IsotonicScoreCalibrator calibrator =
          IsotonicScoreCalibrator.fit("synthetic-" + scorerId, trainingScores, trainingLabels);
      ScoreCalibrationMetrics rawMetrics =
          ScoreCalibrationEvaluator.evaluate(heldOutScores, heldOutLabels, 10);
      ScoreCalibrationMetrics calibratedMetrics =
          ScoreCalibrationEvaluator.evaluate(calibrator, heldOutScores, heldOutLabels, 10);
      assertTrue(calibrator.sampleCount() == trainingScores.size());
      assertTrue(calibratedMetrics.sampleCount() == heldOutScores.size());
      System.out.printf(
          "calibration,%s,train_entities=%d,held_out_entities=%d,train_pairs=%d,held_out_pairs=%d,raw_brier=%.6f,raw_ece=%.6f,calibrated_brier=%.6f,calibrated_ece=%.6f%n",
          scorerId,
          tuningEntities.size(),
          heldOutEntities.size(),
          trainingScores.size(),
          heldOutScores.size(),
          rawMetrics.brierScore(),
          rawMetrics.expectedCalibrationError(),
          calibratedMetrics.brierScore(),
          calibratedMetrics.expectedCalibrationError());
    }
  }

  private static double selectThreshold(FinalScorer scorer, List<EvaluationCase> cases)
      throws GeneralSecurityException {
    List<Double> scores = new ArrayList<>();
    List<Boolean> labels = new ArrayList<>();
    for (EvaluationCase evaluationCase : cases) {
      addLabeledScores(scorer, evaluationCase, scores, labels);
    }
    List<Double> candidates = scores.stream().distinct().sorted().toList();
    double bestThreshold = 0.0;
    double bestF1 = -1.0;
    for (double threshold : candidates) {
      int truePositive = 0;
      int falsePositive = 0;
      int falseNegative = 0;
      for (int index = 0; index < scores.size(); index++) {
        boolean predicted = scores.get(index) >= threshold;
        boolean relevant = labels.get(index);
        if (predicted && relevant) {
          truePositive++;
        } else if (predicted) {
          falsePositive++;
        } else if (relevant) {
          falseNegative++;
        }
      }
      double precision =
          truePositive + falsePositive == 0
              ? 0.0
              : (double) truePositive / (truePositive + falsePositive);
      double recall = (double) truePositive / (truePositive + falseNegative);
      double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
      if (f1 > bestF1) {
        bestF1 = f1;
        bestThreshold = threshold;
      }
    }
    return bestThreshold;
  }

  private static Map<Stratum, RankingMetrics> evaluateByStratum(
      FinalScorer scorer, List<EvaluationCase> cases, double threshold)
      throws GeneralSecurityException {
    Map<Stratum, RankingMetrics> metrics = new EnumMap<>(Stratum.class);
    for (Stratum stratum : Stratum.values()) {
      metrics.put(stratum, new RankingMetrics());
    }
    for (EvaluationCase evaluationCase : cases) {
      RankingMetrics result = metrics.get(evaluationCase.stratum());
      result.queries++;
      CandidateSet candidateSet = retrieveCandidates(evaluationCase);
      List<String> candidateIds = candidateSet.ids();
      result.candidateCount += candidateIds.size();
      boolean targetRetrieved = candidateIds.contains(evaluationCase.expectedId());
      if (targetRetrieved) {
        result.candidateTruePositive++;
      }
      List<List<String>> candidateForms =
          candidateIds.stream().map(id -> List.of(evaluationCase.candidates().get(id))).toList();
      List<Double> scores = scorer.scoreCandidates(List.of(evaluationCase.query()), candidateForms);
      int rank =
          rankOf(evaluationCase.expectedId(), candidateIds, scores, candidateSet.overlapCounts());
      if (rank > 0) {
        result.reciprocalRankSum += 1.0 / rank;
      } else {
        result.falseNegative++;
      }
      if (rank == 1) {
        result.topOne++;
      }
      for (int index = 0; index < scores.size(); index++) {
        if (scores.get(index) >= threshold) {
          if (candidateIds.get(index).equals(evaluationCase.expectedId())) {
            result.truePositive++;
          } else {
            result.falsePositive++;
          }
        } else if (candidateIds.get(index).equals(evaluationCase.expectedId())) {
          result.falseNegative++;
        }
      }
    }
    for (Stratum stratum : Stratum.values()) {
      RankingMetrics result = metrics.get(stratum);
      assertTrue(result.queries == 40);
      assertTrue(result.candidateTruePositive == 40);
      assertTrue(result.candidateCount > 0 && result.candidateCount <= 4000);
    }
    return metrics;
  }

  private static void addLabeledScores(
      FinalScorer scorer, EvaluationCase evaluationCase, List<Double> scores, List<Boolean> labels)
      throws GeneralSecurityException {
    List<String> candidateIds = retrieveCandidates(evaluationCase).ids();
    List<Double> caseScores =
        scorer.scoreCandidates(
            List.of(evaluationCase.query()),
            candidateIds.stream().map(id -> List.of(evaluationCase.candidates().get(id))).toList());
    for (int index = 0; index < candidateIds.size(); index++) {
      scores.add(caseScores.get(index));
      labels.add(candidateIds.get(index).equals(evaluationCase.expectedId()));
    }
  }

  private static List<EvaluationCase> evaluationCases(int firstEntity, int endEntity) {
    List<EvaluationCase> cases = new ArrayList<>();
    for (int entity = firstEntity; entity < endEntity; entity++) {
      Stratum stratum = Stratum.values()[entity % Stratum.values().length];
      String firstName =
          switch (stratum) {
            case OCR, TOKEN_REORDER, TOKEN_DELETION -> "Alice";
            case UNICODE -> "Māria";
            default ->
                List.of("Maria", "Sofia", "Elena", "Nadia", "Marco", "David").get(entity % 6);
          };
      String surname = "Surname" + String.format("%04d", entity);
      String target = firstName + " " + surname;
      String query =
          switch (stratum) {
            case SUBSTITUTION -> firstName + " " + replaceAt(surname, 2, 'x');
            case TRANSPOSITION -> firstName + " " + swapAt(surname, 2, 3);
            case OCR -> "A1ice " + surname;
            case TOKEN_REORDER -> surname + " " + firstName;
            case TOKEN_DELETION -> firstName + " " + surname.substring(0, 3) + surname.substring(4);
            case UNICODE -> "Maria " + surname;
          };
      String nearMiss = firstName + " " + replaceAt(surname, 3, 'z');
      String sharedToken = firstName + " SurnameAlt" + String.format("%04d", entity);
      String unrelated = "Other PersonU" + String.format("%04d", entity);
      Map<String, String> candidates = new LinkedHashMap<>();
      candidates.put("target-" + entity, target);
      candidates.put("near-" + entity, nearMiss);
      candidates.put("shared-" + entity, sharedToken);
      candidates.put("unrelated-" + entity, unrelated);
      for (int distractor = 0; distractor < 96; distractor++) {
        String distractorId = "distractor-" + entity + "-" + distractor;
        candidates.put(
            distractorId, "Other PersonD" + String.format("%06d", entity * 96 + distractor));
      }
      cases.add(
          new EvaluationCase("entity-" + entity, stratum, query, "target-" + entity, candidates));
    }
    return List.copyOf(cases);
  }

  private static String replaceAt(String value, int index, char replacement) {
    char actualReplacement =
        value.charAt(index) == replacement ? (char) (replacement + 1) : replacement;
    return value.substring(0, index) + actualReplacement + value.substring(index + 1);
  }

  private static Set<String> candidateValues(List<EvaluationCase> cases) {
    Set<String> values = new HashSet<>();
    cases.forEach(evaluationCase -> values.addAll(evaluationCase.candidates().values()));
    return values;
  }

  private static CandidateSet retrieveCandidates(EvaluationCase evaluationCase)
      throws GeneralSecurityException {
    Set<ProtectedToken> queryTokens = new HashSet<>(protectedTokens(evaluationCase.query()));
    Map<String, Integer> overlapCounts = new LinkedHashMap<>();
    for (Map.Entry<String, String> candidate : evaluationCase.candidates().entrySet()) {
      int overlap = 0;
      for (ProtectedToken token : protectedTokens(candidate.getValue())) {
        if (queryTokens.contains(token)) {
          overlap++;
        }
      }
      if (overlap > 0) {
        overlapCounts.put(candidate.getKey(), overlap);
      }
    }
    List<String> candidateIds =
        overlapCounts.keySet().stream()
            .sorted(
                (left, right) -> {
                  int overlapOrder =
                      Integer.compare(overlapCounts.get(right), overlapCounts.get(left));
                  return overlapOrder != 0 ? overlapOrder : left.compareTo(right);
                })
            .limit(100)
            .toList();
    Map<String, Integer> returnedOverlaps = new LinkedHashMap<>();
    candidateIds.forEach(id -> returnedOverlaps.put(id, overlapCounts.get(id)));
    return new CandidateSet(candidateIds, returnedOverlaps);
  }

  private static List<ProtectedToken> protectedTokens(String value)
      throws GeneralSecurityException {
    List<ProtectedToken> tokens = new ArrayList<>();
    tokens.addAll(BIGRAMS.createProtectedTokens(SYNTHETIC_INDEX_KEY, value));
    tokens.addAll(TRIGRAMS.createProtectedTokens(SYNTHETIC_INDEX_KEY, value));
    return List.copyOf(tokens);
  }

  private static SecretKey syntheticIndexKey() {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 0x53);
    return new SecretKeySpec(key, "HmacSHA256");
  }

  private static int rankOf(
      String expectedId,
      List<String> ids,
      List<Double> scores,
      Map<String, Integer> overlapCounts) {
    List<Integer> order =
        java.util.stream.IntStream.range(0, ids.size())
            .boxed()
            .sorted(
                (left, right) -> {
                  int scoreOrder = Double.compare(scores.get(right), scores.get(left));
                  if (scoreOrder != 0) {
                    return scoreOrder;
                  }
                  int overlapOrder =
                      Integer.compare(
                          overlapCounts.get(ids.get(right)), overlapCounts.get(ids.get(left)));
                  return overlapOrder != 0 ? overlapOrder : ids.get(left).compareTo(ids.get(right));
                })
            .toList();
    for (int rank = 0; rank < order.size(); rank++) {
      if (ids.get(order.get(rank)).equals(expectedId)) {
        return rank + 1;
      }
    }
    return 0;
  }

  private static String swapAt(String value, int first, int second) {
    char[] chars = value.toCharArray();
    char held = chars[first];
    chars[first] = chars[second];
    chars[second] = held;
    return new String(chars);
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

  private enum Stratum {
    SUBSTITUTION("single_substitution"),
    TRANSPOSITION("adjacent_transposition"),
    OCR("ocr_confusion"),
    TOKEN_REORDER("token_reorder"),
    TOKEN_DELETION("token_deletion"),
    UNICODE("unicode_diacritic");

    private final String id;

    Stratum(String id) {
      this.id = id;
    }
  }

  private record EvaluationCase(
      String entityId,
      Stratum stratum,
      String query,
      String expectedId,
      Map<String, String> candidates) {}

  private record CandidateSet(List<String> ids, Map<String, Integer> overlapCounts) {}

  private static final class RankingMetrics {
    private int queries;
    private int truePositive;
    private int falsePositive;
    private int falseNegative;
    private int topOne;
    private int candidateTruePositive;
    private int candidateCount;
    private double reciprocalRankSum;

    private double recall() {
      return truePositive + falseNegative == 0
          ? 0.0
          : (double) truePositive / (truePositive + falseNegative);
    }

    private double precision() {
      return truePositive + falsePositive == 0
          ? Double.NaN
          : (double) truePositive / (truePositive + falsePositive);
    }

    private String precisionText() {
      return Double.isNaN(precision()) ? "undefined" : "%.4f".formatted(precision());
    }

    private double mrr() {
      return reciprocalRankSum / queries;
    }

    private double candidateRecall() {
      return (double) candidateTruePositive / queries;
    }

    private double candidatePrecision() {
      return candidateCount == 0 ? Double.NaN : (double) candidateTruePositive / candidateCount;
    }

    private double meanCandidates() {
      return (double) candidateCount / queries;
    }

    private String candidatePrecisionText() {
      return Double.isNaN(candidatePrecision())
          ? "undefined"
          : "%.4f".formatted(candidatePrecision());
    }
  }
}
