package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.Icu4jTransliterationProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.TransliterationProfile;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.TransliterationVariant;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;

/**
 * Encrypts indexed values and returns authorized, scored results for protected-token candidates.
 */
public final class EncryptedFuzzySearchService {
  public static final int MAX_CANDIDATE_LIMIT = 1000;

  /** Maximum distinct protected tokens per index or query across all multilingual forms. */
  public static final int MAX_MULTILINGUAL_TOKEN_COUNT = 4096;

  private static final byte[] AAD_LABEL =
      "efs-search-record-aad-v1".getBytes(StandardCharsets.US_ASCII);
  private static final String EXACT_STRATEGY_ID = "efs-exact";

  private final ScopedKeyDeriver keyDeriver;
  private final AuthenticatedFieldCipher fieldCipher;
  private final ExactBlindIndexer exactBlindIndexer;
  private final CanonicalTextNormalizer textNormalizer;
  private final ProtectedNgramStrategy bigrams;
  private final ProtectedNgramStrategy trigrams;
  private final TokenIndexRepository repository;
  private final CodePointLevenshteinScorer scorer;
  private final Icu4jTransliterationProvider transliterationProvider;

  /**
   * Creates the service from framework-independent crypto, language, search and repository APIs.
   */
  public EncryptedFuzzySearchService(
      ScopedKeyDeriver keyDeriver,
      AuthenticatedFieldCipher fieldCipher,
      ExactBlindIndexer exactBlindIndexer,
      CanonicalTextNormalizer textNormalizer,
      ProtectedNgramStrategy bigrams,
      ProtectedNgramStrategy trigrams,
      TokenIndexRepository repository) {
    this.keyDeriver = Objects.requireNonNull(keyDeriver, "keyDeriver");
    this.fieldCipher = Objects.requireNonNull(fieldCipher, "fieldCipher");
    this.exactBlindIndexer = Objects.requireNonNull(exactBlindIndexer, "exactBlindIndexer");
    this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
    this.bigrams = Objects.requireNonNull(bigrams, "bigrams");
    this.trigrams = Objects.requireNonNull(trigrams, "trigrams");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.scorer = new CodePointLevenshteinScorer();
    this.transliterationProvider = new Icu4jTransliterationProvider();
    if (bigrams.gramSize() != 2 || trigrams.gramSize() != 3) {
      throw new IllegalArgumentException("service requires bigram and trigram strategies");
    }
  }

  /** Normalizes, encrypts and indexes one value using tenant/field/record-bound AAD. */
  public void indexValue(
      String tenantId, String fieldId, String recordId, String keyId, String value)
      throws GeneralSecurityException, SQLException {
    indexValueWithProfile(tenantId, fieldId, recordId, keyId, value, null);
  }

