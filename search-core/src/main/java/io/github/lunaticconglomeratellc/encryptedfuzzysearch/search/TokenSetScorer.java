package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Jaccard similarity over Unicode letter/digit tokens, with case folded using ROOT locale. */
public final class TokenSetScorer implements FinalScorer {
  public static final String ID = "token-set-jaccard-v1";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public double score(String left, String right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    Set<String> leftTokens = new HashSet<>(SimilarityTokens.words(left));
    Set<String> rightTokens = new HashSet<>(SimilarityTokens.words(right));
    if (leftTokens.isEmpty() && rightTokens.isEmpty()) {
      return 1.0;
    }
    Set<String> intersection = new HashSet<>(leftTokens);
    intersection.retainAll(rightTokens);
    Set<String> union = new HashSet<>(leftTokens);
    union.addAll(rightTokens);
    return (double) intersection.size() / union.size();
  }
}
