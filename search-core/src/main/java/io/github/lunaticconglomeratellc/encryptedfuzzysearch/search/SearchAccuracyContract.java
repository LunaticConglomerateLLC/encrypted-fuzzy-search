package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Objects;

/** Immutable requested accuracy semantics for one search. */
public record SearchAccuracyContract(SearchMode mode, int maximumEditDistance) {
  public SearchAccuracyContract {
    Objects.requireNonNull(mode, "mode");
    if (mode == SearchMode.BOUNDED_FUZZY && maximumEditDistance < 1) {
      throw new IllegalArgumentException("bounded fuzzy mode requires a positive edit distance");
    }
    if (mode != SearchMode.BOUNDED_FUZZY && maximumEditDistance != 0) {
      throw new IllegalArgumentException(
          "maximumEditDistance is only valid for bounded fuzzy mode");
    }
  }

  public static SearchAccuracyContract exact() {
    return new SearchAccuracyContract(SearchMode.EXACT, 0);
  }

  public static SearchAccuracyContract boundedFuzzy(int maximumEditDistance) {
    return new SearchAccuracyContract(SearchMode.BOUNDED_FUZZY, maximumEditDistance);
  }

  public static SearchAccuracyContract approximate() {
    return new SearchAccuracyContract(SearchMode.APPROXIMATE, 0);
  }
}
