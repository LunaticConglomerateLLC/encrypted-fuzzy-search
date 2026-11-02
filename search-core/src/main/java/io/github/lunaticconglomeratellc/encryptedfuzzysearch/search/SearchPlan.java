package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.List;
import java.util.Objects;

/** Validated strategy selection and candidate-completeness claim for a request. */
public record SearchPlan(
    SearchAccuracyContract accuracyContract,
    List<ProtectedIndexStrategy> strategies,
    CandidateCompleteness candidateCompleteness) {
  public SearchPlan {
    Objects.requireNonNull(accuracyContract, "accuracyContract");
    strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
    Objects.requireNonNull(candidateCompleteness, "candidateCompleteness");
    if (strategies.isEmpty()) {
      throw new IllegalArgumentException("a search plan must select at least one strategy");
    }
  }

  public List<String> strategyIds() {
    return strategies.stream().map(strategy -> strategy.strategyId(null)).toList();
  }
}
