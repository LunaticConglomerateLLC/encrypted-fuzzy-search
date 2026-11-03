package io.github.lunaticconglomeratellc.encryptedfuzzysearch.benchmark;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** JVM-only component costs; these methods do not include a database or request stack. */
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CryptoAndScoringBenchmark {
  @Param({"32", "128", "512"})
  public int textLength;

  @Param({"20261102"})
  public long seed;

  @Param({"synthetic-lowercase-ascii-v1"})
  public String inputGenerator;

  @Param({"unknown"})
  public String sourceRevision;

  @Param({"unknown"})
  public String sourceTreeDirty;

  private static final byte[] ASSOCIATED_DATA =
      "synthetic-benchmark-context".getBytes(StandardCharsets.UTF_8);
  private final CanonicalTextNormalizer normalizer = new CanonicalTextNormalizer();
  private final AuthenticatedFieldCipher cipher = new AuthenticatedFieldCipher();
  private final ExactBlindIndexer exactIndexer = new ExactBlindIndexer();
  private final ProtectedNgramStrategy bigrams =
      new ProtectedNgramStrategy(2, new NgramBlindIndexer());
  private final ProtectedNgramStrategy trigrams =
      new ProtectedNgramStrategy(3, new NgramBlindIndexer());
  private String text;
  private byte[] plaintext;
  private byte[] ciphertext;
  private SecretKey encryptionKey;
  private SecretKey indexKey;

  @Setup(Level.Trial)
  public void setup() throws GeneralSecurityException {
    SplittableRandom random = new SplittableRandom(seed + textLength);
    StringBuilder generated = new StringBuilder(textLength);
    for (int index = 0; index < textLength; index++) {
      generated.append((char) ('a' + random.nextInt(26)));
    }
    text = generated.toString();
    plaintext = text.getBytes(StandardCharsets.UTF_8);

    byte[] masterBytes = new byte[32];
    java.util.Arrays.fill(masterBytes, (byte) 0x41);
    SecretKey masterKey = new SecretKeySpec(masterBytes, "HmacSHA256");
    ScopedKeyDeriver deriver = new ScopedKeyDeriver(ignored -> masterKey);
    encryptionKey = deriver.deriveEncryptionKey("benchmark-key-v1", "benchmark-tenant", "name");
    indexKey = deriver.deriveIndexKey("benchmark-key-v1", "benchmark-tenant", "name");
    ciphertext = cipher.encrypt(encryptionKey, plaintext, ASSOCIATED_DATA);
  }

  @Benchmark
  public byte[] aesGcmEncrypt() throws GeneralSecurityException {
    return cipher.encrypt(encryptionKey, plaintext, ASSOCIATED_DATA);
  }

  @Benchmark
  public byte[] aesGcmDecrypt() throws GeneralSecurityException {
    return cipher.decrypt(encryptionKey, ciphertext, ASSOCIATED_DATA);
  }

  @Benchmark
  public byte[] exactBlindToken() throws GeneralSecurityException {
    return exactIndexer.createToken(indexKey, plaintext);
  }

  @Benchmark
  public List<byte[]> protectedBigrams() throws GeneralSecurityException {
    return bigrams.createTokens(indexKey, text);
  }

  @Benchmark
  public List<byte[]> protectedTrigrams() throws GeneralSecurityException {
    return trigrams.createTokens(indexKey, text);
  }

  @Benchmark
  public String normalize() {
    return normalizer.normalize(text);
  }
}
