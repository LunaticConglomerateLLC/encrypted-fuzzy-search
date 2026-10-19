package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;
import org.testng.annotations.Test;

public class SearchDataModelTest {
  @Test
  public void protectedTokenDefensivelyCopiesItsValueAndUsesArrayContentsForEquality() {
    byte[] source = {1, 2, 3};
    ProtectedToken token = new ProtectedToken("efs-ngram-2", 1, source);
    source[0] = 9;
    byte[] returned = token.value();
    returned[1] = 9;

    assertTrue(token.equals(new ProtectedToken("efs-ngram-2", 1, new byte[] {1, 2, 3})));
    assertEquals(
        token.hashCode(), new ProtectedToken("efs-ngram-2", 1, new byte[] {1, 2, 3}).hashCode());
    assertFalse(token.equals(new ProtectedToken("efs-ngram-3", 1, new byte[] {1, 2, 3})));
    assertFalse(token.equals(new ProtectedToken("efs-ngram-2", 2, new byte[] {1, 2, 3})));
    assertFalse(token.equals(new ProtectedToken("efs-ngram-2", 1, new byte[] {1, 2, 4})));
    assertNotEquals(token, "token");
    assertEquals(token.value()[0], (byte) 1);
    assertEquals(token.value()[1], (byte) 2);
  }

  @Test
  public void protectedTokenRejectsInvalidStrategyVersionAndValue() {
    assertThrows(NullPointerException.class, () -> new ProtectedToken(null, 1, new byte[] {1}));
    assertThrows(NullPointerException.class, () -> new ProtectedToken("type", 1, null));
    assertThrows(IllegalArgumentException.class, () -> new ProtectedToken(" ", 1, new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtectedToken("x".repeat(65), 1, new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class, () -> new ProtectedToken("type", 0, new byte[] {1}));
    assertThrows(IllegalArgumentException.class, () -> new ProtectedToken("type", 1, new byte[0]));
    assertThrows(
        IllegalArgumentException.class, () -> new ProtectedToken("type", 1, new byte[513]));
  }

  @Test
  public void encryptedSearchDocumentCopiesDataAndRejectsDuplicateTokens() {
    byte[] ciphertext = {4, 5};
    ProtectedToken token = new ProtectedToken("efs-ngram-2", 1, new byte[] {7});
    EncryptedSearchDocument document = document(ciphertext, List.of(token));
    ciphertext[0] = 0;
    byte[] fetchedCiphertext = document.ciphertext();
    fetchedCiphertext[0] = 0;

    assertEquals(document.ciphertext()[0], (byte) 4);
    assertEquals(document.protectedTokens(), List.of(token));
    assertThrows(
        IllegalArgumentException.class, () -> document("cipher".getBytes(), List.of(token, token)));
  }

  @Test
  public void encryptedSearchDocumentValidatesAllFields() {
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument(null, "f", "r", "k", "v", new byte[] {1}, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", null, "r", "k", "v", new byte[] {1}, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", "f", null, "k", "v", new byte[] {1}, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", "f", "r", null, "v", new byte[] {1}, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", "f", "r", "k", null, new byte[] {1}, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EncryptedSearchDocument(" ", "f", "r", "k", "v", new byte[] {1}, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptedSearchDocument(
                "x".repeat(257), "f", "r", "k", "v", new byte[] {1}, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", "f", "r", "k", "v", null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EncryptedSearchDocument("t", "f", "r", "k", "v", new byte[0], List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new EncryptedSearchDocument("t", "f", "r", "k", "v", new byte[] {1}, null));
  }

  @Test
  public void searchCandidateDefensivelyCopiesCiphertextAndValidatesFields() {
    byte[] ciphertext = {2, 3};
    SearchCandidate candidate = new SearchCandidate("r", "k", "v", ciphertext, 1);
    ciphertext[0] = 0;
    assertEquals(candidate.ciphertext()[0], (byte) 2);

    assertThrows(
        NullPointerException.class, () -> new SearchCandidate(null, "k", "v", new byte[] {1}, 1));
    assertThrows(
        NullPointerException.class, () -> new SearchCandidate("r", null, "v", new byte[] {1}, 1));
    assertThrows(
        NullPointerException.class, () -> new SearchCandidate("r", "k", null, new byte[] {1}, 1));
    assertThrows(NullPointerException.class, () -> new SearchCandidate("r", "k", "v", null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new SearchCandidate("r", "k", "v", new byte[0], 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchCandidate("r", "k", "v", new byte[] {1}, 0));
  }

  private static EncryptedSearchDocument document(byte[] ciphertext, List<ProtectedToken> tokens) {
    return new EncryptedSearchDocument(
        "tenant", "field", "record", "key", "transform", ciphertext, tokens);
  }
}
