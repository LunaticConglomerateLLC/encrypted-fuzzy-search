package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class ProtectedIndexStrategyTest {
  private final SecretKey indexKey = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void exactAndNgramStrategiesCoexistInDistinctVersionedNamespaces() throws Exception {
    ProtectedIndexStrategy exact = new ProtectedExactStrategy(new ExactBlindIndexer());
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());
    List<ProtectedToken> tokens =
        List.of(
            exact.createTokens(indexKey, "private", null).get(0),
            bigrams.createTokens(indexKey, "private", null).get(0));

    assertEquals(exact.strategyId(null), "efs-exact");
    assertEquals(bigrams.strategyId(null), "efs-ngram-2");
    assertEquals(tokens.get(0).formatVersion(), exact.formatVersion());
    assertEquals(tokens.get(1).formatVersion(), bigrams.formatVersion());
    assertFalse(tokens.get(0).strategyId().equals(tokens.get(1).strategyId()));
    assertEquals(new HashSet<>(tokens).size(), 2);
  }

  @Test
  public void strategiesExposeScopeQuerySemanticsAndLeakage() {
    ProtectedIndexStrategy exact = new ProtectedExactStrategy(new ExactBlindIndexer());
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    assertEquals(exact.keyScope(), IndexKeyScope.TENANT_FIELD_KEY_VERSION);
    assertEquals(bigrams.keyScope(), IndexKeyScope.TENANT_FIELD_KEY_VERSION);
    assertEquals(exact.candidateMatchMode(), CandidateMatchMode.ANY_SHARED_TOKEN);
    assertEquals(bigrams.candidateMatchMode(), CandidateMatchMode.ANY_SHARED_TOKEN);
    assertEquals(exact.leakageMetadata(), Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY));
    assertEquals(
        bigrams.leakageMetadata(),
        Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP));
  }

  @Test
  public void transformProfilesHaveIndependentStrategyNamespaces() throws Exception {
    ProtectedIndexStrategy exact = new ProtectedExactStrategy(new ExactBlindIndexer());
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());
    List<ProtectedToken> baseTokens = exact.createTokens(indexKey, "private", null);
    List<ProtectedToken> profileTokens = exact.createTokens(indexKey, "private", "profile-v1");
    List<ProtectedToken> profileGrams = bigrams.createTokens(indexKey, "private", "profile-v1");

    assertEquals(profileTokens.get(0).strategyId(), "efs-xexact-profile-v1");
    assertEquals(profileGrams.get(0).strategyId(), "efs-l10n-profile-v1-ngram-2");
    assertFalse(baseTokens.get(0).equals(profileTokens.get(0)));
  }

  @Test
  public void rejectsInvalidTransformNamespaces() {
    ProtectedIndexStrategy exact = new ProtectedExactStrategy(new ExactBlindIndexer());
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    assertThrows(IllegalArgumentException.class, () -> exact.strategyId(" "));
    assertThrows(IllegalArgumentException.class, () -> exact.strategyId("x".repeat(65)));
    assertThrows(IllegalArgumentException.class, () -> bigrams.strategyId("x".repeat(60)));
    assertThrows(NullPointerException.class, () -> exact.createTokens(indexKey, null, null));
  }
}
