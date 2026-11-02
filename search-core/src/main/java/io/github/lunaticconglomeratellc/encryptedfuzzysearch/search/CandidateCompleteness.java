package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

/** Proven candidate-generation guarantee supplied by a protected-index strategy. */
public record CandidateCompleteness(Level level, int maximumEditDistance) {
  public enum Level {
    /** Candidate generation is complete for canonical equality. */
    EXACT,
    /** Candidate generation is complete for values within the stated edit-distance bound. */
    BOUNDED_EDIT_DISTANCE,
    /** Candidate generation is heuristic and has no completeness guarantee. */
    BEST_EFFORT
  }

  public CandidateCompleteness {
    if (level == null) {
      throw new NullPointerException("level");
    }
    if (level == Level.BOUNDED_EDIT_DISTANCE && maximumEditDistance < 1) {
      throw new IllegalArgumentException("bounded completeness requires a positive edit distance");
    }
    if (level != Level.BOUNDED_EDIT_DISTANCE && maximumEditDistance != 0) {
      throw new IllegalArgumentException(
          "maximumEditDistance is only valid for bounded completeness");
    }
  }

  public static CandidateCompleteness exact() {
    return new CandidateCompleteness(Level.EXACT, 0);
  }

  public static CandidateCompleteness boundedEditDistance(int maximumEditDistance) {
    return new CandidateCompleteness(Level.BOUNDED_EDIT_DISTANCE, maximumEditDistance);
  }

  public static CandidateCompleteness bestEffort() {
    return new CandidateCompleteness(Level.BEST_EFFORT, 0);
  }

  public boolean supports(SearchAccuracyContract contract) {
    return switch (contract.mode()) {
      case EXACT -> level == Level.EXACT;
      case BOUNDED_FUZZY ->
          level == Level.BOUNDED_EDIT_DISTANCE
              && maximumEditDistance >= contract.maximumEditDistance();
      case APPROXIMATE -> true;
    };
  }
}
