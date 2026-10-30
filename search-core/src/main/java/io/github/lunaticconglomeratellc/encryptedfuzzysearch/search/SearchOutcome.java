package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.List;
import java.util.Objects;

/** Verified search matches and explicit candidate retrieval completeness metadata. */
public record SearchOutcome(
    List<FuzzySearchResult> matches,
    SearchAccuracyContract accuracyContract,
    CandidateCompleteness candidateCompleteness,
    boolean candidateLimitExceeded,
    int candidatesVerified,
    List<String> strategyIds) {
  public SearchOutcome {
    matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
    Objects.requireNonNull(accuracyContract, "accuracyContract");
    Objects.requireNonNull(candidateCompleteness, "candidateCompleteness");
    strategyIds = List.copyOf(Objects.requireNonNull(strategyIds, "strategyIds"));
    if (candidatesVerified < 0) {
      throw new IllegalArgumentException("candidatesVerified must not be negative");
    }
    if (strategyIds.isEmpty()) {
      throw new IllegalArgumentException("strategyIds must not be empty");
    }
  }

  /**
   * True only when the selected strategy proves the request and the repository cap did not cut it.
   */
  public boolean candidateRetrievalComplete() {
    return candidateCompleteness.level() != CandidateCompleteness.Level.BEST_EFFORT
        && !candidateLimitExceeded;
  }
}
