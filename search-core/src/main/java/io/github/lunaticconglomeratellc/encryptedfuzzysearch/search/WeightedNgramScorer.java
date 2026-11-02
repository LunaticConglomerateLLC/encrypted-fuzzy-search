package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pair-local weighted Jaccard over Unicode character n-gram multisets. */
public final class WeightedNgramScorer implements FinalScorer {
  public static final String ID_PREFIX = "weighted-ngram-jaccard-v1-n";
  private final int ngramSize;

  public WeightedNgramScorer(int ngramSize) {
    if (ngramSize < 1 || ngramSize > 8) {
      throw new IllegalArgumentException("ngramSize must be between 1 and 8");
    }
    this.ngramSize = ngramSize;
  }

  @Override
  public String id() {
    return ID_PREFIX + ngramSize;
  }

  @Override
  public double score(String left, String right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    Map<String, Integer> leftCounts = SimilarityTokens.frequencies(left, ngramSize);
    Map<String, Integer> rightCounts = SimilarityTokens.frequencies(right, ngramSize);
    if (leftCounts.isEmpty() && rightCounts.isEmpty()) {
      return 1.0;
    }
    Set<String> grams = new TreeSet<>(leftCounts.keySet());
    grams.addAll(rightCounts.keySet());
    double intersection = 0.0;
    double union = 0.0;
    for (String gram : grams) {
      int leftFrequency = leftCounts.getOrDefault(gram, 0);
      int rightFrequency = rightCounts.getOrDefault(gram, 0);
      int maximum = Math.max(leftFrequency, rightFrequency);
      double pairLocalWeight = 1.0 / Math.sqrt(maximum);
      intersection += Math.min(leftFrequency, rightFrequency) * pairLocalWeight;
      union += maximum * pairLocalWeight;
    }
    return FinalScorer.requireScore(intersection / union);
  }
}
