package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Optimal-string-alignment Damerau-Levenshtein similarity over Unicode code points. */
public final class DamerauLevenshteinScorer implements FinalScorer {
  public static final String ID = "damerau-levenshtein-osa-codepoint-v1";

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
      throw new IllegalArgumentException(
          "Damerau-Levenshtein input exceeds the 512-code-point limit");
    }
    if (b.length > a.length) {
      int[] swap = a;
      a = b;
      b = swap;
    }
    int maximumLength = Math.max(a.length, b.length);
    if (maximumLength == 0) {
      return 1.0;
    }
    int[] twoRowsBack = new int[b.length + 1];
    int[] previous = new int[b.length + 1];
    int[] current = new int[b.length + 1];
    for (int column = 0; column <= b.length; column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= a.length; row++) {
      current[0] = row;
      for (int column = 1; column <= b.length; column++) {
        int substitution = a[row - 1] == b[column - 1] ? 0 : 1;
        int best =
            Math.min(
                Math.min(previous[column] + 1, current[column - 1] + 1),
                previous[column - 1] + substitution);
        if (row > 1 && column > 1 && a[row - 1] == b[column - 2] && a[row - 2] == b[column - 1]) {
          best = Math.min(best, twoRowsBack[column - 2] + 1);
        }
        current[column] = best;
      }
      int[] swap = twoRowsBack;
      twoRowsBack = previous;
      previous = current;
      current = swap;
    }
    return FinalScorer.requireScore(1.0 - ((double) previous[b.length] / maximumLength));
  }
}
