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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Encrypts indexed values and returns authorized, scored results for protected-token candidates.
 */
public final class EncryptedFuzzySearchService {
  public static final int MAX_CANDIDATE_LIMIT = 1000;

  /** Maximum key versions accepted by one migration-window search request. */
  public static final int MAX_KEY_VERSIONS_PER_SEARCH = 16;

  /** Maximum distinct protected tokens per index or query across all multilingual forms. */
  public static final int MAX_MULTILINGUAL_TOKEN_COUNT = 4096;

  private static final byte[] AAD_LABEL =
      "efs-search-record-aad-v1".getBytes(StandardCharsets.US_ASCII);
  private static final Comparator<FuzzySearchResult> RESULT_ORDER =
      Comparator.comparingDouble(FuzzySearchResult::similarity)
          .reversed()
          .thenComparing(Comparator.comparingInt(FuzzySearchResult::overlapCount).reversed())
          .thenComparing(FuzzySearchResult::recordId);
  private final ScopedKeyDeriver keyDeriver;
  private final AuthenticatedFieldCipher fieldCipher;
  private final CanonicalTextNormalizer textNormalizer;
  private final List<ProtectedIndexStrategy> protectedIndexStrategies;
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
    this(
        keyDeriver,
        fieldCipher,
        textNormalizer,
        legacyStrategies(exactBlindIndexer, bigrams, trigrams),
        repository);
  }

  /** Creates the service with an ordered set of protected-index strategy implementations. */
  public EncryptedFuzzySearchService(
      ScopedKeyDeriver keyDeriver,
      AuthenticatedFieldCipher fieldCipher,
      CanonicalTextNormalizer textNormalizer,
      List<? extends ProtectedIndexStrategy> protectedIndexStrategies,
      TokenIndexRepository repository) {
    this.keyDeriver = Objects.requireNonNull(keyDeriver, "keyDeriver");
    this.fieldCipher = Objects.requireNonNull(fieldCipher, "fieldCipher");
    this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
    this.protectedIndexStrategies = validateStrategies(protectedIndexStrategies);
    this.repository = Objects.requireNonNull(repository, "repository");
    this.scorer = new CodePointLevenshteinScorer();
    this.transliterationProvider = new Icu4jTransliterationProvider();
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
              ? createStrategyTokens(indexKey, canonicalValue, null)
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

  /**
   * Searches records indexed under any of the supplied key versions during a key migration.
   * Candidate limiting applies independently to each key version; result IDs are deduplicated and
   * the best verified match is retained. The caller controls which versions remain searchable.
   */
  public List<FuzzySearchResult> searchAcrossKeyVersions(
      String tenantId,
      String fieldId,
      List<String> keyIds,
      String query,
      int candidateLimitPerKeyVersion,
      double minimumSimilarity)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    return searchAcrossKeyVersionsInternal(
        tenantId, fieldId, keyIds, query, candidateLimitPerKeyVersion, minimumSimilarity, null);
  }

  /** Searches migration-window records indexed with an explicit multilingual transform profile. */
  public List<FuzzySearchResult> searchAcrossKeyVersions(
      String tenantId,
      String fieldId,
      List<String> keyIds,
      String query,
      int candidateLimitPerKeyVersion,
      double minimumSimilarity,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    return searchAcrossKeyVersionsInternal(
        tenantId,
        fieldId,
        keyIds,
        query,
        candidateLimitPerKeyVersion,
        minimumSimilarity,
        Objects.requireNonNull(profile, "profile"));
  }

  private List<FuzzySearchResult> searchAcrossKeyVersionsInternal(
      String tenantId,
      String fieldId,
      List<String> keyIds,
      String query,
      int candidateLimitPerKeyVersion,
      double minimumSimilarity,
      TransliterationProfile profile)
      throws GeneralSecurityException, SQLException, CharacterCodingException {
    List<String> validatedKeyIds = validateKeyVersions(keyIds);
    List<FuzzySearchResult> results = new ArrayList<>();
    for (String keyId : validatedKeyIds) {
      results.addAll(
          searchWithProfile(
              tenantId,
              fieldId,
              keyId,
              query,
              candidateLimitPerKeyVersion,
              minimumSimilarity,
              profile));
    }

    Map<String, FuzzySearchResult> bestResultByRecord = new HashMap<>();
    for (FuzzySearchResult result : results) {
      bestResultByRecord.merge(
          result.recordId(),
          result,
          (existing, candidate) ->
              RESULT_ORDER.compare(candidate, existing) < 0 ? candidate : existing);
    }
    return bestResultByRecord.values().stream().sorted(RESULT_ORDER).toList();
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
    List<SearchCandidate> candidates;
    SecretKey indexKey = keyDeriver.deriveIndexKey(keyId, tenantId, fieldId);
    List<ProtectedToken> queryTokens =
        profile == null
            ? createStrategyTokens(indexKey, canonicalQuery, null)
            : createMultilingualTokens(indexKey, canonicalQuery, profile);
    candidates = repository.findCandidates(tenantId, fieldId, keyId, queryTokens, candidateLimit);

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
    matches.sort(RESULT_ORDER);
    return List.copyOf(matches);
  }

  private static List<String> validateKeyVersions(List<String> keyIds) {
    Objects.requireNonNull(keyIds, "keyIds");
    if (keyIds.isEmpty() || keyIds.size() > MAX_KEY_VERSIONS_PER_SEARCH) {
      throw new IllegalArgumentException(
          "keyIds must contain between 1 and " + MAX_KEY_VERSIONS_PER_SEARCH + " versions");
    }
    List<String> validated = List.copyOf(keyIds);
    if (new HashSet<>(validated).size() != validated.size()) {
      throw new IllegalArgumentException("keyIds must not contain duplicates");
    }
    for (String keyId : validated) {
      requireIdentifier(keyId, "keyId");
    }
    return validated;
  }

  /** Deletes both ciphertext and all associated protected token postings. */
  public void delete(String tenantId, String fieldId, String recordId) throws SQLException {
    repository.delete(tenantId, fieldId, recordId);
  }

  private List<ProtectedToken> createStrategyTokens(
      SecretKey indexKey, String canonicalText, String transformId)
      throws GeneralSecurityException {
    List<ProtectedToken> tokens = new ArrayList<>();
    Set<String> strategyIds = new HashSet<>();
    for (ProtectedIndexStrategy strategy : protectedIndexStrategies) {
      String strategyId = strategy.strategyId(transformId);
      if (!strategyIds.add(strategyId)) {
        throw new GeneralSecurityException("configured strategies produced a namespace collision");
      }
      List<ProtectedToken> generated =
          Objects.requireNonNull(
              strategy.createTokens(indexKey, canonicalText, transformId), "strategy tokens");
      for (ProtectedToken token : generated) {
        if (!strategyId.equals(token.strategyId())
            || token.formatVersion() != strategy.formatVersion()) {
          throw new GeneralSecurityException(
              "strategy emitted tokens outside its declared namespace");
        }
        tokens.add(token);
      }
    }
    return List.copyOf(tokens);
  }

  private static List<ProtectedIndexStrategy> legacyStrategies(
      ExactBlindIndexer exactBlindIndexer,
      ProtectedNgramStrategy bigrams,
      ProtectedNgramStrategy trigrams) {
    Objects.requireNonNull(exactBlindIndexer, "exactBlindIndexer");
    Objects.requireNonNull(bigrams, "bigrams");
    Objects.requireNonNull(trigrams, "trigrams");
    if (bigrams.gramSize() != 2 || trigrams.gramSize() != 3) {
      throw new IllegalArgumentException("service requires bigram and trigram strategies");
    }
    return List.of(new ProtectedExactStrategy(exactBlindIndexer), bigrams, trigrams);
  }

  private static List<ProtectedIndexStrategy> validateStrategies(
      List<? extends ProtectedIndexStrategy> strategies) {
    Objects.requireNonNull(strategies, "protectedIndexStrategies");
    if (strategies.isEmpty()) {
      throw new IllegalArgumentException("at least one protected-index strategy is required");
    }
    List<ProtectedIndexStrategy> immutableStrategies = List.copyOf(strategies);
    Set<String> identifiers = new HashSet<>();
    for (ProtectedIndexStrategy strategy : immutableStrategies) {
      Objects.requireNonNull(strategy, "protected-index strategy");
      String strategyId = Objects.requireNonNull(strategy.strategyId(null), "strategyId");
      if (strategyId.isBlank() || strategyId.length() > 64 || !identifiers.add(strategyId)) {
        throw new IllegalArgumentException("protected-index strategy IDs must be valid and unique");
      }
      if (strategy.formatVersion() < 1) {
        throw new IllegalArgumentException("strategy format version must be positive");
      }
      if (strategy.keyScope() != IndexKeyScope.TENANT_FIELD_KEY_VERSION) {
        throw new IllegalArgumentException("unsupported protected-index key scope");
      }
      if (strategy.candidateMatchMode() != CandidateMatchMode.ANY_SHARED_TOKEN) {
        throw new IllegalArgumentException("unsupported protected-index candidate match mode");
      }
      Objects.requireNonNull(strategy.leakageMetadata(), "strategy leakage metadata");
    }
    return immutableStrategies;
  }

  private List<ProtectedToken> createMultilingualTokens(
      SecretKey indexKey, String canonicalText, TransliterationProfile profile)
      throws GeneralSecurityException {
    LinkedHashSet<ProtectedToken> tokens = new LinkedHashSet<>();
    for (TransliterationVariant variant :
        transliterationProvider.variants(canonicalText, profile)) {
      addMultilingualTokens(
          tokens, createStrategyTokens(indexKey, variant.value(), profile.transformId()));
    }
    if (tokens.size() > MAX_MULTILINGUAL_TOKEN_COUNT) {
      throw new IllegalArgumentException(
          "multilingual variants exceed maximum of " + MAX_MULTILINGUAL_TOKEN_COUNT + " tokens");
    }
    return List.copyOf(tokens);
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