  /** Indexes a value using an explicitly selected, immutable multilingual transform profile. */
  public void indexValue(
      String tenantId,
      String fieldId,
      String recordId,
      String keyId,
      String value,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException {
    indexValueWithProfile(
        tenantId, fieldId, recordId, keyId, value, Objects.requireNonNull(profile, "profile"));
  }

  private void indexValueWithProfile(
      String tenantId,
      String fieldId,
      String recordId,
      String keyId,
      String value,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException {
    requireIdentifier(tenantId, "tenantId");
    requireIdentifier(fieldId, "fieldId");
    requireIdentifier(recordId, "recordId");
    requireIdentifier(keyId, "keyId");
    String canonicalValue = textNormalizer.normalize(value);
    byte[] plaintext = canonicalValue.getBytes(StandardCharsets.UTF_8);
    byte[] associatedData = associatedData(tenantId, fieldId, recordId, keyId);
    byte[] ciphertext = null;
    try {
      SecretKey indexKey = keyDeriver.deriveIndexKey(keyId, tenantId, fieldId);
      List<ProtectedToken> tokens =
          profile == null
              ? createTokens(indexKey, canonicalValue, plaintext)
              : createMultilingualTokens(indexKey, canonicalValue, profile);
      SecretKey encryptionKey = keyDeriver.deriveEncryptionKey(keyId, tenantId, fieldId);
      ciphertext = fieldCipher.encrypt(encryptionKey, plaintext, associatedData);
      repository.upsert(
          new EncryptedSearchDocument(
              tenantId,
              fieldId,
              recordId,
              keyId,
              profile == null ? CanonicalTextNormalizer.TRANSFORM_ID : profile.transformId(),
              ciphertext,
              tokens));
    } finally {
      Arrays.fill(plaintext, (byte) 0);
      Arrays.fill(associatedData, (byte) 0);
      if (ciphertext != null) {
        Arrays.fill(ciphertext, (byte) 0);
      }
    }
  }

  /**
   * Retrieves a bounded candidate set, decrypts only those candidates, and ranks verified matches.
   * Candidate limiting may reduce recall and is not a completeness guarantee.
   *
   * <p>The caller must authorize access to the supplied tenant and field before invoking this
   * method. This library enforces cryptographic/database scope but does not implement caller
   * identity or application authorization.
   */
  public List<FuzzySearchResult> search(
      String tenantId,
      String fieldId,
      String keyId,
      String query,
      int candidateLimit,
      double minimumSimilarity)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    return searchWithProfile(
        tenantId, fieldId, keyId, query, candidateLimit, minimumSimilarity, null);
  }

  /** Searches a field whose values were indexed with the same explicit transform profile. */
  public List<FuzzySearchResult> search(
      String tenantId,
      String fieldId,
      String keyId,
      String query,
      int candidateLimit,
      double minimumSimilarity,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    return searchWithProfile(
        tenantId,
        fieldId,
        keyId,
        query,
        candidateLimit,
        minimumSimilarity,
        Objects.requireNonNull(profile, "profile"));
  }

  private List<FuzzySearchResult> searchWithProfile(
      String tenantId,
      String fieldId,
      String keyId,
      String query,
      int candidateLimit,
      double minimumSimilarity,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    requireIdentifier(tenantId, "tenantId");
    requireIdentifier(fieldId, "fieldId");
    requireIdentifier(keyId, "keyId");
    if (candidateLimit < 1 || candidateLimit > MAX_CANDIDATE_LIMIT) {
      throw new IllegalArgumentException(
          "candidateLimit must be between 1 and " + MAX_CANDIDATE_LIMIT);
    }
    if (!Double.isFinite(minimumSimilarity) || minimumSimilarity < 0.0 || minimumSimilarity > 1.0) {
      throw new IllegalArgumentException(
          "minimumSimilarity must be finite and between 0.0 and 1.0");
    }

    String canonicalQuery = textNormalizer.normalize(query);
    byte[] queryBytes = canonicalQuery.getBytes(StandardCharsets.UTF_8);
    List<SearchCandidate> candidates;
    try {
      SecretKey indexKey = keyDeriver.deriveIndexKey(keyId, tenantId, fieldId);
      List<ProtectedToken> queryTokens =
          profile == null
              ? createTokens(indexKey, canonicalQuery, queryBytes)
              : createMultilingualTokens(indexKey, canonicalQuery, profile);
      candidates = repository.findCandidates(tenantId, fieldId, keyId, queryTokens, candidateLimit);
    } finally {
      Arrays.fill(queryBytes, (byte) 0);
    }

    List<FuzzySearchResult> matches = new ArrayList<>();
    for (SearchCandidate candidate : candidates) {
      String expectedTransform =
          profile == null ? CanonicalTextNormalizer.TRANSFORM_ID : profile.transformId();
      if (!expectedTransform.equals(candidate.transformVersion())) {
        throw new GeneralSecurityException("candidate uses an unsupported text transform");
      }
      byte[] associatedData =
          associatedData(tenantId, fieldId, candidate.recordId(), candidate.keyId());
      byte[] plaintext = null;
      try {
        SecretKey encryptionKey =
            keyDeriver.deriveEncryptionKey(candidate.keyId(), tenantId, fieldId);
        plaintext = fieldCipher.decrypt(encryptionKey, candidate.ciphertext(), associatedData);
        String value = decodeUtf8(plaintext);
        double similarity =
            profile == null
                ? scorer.score(canonicalQuery, value)
                : multilingualSimilarity(canonicalQuery, value, profile);
        if (similarity >= minimumSimilarity) {
          matches.add(
              new FuzzySearchResult(
                  candidate.recordId(), value, similarity, candidate.overlapCount()));
        }
      } finally {
        Arrays.fill(associatedData, (byte) 0);
        if (plaintext != null) {
          Arrays.fill(plaintext, (byte) 0);
        }
      }
    }
    matches.sort(
        Comparator.comparingDouble(FuzzySearchResult::similarity)
            .reversed()
            .thenComparing(Comparator.comparingInt(FuzzySearchResult::overlapCount).reversed())
            .thenComparing(FuzzySearchResult::recordId));
    return List.copyOf(matches);
  }

  /** Deletes both ciphertext and all associated protected token postings. */
  public void delete(String tenantId, String fieldId, String recordId) throws SQLException {
    repository.delete(tenantId, fieldId, recordId);
  }

  private List<ProtectedToken> createTokens(
      SecretKey indexKey, String canonicalText, byte[] canonicalBytes)
      throws GeneralSecurityException {
    List<ProtectedToken> tokens = new ArrayList<>();
    tokens.add(
        new ProtectedToken(
            EXACT_STRATEGY_ID,
            exactBlindIndexer.formatVersion(),
            exactBlindIndexer.createToken(indexKey, canonicalBytes)));
    tokens.addAll(bigrams.createProtectedTokens(indexKey, canonicalText));
    tokens.addAll(trigrams.createProtectedTokens(indexKey, canonicalText));
    return List.copyOf(tokens);
  }

  private List<ProtectedToken> createMultilingualTokens(
      SecretKey indexKey, String canonicalText, TransliterationProfile profile)
      throws GeneralSecurityException {
    LinkedHashSet<ProtectedToken> tokens = new LinkedHashSet<>();
    for (TransliterationVariant variant :
        transliterationProvider.variants(canonicalText, profile)) {
      addMultilingualTokens(tokens, exactTokens(indexKey, variant.value(), profile));
      addMultilingualTokens(tokens, ngramTokens(indexKey, variant.value(), profile, bigrams));
      addMultilingualTokens(tokens, ngramTokens(indexKey, variant.value(), profile, trigrams));
    }
    if (tokens.size() > MAX_MULTILINGUAL_TOKEN_COUNT) {
      throw new IllegalArgumentException(
          "multilingual variants exceed maximum of " + MAX_MULTILINGUAL_TOKEN_COUNT + " tokens");
    }
    return List.copyOf(tokens);
  }

  private List<ProtectedToken> exactTokens(
      SecretKey indexKey, String value, TransliterationProfile profile)
      throws GeneralSecurityException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    try {
      return List.of(
          new ProtectedToken(
              "efs-xexact-" + profile.transformId(),
              exactBlindIndexer.formatVersion(),
              exactBlindIndexer.createToken(indexKey, bytes)));
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private List<ProtectedToken> ngramTokens(
      SecretKey indexKey,
      String value,
      TransliterationProfile profile,
      ProtectedNgramStrategy strategy)
      throws GeneralSecurityException {
    String strategyId = "efs-l10n-" + profile.transformId() + "-ngram-" + strategy.gramSize();
    if (strategyId.length() > 64) {
      throw new IllegalArgumentException(
          "transform profile produces an overlong token strategy id");
    }
    return strategy.createTokens(indexKey, value).stream()
        .map(token -> new ProtectedToken(strategyId, 1, token))
        .toList();
  }

  private static void addMultilingualTokens(
      LinkedHashSet<ProtectedToken> destination, List<ProtectedToken> additions) {
    destination.addAll(additions);
    if (destination.size() > MAX_MULTILINGUAL_TOKEN_COUNT) {
      throw new IllegalArgumentException(
          "multilingual variants exceed maximum of " + MAX_MULTILINGUAL_TOKEN_COUNT + " tokens");
    }
  }

  private double multilingualSimilarity(
      String query, String candidate, TransliterationProfile profile) {
    List<TransliterationVariant> queryVariants = transliterationProvider.variants(query, profile);
    List<TransliterationVariant> candidateVariants =
        transliterationProvider.variants(candidate, profile);
    double maximum = 0.0;
    for (TransliterationVariant queryVariant : queryVariants) {
      for (TransliterationVariant candidateVariant : candidateVariants) {
        maximum = Math.max(maximum, scorer.score(queryVariant.value(), candidateVariant.value()));
      }
    }
    return maximum;
  }

  private static byte[] associatedData(
      String tenantId, String fieldId, String recordId, String keyId) {
    byte[][] values =
        new byte[][] {
          tenantId.getBytes(StandardCharsets.UTF_8),
          fieldId.getBytes(StandardCharsets.UTF_8),
          recordId.getBytes(StandardCharsets.UTF_8),
          keyId.getBytes(StandardCharsets.UTF_8),
          CanonicalTextNormalizer.TRANSFORM_ID.getBytes(StandardCharsets.UTF_8)
        };
    int size = AAD_LABEL.length + Integer.BYTES * values.length;
    for (byte[] value : values) {
      size += value.length;
    }
    ByteBuffer buffer = ByteBuffer.allocate(size).put(AAD_LABEL);
    for (byte[] value : values) {
      buffer.putInt(value.length).put(value);
      Arrays.fill(value, (byte) 0);
    }
    return buffer.array();
  }

  private static String decodeUtf8(byte[] value) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private static void requireIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must contain 1 to 256 non-blank characters");
    }
  }
}
