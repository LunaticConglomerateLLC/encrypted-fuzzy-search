package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class ProtectedPrefixStrategyTest {
  private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void emitsBoundedVersionedPrefixTokens() throws Exception {
    ProtectedPrefixStrategy strategy = new ProtectedPrefixStrategy(new ExactBlindIndexer());

    List<ProtectedToken> tokens = strategy.createTokens(key, "alphabet", null);

    assertEquals(tokens.size(), 7);
    assertEquals(tokens.getFirst().strategyId(), "efs-prefix-2-8");
    assertEquals(tokens.getFirst().formatVersion(), 1);
    assertEquals(
        strategy.leakageMetadata(),
        Set.of(
            IndexLeakage.EQUALITY,
            IndexLeakage.FREQUENCY,
            IndexLeakage.TOKEN_OVERLAP,
            IndexLeakage.PREFIX_RELATION));
  }

  @Test
  public void handlesPrefixesAsUnicodeCodePointsAndKeepsProfileNamespacesDistinct()
      throws Exception {
    ProtectedPrefixStrategy strategy = new ProtectedPrefixStrategy(new ExactBlindIndexer());

    List<ProtectedToken> first = strategy.createTokens(key, "😀abc", null);
    List<ProtectedToken> second = strategy.createTokens(key, "😀axy", null);
    List<ProtectedToken> profiled = strategy.createTokens(key, "😀abc", "profile-v1");

    assertEquals(first.size(), 3);
    assertEquals(first.getFirst(), second.getFirst());
    assertEquals(profiled.getFirst().strategyId(), "efs-l10n-profile-v1-efs-prefix-2-8");
    assertEquals(first.getFirst().equals(profiled.getFirst()), false);
  }

  @Test
  public void shortValuesHaveNoPrefixAndConfigurationIsBounded() throws Exception {
    ProtectedPrefixStrategy strategy = new ProtectedPrefixStrategy(new ExactBlindIndexer());

    assertEquals(strategy.createTokens(key, "x", null), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectedPrefixStrategy(
                new ExactBlindIndexer(),
                0,
                8,
                ProtectedPrefixStrategy.DEFAULT_MAX_INPUT_CODE_POINTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectedPrefixStrategy(
                new ExactBlindIndexer(),
                3,
                2,
                ProtectedPrefixStrategy.DEFAULT_MAX_INPUT_CODE_POINTS));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedPrefixStrategy(new ExactBlindIndexer(), 2, 8, 7));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectedPrefixStrategy(
                new ExactBlindIndexer(),
                2,
                ProtectedPrefixStrategy.MAX_PREFIX_CODE_POINTS + 1,
                ProtectedPrefixStrategy.DEFAULT_MAX_INPUT_CODE_POINTS));
    assertThrows(NullPointerException.class, () -> new ProtectedPrefixStrategy(null));
  }
}
