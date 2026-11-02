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
    List<String> strategyIds,
    List<ExpansionProvenance> expansions,
    String scoringProfileId,
    java.util.Optional<String> calibrationModelId) {
  public SearchOutcome {
    matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
    Objects.requireNonNull(accuracyContract, "accuracyContract");
    Objects.requireNonNull(candidateCompleteness, "candidateCompleteness");
    strategyIds = List.copyOf(Objects.requireNonNull(strategyIds, "strategyIds"));
    expansions = List.copyOf(Objects.requireNonNull(expansions, "expansions"));
    Objects.requireNonNull(scoringProfileId, "scoringProfileId");
    Objects.requireNonNull(calibrationModelId, "calibrationModelId");
    if (scoringProfileId.isBlank()) {
      throw new IllegalArgumentException("scoringProfileId must not be blank");
    }
    calibrationModelId.ifPresent(
        id -> {
          if (id.isBlank()) {
            throw new IllegalArgumentException("calibration model ID must not be blank");
          }
        });
    if (candidatesVerified < 0) {
      throw new IllegalArgumentException("candidatesVerified must not be negative");
    }
    if (strategyIds.isEmpty()) {
      throw new IllegalArgumentException("strategyIds must not be empty");
    }
  }

  public SearchOutcome(
      List<FuzzySearchResult> matches,
      SearchAccuracyContract accuracyContract,
      CandidateCompleteness candidateCompleteness,
      boolean candidateLimitExceeded,
      int candidatesVerified,
      List<String> strategyIds) {
    this(
        matches,
        accuracyContract,
        candidateCompleteness,
        candidateLimitExceeded,
        candidatesVerified,
        strategyIds,
        List.of(),
        CodePointLevenshteinScorer.ID,
        java.util.Optional.empty());
  }

  public SearchOutcome(
      List<FuzzySearchResult> matches,
      SearchAccuracyContract accuracyContract,
      CandidateCompleteness candidateCompleteness,
      boolean candidateLimitExceeded,
      int candidatesVerified,
      List<String> strategyIds,
      List<ExpansionProvenance> expansions) {
    this(
        matches,
        accuracyContract,
        candidateCompleteness,
        candidateLimitExceeded,
        candidatesVerified,
        strategyIds,
        expansions,
        CodePointLevenshteinScorer.ID,
        java.util.Optional.empty());
  }

  public SearchOutcome(
      List<FuzzySearchResult> matches,
      SearchAccuracyContract accuracyContract,
      CandidateCompleteness candidateCompleteness,
      boolean candidateLimitExceeded,
      int candidatesVerified,
      List<String> strategyIds,
      List<ExpansionProvenance> expansions,
      String scoringProfileId) {
    this(
        matches,
        accuracyContract,
        candidateCompleteness,
        candidateLimitExceeded,
        candidatesVerified,
        strategyIds,
        expansions,
        scoringProfileId,
        java.util.Optional.empty());
  }

  public boolean calibrated() {
    return calibrationModelId.isPresent();
  }

  /**
   * True only when the selected strategy proves the request and the repository cap did not cut it.
   */
  public boolean candidateRetrievalComplete() {
    return candidateCompleteness.level() != CandidateCompleteness.Level.BEST_EFFORT
        && !candidateLimitExceeded;
  }
}
