package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

public class SearchPlannerTest {
  private final SearchPlanner planner = new SearchPlanner();

  @Test
  public void exactPlanSelectsOnlyExactIndexAndReportsExactGuarantee() {
    ProtectedIndexStrategy exact = new ProtectedExactStrategy(new ExactBlindIndexer());
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    SearchPlan plan =
        planner.plan(
            SearchAccuracyContract.exact(),
            List.of(exact, bigrams),
            SearchPolicy.productionDefault());

    assertEquals(plan.strategyIds(), List.of("efs-exact"));
    assertEquals(plan.candidateCompleteness(), CandidateCompleteness.exact());
  }

  @Test
  public void boundedPlanSelectsLeastExpansiveStrategyThatProvesRequestedDistance() {
    ProtectedIndexStrategy radiusOne =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 64, 256);
    ProtectedIndexStrategy radiusTwo =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 2, 64, 1024);
    SearchPolicy policy =
        new SearchPolicy(
            Set.of("efs-delete-r1", "efs-delete-r2"),
            Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP));

    SearchPlan plan =
        planner.plan(SearchAccuracyContract.boundedFuzzy(1), List.of(radiusTwo, radiusOne), policy);

    assertEquals(plan.strategyIds(), List.of("efs-delete-r1"));
    assertEquals(plan.candidateCompleteness(), CandidateCompleteness.boundedEditDistance(1));
  }

  @Test
  public void approximatePlanReportsBestEffortEvenWithExactAndNgramIndexes() {
    SearchPlan plan =
        planner.plan(
            SearchAccuracyContract.approximate(),
            List.of(
                new ProtectedExactStrategy(new ExactBlindIndexer()),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer())),
            SearchPolicy.productionDefault());

    assertEquals(plan.strategyIds(), List.of("efs-exact", "efs-ngram-2"));
    assertEquals(plan.candidateCompleteness(), CandidateCompleteness.bestEffort());
  }

  @Test
  public void policyRejectsConfiguredDeletionEvenWhenApproximateModeCouldUseIt() {
    ProtectedIndexStrategy deletion = new ProtectedDeletionStrategy(new ExactBlindIndexer());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            planner.plan(
                SearchAccuracyContract.approximate(),
                List.of(deletion),
                SearchPolicy.productionDefault()));
  }

  @Test
  public void strategyLeakageMustFitPolicyEvenWhenItsIdentifierIsAllowlisted() {
    ProtectedIndexStrategy soundex = new ProtectedSoundexStrategy(new ExactBlindIndexer());
    SearchPolicy policy =
        new SearchPolicy(
            Set.of(soundex.strategyId(null)),
            Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP));

    assertThrows(
        IllegalArgumentException.class,
        () -> planner.plan(SearchAccuracyContract.approximate(), List.of(soundex), policy));
  }

  @Test
  public void boundedPlanCannotUseHeuristicNgramsOrAWeakRadius() {
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());
    ProtectedIndexStrategy radiusOne =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 64, 256);
    SearchPolicy policy =
        new SearchPolicy(
            Set.of("efs-ngram-2", "efs-delete-r1"),
            Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP));

    assertThrows(
        IllegalArgumentException.class,
        () -> planner.plan(SearchAccuracyContract.boundedFuzzy(2), List.of(bigrams), policy));
    assertThrows(
        IllegalArgumentException.class,
        () -> planner.plan(SearchAccuracyContract.boundedFuzzy(2), List.of(radiusOne), policy));
  }

  @Test
  public void completenessModelDoesNotPromoteHeuristicSearchToAProof() {
    CandidateCompleteness approximate = CandidateCompleteness.bestEffort();

    assertFalse(approximate.supports(SearchAccuracyContract.exact()));
    assertFalse(approximate.supports(SearchAccuracyContract.boundedFuzzy(1)));
    assertTrue(approximate.supports(SearchAccuracyContract.approximate()));
  }
}
