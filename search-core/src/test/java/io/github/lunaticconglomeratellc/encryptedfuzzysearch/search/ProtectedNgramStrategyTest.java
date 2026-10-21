package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class ProtectedNgramStrategyTest {
  private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");

  @Test
  public void generatesCodePointBigramsAndTrigramsInStableOrder() throws Exception {
    ProtectedNgramStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());
    ProtectedNgramStrategy trigrams = new ProtectedNgramStrategy(3, new NgramBlindIndexer());

    List<byte[]> bigramTokens = bigrams.createTokens(key, "abcd");
    List<byte[]> trigramTokens = trigrams.createTokens(key, "abcd");

    assertEquals(bigramTokens.size(), 3);
    assertEquals(trigramTokens.size(), 2);
    assertEquals(bigrams.gramSize(), 2);
    assertEquals(
        trigrams.maxInputCodePoints(), ProtectedNgramStrategy.DEFAULT_MAX_INPUT_CODE_POINTS);
    assertEquals(encode(bigramTokens.get(0)), encode(bigrams.createTokens(key, "abcd").get(0)));
    assertFalse(Arrays.equals(bigramTokens.get(0), trigramTokens.get(0)));
  }

  @Test
  public void deduplicatesRepeatedGramsAndReturnsFirstOccurrenceOrder() throws Exception {
    ProtectedNgramStrategy strategy = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    List<byte[]> tokens = strategy.createTokens(key, "aaaa");

    assertEquals(tokens.size(), 1);
    assertTrue(
        new HashSet<>(tokens.stream().map(ProtectedNgramStrategyTest::encode).toList()).size()
            == 1);
  }

  @Test
  public void handlesShortAndEmptyInputsExplicitly() throws Exception {
    ProtectedNgramStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());
    ProtectedNgramStrategy trigrams = new ProtectedNgramStrategy(3, new NgramBlindIndexer());

    assertTrue(bigrams.createTokens(key, "").isEmpty());
    assertEquals(bigrams.createTokens(key, "x").size(), 1);
    assertEquals(trigrams.createTokens(key, "xy").size(), 1);
    assertTrue(new NgramBlindIndexer().isSupportedToken(bigrams.createTokens(key, "x").get(0)));
  }

  @Test
  public void treatsSupplementaryUnicodeCharactersAsSingleCodePoints() throws Exception {
    ProtectedNgramStrategy strategy = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    List<byte[]> tokens = strategy.createTokens(key, "a😀b");

    assertEquals(tokens.size(), 2);
    assertTrue(new NgramBlindIndexer().isSupportedToken(tokens.get(0)));
    assertTrue(new NgramBlindIndexer().isSupportedToken(tokens.get(1)));
  }

  @Test
  public void rejectsOversizedInputInsteadOfTruncatingIt() {
    ProtectedNgramStrategy strategy = new ProtectedNgramStrategy(2, 4, new NgramBlindIndexer());

    assertThrows(IllegalArgumentException.class, () -> strategy.createTokens(key, "12345"));
  }

  @Test
  public void validatesStrategyAndInputBound() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedNgramStrategy(1, new NgramBlindIndexer()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedNgramStrategy(3, 2, new NgramBlindIndexer()));
    assertThrows(NullPointerException.class, () -> new ProtectedNgramStrategy(2, null));
    assertThrows(
        NullPointerException.class,
        () -> new ProtectedNgramStrategy(2, new NgramBlindIndexer()).createTokens(key, null));
  }

  @Test
  public void emitsStrategyAndVersionMetadataWithOpaqueValues() throws Exception {
    ProtectedNgramStrategy strategy = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    List<ProtectedToken> tokens = strategy.createProtectedTokens(key, "private");

    assertEquals(strategy.strategyId(), "efs-ngram-2");
    assertEquals(tokens.size(), 6);
    assertEquals(tokens.get(0).strategyId(), "efs-ngram-2");
    assertEquals(tokens.get(0).formatVersion(), 1);
    assertEquals(tokens.get(0).value().length, 39);
    assertFalse(
        Arrays.equals(
            tokens.get(0).value(), "pr".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
