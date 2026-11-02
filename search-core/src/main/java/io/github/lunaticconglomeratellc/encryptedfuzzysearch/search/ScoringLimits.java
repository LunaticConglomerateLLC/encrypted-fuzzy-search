package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Work bounds for opt-in candidate-local scoring profiles. */
public final class ScoringLimits {
  public static final int MAX_EDIT_CODE_POINTS = 512;
  public static final int MAX_CANDIDATE_SET_TOKENS = 100_000;
  public static final int MAX_SOFT_TFIDF_TOKEN_CODE_POINTS = 128;
  public static final long MAX_SOFT_TFIDF_COMPARISONS = 1_000_000;

  private ScoringLimits() {}
}
