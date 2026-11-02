package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

public class SearchRequestTest {
  @Test
  public void factoriesCreateTheRequestedModes() {
    assertEquals(
        SearchRequest.exact("tenant", "field", "key", "query", 10).accuracyContract(),
        SearchAccuracyContract.exact());
    assertEquals(
        SearchRequest.boundedFuzzy("tenant", "field", "key", "query", 2, 10, 0.7)
            .accuracyContract(),
        SearchAccuracyContract.boundedFuzzy(2));
    assertEquals(
        SearchRequest.approximate("tenant", "field", "key", "query", 10, 0.7).accuracyContract(),
        SearchAccuracyContract.approximate());
  }

  @Test
  public void rejectsInvalidSearchBoundsAndExactSimilarityThatWouldChangeModeSemantics() {
    assertThrows(IllegalArgumentException.class, () -> SearchAccuracyContract.boundedFuzzy(0));
    assertThrows(
        IllegalArgumentException.class, () -> new SearchAccuracyContract(SearchMode.EXACT, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRequest.exact("tenant", "field", "key", "query", 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SearchRequest(
                "tenant", "field", "key", "query", SearchAccuracyContract.exact(), 10, 0.99));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRequest.approximate("tenant", "field", "key", "query", 10, Double.NaN));
    assertThrows(
        NullPointerException.class,
        () -> new SearchRequest("tenant", "field", "key", "query", null, 10, 0.5));
  }

  @Test
  public void searchPolicyRequiresStrategiesAndTracksAllowedLeakage() {
    SearchPolicy policy = SearchPolicy.productionDefault();
    ProtectedIndexStrategy exact =
        new ProtectedExactStrategy(
            new io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer());
    ProtectedIndexStrategy prefix =
        new ProtectedPrefixStrategy(
            new io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer());

    assertEquals(policy.allows(exact), true);
    assertEquals(policy.allows(prefix), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchPolicy(java.util.Set.of(), java.util.Set.of()));
  }
}
