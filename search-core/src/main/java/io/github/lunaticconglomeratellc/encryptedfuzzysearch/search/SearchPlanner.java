package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Plans strategy selection only after checking every configured strategy against policy. */
public final class SearchPlanner {
  public SearchPlan plan(
      SearchAccuracyContract contract,
      List<? extends ProtectedIndexStrategy> configuredStrategies,
      SearchPolicy policy) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(configuredStrategies, "configuredStrategies");
    Objects.requireNonNull(policy, "policy");
    List<ProtectedIndexStrategy> strategies = List.copyOf(configuredStrategies);
    if (strategies.isEmpty()) {
      throw new IllegalArgumentException("at least one strategy must be configured");
    }
    strategies.forEach(policy::requireAllowed);

    return switch (contract.mode()) {
      case EXACT -> planExact(contract, strategies);
      case BOUNDED_FUZZY -> planBounded(contract, strategies);
      case APPROXIMATE -> new SearchPlan(contract, strategies, CandidateCompleteness.bestEffort());
    };
  }

  private static SearchPlan planExact(
      SearchAccuracyContract contract, List<ProtectedIndexStrategy> strategies) {
    List<ProtectedIndexStrategy> exactStrategies =
        strategies.stream()
            .filter(
                strategy ->
                    strategy.candidateCompleteness().level() == CandidateCompleteness.Level.EXACT)
            .toList();
    if (exactStrategies.isEmpty()) {
      throw new IllegalArgumentException("exact mode requires an exact-match index strategy");
    }
    return new SearchPlan(contract, exactStrategies, CandidateCompleteness.exact());
  }

  private static SearchPlan planBounded(
      SearchAccuracyContract contract, List<ProtectedIndexStrategy> strategies) {
    ProtectedIndexStrategy selected =
        strategies.stream()
            .filter(strategy -> strategy.candidateCompleteness().supports(contract))
            .filter(
                strategy ->
                    strategy.candidateCompleteness().level()
                        == CandidateCompleteness.Level.BOUNDED_EDIT_DISTANCE)
            .min(
                Comparator.comparingInt(
                    strategy -> strategy.candidateCompleteness().maximumEditDistance()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "no allowed strategy proves the requested edit-distance bound"));
    return new SearchPlan(
        contract,
        List.of(selected),
        CandidateCompleteness.boundedEditDistance(contract.maximumEditDistance()));
  }
}
