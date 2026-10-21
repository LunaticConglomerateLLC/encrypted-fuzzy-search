package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Computes case-sensitive normalized Levenshtein similarity over Unicode code points. */
public final class CodePointLevenshteinScorer {
  /** Returns 1 for identical strings and 0 when every code point in the longer string differs. */
  public double score(String left, String right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    int[] leftPoints = left.codePoints().toArray();
    int[] rightPoints = right.codePoints().toArray();
    int maximumLength = Math.max(leftPoints.length, rightPoints.length);
    if (maximumLength == 0) {
      return 1.0;
    }

    int[] previous = new int[rightPoints.length + 1];
    int[] current = new int[rightPoints.length + 1];
    for (int column = 0; column <= rightPoints.length; column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= leftPoints.length; row++) {
      current[0] = row;
      for (int column = 1; column <= rightPoints.length; column++) {
        int substitutionCost = leftPoints[row - 1] == rightPoints[column - 1] ? 0 : 1;
        current[column] =
            Math.min(
                Math.min(current[column - 1] + 1, previous[column] + 1),
                previous[column - 1] + substitutionCost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return 1.0 - ((double) previous[rightPoints.length] / maximumLength);
  }
}
