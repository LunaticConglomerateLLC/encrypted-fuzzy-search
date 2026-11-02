package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Built-in scoring over decrypted, caller-authorized candidate text. */
public sealed interface FinalScorer
    permits CodePointLevenshteinScorer,
        DamerauLevenshteinScorer,
        WeightedEditScorer,
        WeightedNgramScorer,
        TokenSetScorer,
        CandidateLocalBm25Scorer,
        SoftTfIdfScorer {
  /** Stable profile ID for diagnostics and explicit policy allowlisting. */
  String id();

  /** Scores one pair as a normalized value in [0, 1]. */
  double score(String query, String candidate);

  /** Scores candidates against a set of query forms; rankers may use only this supplied set. */
  default List<Double> scoreCandidates(List<String> queryForms, List<List<String>> candidateForms) {
    Objects.requireNonNull(queryForms, "queryForms");
    Objects.requireNonNull(candidateForms, "candidateForms");
    if (queryForms.isEmpty()) {
      throw new IllegalArgumentException("at least one query form is required");
    }
    List<Double> scores = new ArrayList<>(candidateForms.size());
    for (List<String> candidate : candidateForms) {
      Objects.requireNonNull(candidate, "candidate forms");
      if (candidate.isEmpty()) {
        throw new IllegalArgumentException("at least one candidate form is required");
      }
      double maximum = 0.0;
      for (String query : queryForms) {
        for (String value : candidate) {
          maximum = Math.max(score(query, value), maximum);
        }
      }
      scores.add(requireScore(maximum));
    }
    return List.copyOf(scores);
  }

  static double requireScore(double score) {
    if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
      throw new IllegalArgumentException("scorer must return a finite score in [0, 1]");
    }
    return score;
  }
}
