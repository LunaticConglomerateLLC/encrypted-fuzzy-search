package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class ProtectedSoundexStrategyTest {
  private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void emitsSameProtectedCodeForKnownEnglishSoundexCollision() throws Exception {
    ProtectedSoundexStrategy strategy = new ProtectedSoundexStrategy(new ExactBlindIndexer());

    List<ProtectedToken> robert = strategy.createTokens(key, "Robert", null);
    List<ProtectedToken> rupert = strategy.createTokens(key, "Rupert", null);

    assertEquals(robert.size(), 1);
    assertEquals(robert, rupert);
    assertEquals(robert.getFirst().strategyId(), "efs-soundex-ascii-en-v1");
    assertEquals(
        strategy.maxInputCodePoints(), ProtectedSoundexStrategy.DEFAULT_MAX_INPUT_CODE_POINTS);
    assertEquals(
        strategy.leakageMetadata(),
        Set.of(
            IndexLeakage.EQUALITY,
            IndexLeakage.FREQUENCY,
            IndexLeakage.TOKEN_OVERLAP,
            IndexLeakage.STRATEGY_COLLISION));
  }

  @Test
  public void unsupportedScriptsAndNonWordInputsProduceNoPhoneticTokens() throws Exception {
    ProtectedSoundexStrategy strategy = new ProtectedSoundexStrategy(new ExactBlindIndexer());

    assertEquals(strategy.createTokens(key, "Иван", null), List.of());
    assertEquals(strategy.createTokens(key, "José", null), List.of());
    assertEquals(strategy.createTokens(key, "Robert Smith", null), List.of());
    assertEquals(strategy.createTokens(key, "R2D2", null), List.of());
    assertEquals(strategy.createTokens(key, "", null), List.of());
  }

  @Test
  public void validatesBoundsAndProfileNamespace() throws Exception {
    ProtectedSoundexStrategy bounded = new ProtectedSoundexStrategy(new ExactBlindIndexer(), 6);

    assertEquals(
        bounded.createTokens(key, "Robert", "profile-v1").getFirst().strategyId(),
        "efs-soundex-ascii-en-v1-profile-v1");
    assertThrows(IllegalArgumentException.class, () -> bounded.createTokens(key, "Roberts", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedSoundexStrategy(new ExactBlindIndexer(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectedSoundexStrategy(
                new ExactBlindIndexer(), ProtectedSoundexStrategy.MAX_INPUT_CODE_POINTS + 1));
    assertThrows(NullPointerException.class, () -> new ProtectedSoundexStrategy(null));
  }
}
