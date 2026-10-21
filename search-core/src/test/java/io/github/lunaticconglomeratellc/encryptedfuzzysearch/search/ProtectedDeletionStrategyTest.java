package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class ProtectedDeletionStrategyTest {
  private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void measuresUniqueRadiusTwoExpansionAndNamesTheRadius() throws Exception {
    ProtectedDeletionStrategy strategy = new ProtectedDeletionStrategy(new ExactBlindIndexer());

    List<ProtectedToken> tokens = strategy.createTokens(key, "abcdefgh", null);

    assertEquals(strategy.maxEditRadius(), 2);
    assertEquals(
        strategy.maxInputCodePoints(), ProtectedDeletionStrategy.DEFAULT_MAX_INPUT_CODE_POINTS);
    assertEquals(strategy.maxTokenCount(), ProtectedDeletionStrategy.DEFAULT_MAX_TOKEN_COUNT);
    assertEquals(tokens.size(), 37);
    assertEquals(tokens.getFirst().strategyId(), "efs-delete-r2");
    assertEquals(tokens.getFirst().formatVersion(), 1);
    assertEquals(strategy.keyScope(), IndexKeyScope.TENANT_FIELD_KEY_VERSION);
    assertEquals(strategy.candidateMatchMode(), CandidateMatchMode.ANY_SHARED_TOKEN);
    assertEquals(
        strategy.leakageMetadata(),
        Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP));
  }

  @Test
  public void generatesVariantsByUnicodeCodePointAndVersionsProfileNamespaces() throws Exception {
    ProtectedDeletionStrategy strategy =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 8, 8);

    List<ProtectedToken> tokens = strategy.createTokens(key, "a😀b", "profile-v1");

    assertEquals(tokens.size(), 4);
    assertEquals(tokens.getFirst().strategyId(), "efs-l10n-profile-v1-delete-r1");
    assertThrows(IllegalArgumentException.class, () -> strategy.strategyId(" "));
    assertThrows(IllegalArgumentException.class, () -> strategy.strategyId("transform".repeat(8)));
  }

  @Test
  public void rejectsInputOrExpansionBeyondConfiguredLimits() {
    ProtectedDeletionStrategy inputBounded =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 3, 8);
    ProtectedDeletionStrategy expansionBounded =
        new ProtectedDeletionStrategy(new ExactBlindIndexer(), 2, 8, 36);

    assertThrows(
        IllegalArgumentException.class, () -> inputBounded.createTokens(key, "abcd", null));
    assertThrows(
        IllegalArgumentException.class, () -> expansionBounded.createTokens(key, "abcdefgh", null));
  }

  @Test
  public void validatesConfigurationAndInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedDeletionStrategy(new ExactBlindIndexer(), 0, 10, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedDeletionStrategy(new ExactBlindIndexer(), 3, 10, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 10, 0));
    assertThrows(NullPointerException.class, () -> new ProtectedDeletionStrategy(null));
    assertThrows(
        NullPointerException.class,
        () -> new ProtectedDeletionStrategy(new ExactBlindIndexer()).createTokens(key, null, null));
  }
}
