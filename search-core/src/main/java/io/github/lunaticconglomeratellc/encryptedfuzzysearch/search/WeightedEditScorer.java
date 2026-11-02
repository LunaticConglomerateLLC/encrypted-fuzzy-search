package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Levenshtein scoring with caller-supplied, bounded substitution costs. */
public final class WeightedEditScorer implements FinalScorer {
  public static final String ID = "weighted-edit-codepoint-v1";
  private final Map<Long, Double> substitutionCosts;

  /**
   * @param substitutionCosts map keyed by two Unicode code points in order, for example {@code "O0"
   *     -> 0.2}; unspecified substitutions cost 1.0
   */
  public WeightedEditScorer(Map<String, Double> substitutionCosts) {
    Objects.requireNonNull(substitutionCosts, "substitutionCosts");
    Map<Long, Double> copy = new HashMap<>();
    substitutionCosts.forEach(
        (pair, cost) -> {
          Objects.requireNonNull(pair, "substitution pair");
          Objects.requireNonNull(cost, "substitution cost");
          int[] points = pair.codePoints().toArray();
          if (points.length != 2 || points[0] == points[1]) {
            throw new IllegalArgumentException(
                "substitution keys must contain two distinct code points");
          }
          if (!Double.isFinite(cost) || cost < 0.0 || cost > 1.0) {
            throw new IllegalArgumentException("substitution costs must be finite and in [0, 1]");
          }
          copy.put(pairKey(points[0], points[1]), cost);
        });
    this.substitutionCosts = Map.copyOf(copy);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public double score(String left, String right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    int[] a = left.codePoints().toArray();
    int[] b = right.codePoints().toArray();
    if (a.length > ScoringLimits.MAX_EDIT_CODE_POINTS
        || b.length > ScoringLimits.MAX_EDIT_CODE_POINTS) {
      throw new IllegalArgumentException("weighted edit input exceeds the 512-code-point limit");
    }
    int maximumLength = Math.max(a.length, b.length);
    if (maximumLength == 0) {
      return 1.0;
    }
    double[] previous = new double[b.length + 1];
    double[] current = new double[b.length + 1];
    for (int column = 0; column <= b.length; column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= a.length; row++) {
      current[0] = row;
      for (int column = 1; column <= b.length; column++) {
        double substitution =
            a[row - 1] == b[column - 1]
                ? 0.0
                : substitutionCosts.getOrDefault(pairKey(a[row - 1], b[column - 1]), 1.0);
        current[column] =
            Math.min(
                Math.min(current[column - 1] + 1.0, previous[column] + 1.0),
                previous[column - 1] + substitution);
      }
      double[] swap = previous;
      previous = current;
      current = swap;
    }
    return FinalScorer.requireScore(1.0 - (previous[b.length] / maximumLength));
  }

  private static long pairKey(int first, int second) {
    return ((long) first << 32) | (second & 0xffffffffL);
  }
}
