package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.InvalidCiphertextException;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.TransliterationProfile;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.Test;

public class EncryptedFuzzySearchServiceTest {
  @Test
  public void acceptsPluggableStrategiesAndPersistsTheirSeparateNamespaces() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            List.of(
                new ProtectedExactStrategy(new ExactBlindIndexer()),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer())),
            repository);

    service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");

    assertEquals(
        repository.documents.getFirst().protectedTokens().stream()
            .map(ProtectedToken::strategyId)
            .collect(java.util.stream.Collectors.toSet()),
        Set.of("efs-exact", "efs-ngram-2"));
    assertEquals(service.search("tenant-a", "name", "key-v1", "Alice", 10, 1.0).size(), 1);
  }

  @Test
  public void rejectsDuplicateStrategyNamespaces() {
    ProtectedIndexStrategy bigrams = new ProtectedNgramStrategy(2, new NgramBlindIndexer());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptedFuzzySearchService(
                keyDeriver(new AtomicInteger()),
                new AuthenticatedFieldCipher(),
                new CanonicalTextNormalizer(),
                List.of(bigrams, bigrams),
                new InMemoryRepository()));
  }

  @Test
  public void deletionNeighborhoodRetrievesEditsThenVerifiesExactDistance() throws Exception {
    InMemoryRepository editRepository = new InMemoryRepository();
    List<ProtectedIndexStrategy> deletionStrategies =
        List.of(
            new ProtectedExactStrategy(new ExactBlindIndexer()),
            new ProtectedDeletionStrategy(new ExactBlindIndexer()));
    EncryptedFuzzySearchService editService =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            deletionStrategies,
            editRepository,
            explicitPolicy(deletionStrategies));
    editService.indexValue("tenant-a", "name", "inserted", "key-v1", "cart");
    editService.indexValue("tenant-a", "name", "substituted", "key-v1", "cat");

    List<FuzzySearchResult> insertionOrDeletion =
        editService.search("tenant-a", "name", "key-v1", "cat", 10, 0.7);
    List<FuzzySearchResult> queryInsertion =
        editService.search("tenant-a", "name", "key-v1", "cart", 10, 0.7);
    List<FuzzySearchResult> substitution =
        editService.search("tenant-a", "name", "key-v1", "cut", 10, 0.6);

    assertTrue(
        insertionOrDeletion.stream().anyMatch(result -> result.recordId().equals("inserted")));
    assertTrue(queryInsertion.stream().anyMatch(result -> result.recordId().equals("substituted")));
    assertEquals(substitution.size(), 1);
    assertEquals(substitution.getFirst().recordId(), "substituted");

    InMemoryRepository verificationRepository = new InMemoryRepository();
    List<ProtectedIndexStrategy> verificationStrategies =
        List.of(
            new ProtectedExactStrategy(new ExactBlindIndexer()),
            new ProtectedDeletionStrategy(new ExactBlindIndexer()));
    EncryptedFuzzySearchService verificationService =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            verificationStrategies,
            verificationRepository,
            explicitPolicy(verificationStrategies));
    verificationService.indexValue("tenant-a", "name", "distant", "key-v1", "abcd");

    assertTrue(
        verificationService.search("tenant-a", "name", "key-v1", "abxy", 10, 0.75).isEmpty());
    assertTrue(verificationRepository.lastCandidateCount > 0);
  }

  @Test
  public void optionalPhoneticAndPrefixCandidatesAreStillSimilarityVerified() throws Exception {
    InMemoryRepository phoneticRepository = new InMemoryRepository();
    List<ProtectedIndexStrategy> phoneticStrategies =
        List.of(
            new ProtectedSoundexStrategy(new ExactBlindIndexer()),
            new ProtectedPrefixStrategy(new ExactBlindIndexer()));
    EncryptedFuzzySearchService phoneticService =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            phoneticStrategies,
            phoneticRepository,
            explicitPolicy(phoneticStrategies));
    phoneticService.indexValue("tenant-a", "name", "rupert", "key-v1", "Rupert");

    assertTrue(phoneticService.search("tenant-a", "name", "key-v1", "Robert", 10, 0.9).isEmpty());
    assertTrue(phoneticRepository.lastCandidateCount > 0);

    InMemoryRepository prefixRepository = new InMemoryRepository();
    List<ProtectedIndexStrategy> prefixStrategies =
        List.of(new ProtectedPrefixStrategy(new ExactBlindIndexer()));
    EncryptedFuzzySearchService prefixService =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            prefixStrategies,
            prefixRepository,
            explicitPolicy(prefixStrategies));
    prefixService.indexValue("tenant-a", "name", "carpet", "key-v1", "carpet");

    assertTrue(prefixService.search("tenant-a", "name", "key-v1", "carton", 10, 0.9).isEmpty());
    assertTrue(prefixRepository.lastCandidateCount > 0);
  }

  @Test
  public void detailedExactSearchReportsCompletenessUnlessCandidateCapIsExceeded()
      throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "alice-1", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "alice-2", "key-v1", "Alice");

    SearchOutcome outcome =
        service.search(SearchRequest.exact("tenant-a", "name", "key-v1", "Alice", 1));

    assertEquals(outcome.matches().size(), 1);
    assertEquals(outcome.candidateCompleteness(), CandidateCompleteness.exact());
    assertTrue(outcome.candidateLimitExceeded());
    assertEquals(outcome.candidatesVerified(), 1);
    assertEquals(outcome.strategyIds(), List.of("efs-exact"));
    assertTrue(!outcome.candidateRetrievalComplete());
  }

  @Test
  public void boundedSearchUsesProvenDeletionRadiusAndFiltersBeyondTheBound() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    List<ProtectedIndexStrategy> strategies =
        List.of(new ProtectedDeletionStrategy(new ExactBlindIndexer(), 1, 64, 256));
    EncryptedFuzzySearchService service =
        new EncryptedFuzzySearchService(
            keyDeriver(new AtomicInteger()),
            new AuthenticatedFieldCipher(),
            new CanonicalTextNormalizer(),
            strategies,
            repository,
            explicitPolicy(strategies));
    service.indexValue("tenant-a", "name", "one-edit", "key-v1", "a");
    service.indexValue("tenant-a", "name", "two-edits", "key-v1", "ba");

    SearchOutcome outcome =
        service.search(SearchRequest.boundedFuzzy("tenant-a", "name", "key-v1", "ab", 1, 10, 0.0));

    assertEquals(
        outcome.matches().stream().map(FuzzySearchResult::recordId).toList(), List.of("one-edit"));
    assertEquals(outcome.candidateCompleteness(), CandidateCompleteness.boundedEditDistance(1));
    assertTrue(outcome.candidateRetrievalComplete());
    assertFalse(outcome.candidateLimitExceeded());
  }

  @Test
  public void approximateSearchAlwaysReportsBestEffortCompleteness() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");

    SearchOutcome outcome =
        service.search(SearchRequest.approximate("tenant-a", "name", "key-v1", "Alice", 10, 0.5));

    assertEquals(outcome.candidateCompleteness(), CandidateCompleteness.bestEffort());
    assertFalse(outcome.candidateRetrievalComplete());
    assertFalse(outcome.candidateLimitExceeded());
  }

  @Test
  public void explicitKnownAliasesMatchAndReportOriginWithoutRetainingAliasText() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    SearchPolicy policy = expansionPolicy(true, Set.of());
    EncryptedFuzzySearchService service = serviceWithPolicy(repository, policy);
    service.indexValue("tenant-a", "name", "robert", "key-v1", "Robert");
    SearchAlias alias = new SearchAlias("Robert", "crm-contact-alias", "2026-10");
    SearchRequest request =
        SearchRequest.exact("tenant-a", "name", "key-v1", "Bob", 10)
            .withExpansions(SearchExpansionOptions.aliases(List.of(alias)));

    SearchOutcome outcome = service.search(request);

    assertEquals(outcome.matches().getFirst().recordId(), "robert");
    assertEquals(
        outcome.expansions(), List.of(ExpansionProvenance.alias("crm-contact-alias", "2026-10")));
    assertTrue(!request.toString().contains("Bob"));
    assertTrue(!alias.toString().contains("Robert"));
    assertTrue(
        repository.documents.getFirst().protectedTokens().stream()
            .noneMatch(
                token -> Arrays.equals("Bob".getBytes(StandardCharsets.UTF_8), token.value())));
  }

  @Test
  public void languageProviderSelectionIsExplicitVersionedAndUsedForIndexAndSearch()
      throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    SearchPolicy policy = expansionPolicy(false, Set.of("icu4j"));
    EncryptedFuzzySearchService service = serviceWithPolicy(repository, policy);
    LanguageProviderSelection selection = new LanguageProviderSelection("icu4j", "zh-Hans");
    service.indexValueWithLanguageProvider(
        "tenant-a", "name", "greeting", "key-v1", "你好", selection);
    SearchRequest request =
        SearchRequest.exact("tenant-a", "name", "key-v1", "nihao", 10)
            .withExpansions(SearchExpansionOptions.language(selection));

    SearchOutcome outcome = service.search(request);

    assertEquals(outcome.matches().getFirst().recordId(), "greeting");
    assertEquals(
        outcome.expansions(),
        List.of(
            ExpansionProvenance.languageProvider(
                "icu4j", "efs-translit-zh-Hans-icu4j-78.3-v2", "zh-Hans")));
    assertEquals(
        repository.documents.getFirst().transformVersion(),
        "icu4j-efs-translit-zh-Hans-icu4j-78.3-v2");
  }

  @Test
  public void expansionPolicyRejectsAliasesAndProvidersBeforeRepositoryAccess() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    SearchRequest aliasRequest =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "Bob", 10, 0.0)
            .withExpansions(
                SearchExpansionOptions.aliases(List.of(new SearchAlias("Robert", "crm", "v1"))));
    SearchRequest providerRequest =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "nihao", 10, 0.0)
            .withExpansions(
                SearchExpansionOptions.language(new LanguageProviderSelection("icu4j", "zh-Hans")));

    assertThrows(IllegalArgumentException.class, () -> service.search(aliasRequest));
    assertThrows(IllegalArgumentException.class, () -> service.search(providerRequest));
    assertEquals(repository.findCandidateCalls, 0);
  }

  @Test
  public void unsupportedLanguageAndUnconfiguredProviderFailClosed() {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service =
        serviceWithPolicy(repository, expansionPolicy(false, Set.of("icu4j")));
    SearchRequest unsupported =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "こんにちは", 10, 0.0)
            .withExpansions(
                SearchExpansionOptions.language(new LanguageProviderSelection("icu4j", "ja-JP")));
    SearchRequest missing =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "text", 10, 0.0)
            .withExpansions(
                SearchExpansionOptions.language(new LanguageProviderSelection("missing", "en")));

    assertThrows(IllegalArgumentException.class, () -> service.search(unsupported));
    assertThrows(IllegalArgumentException.class, () -> service.search(missing));
    assertEquals(repository.findCandidateCalls, 0);
  }

  @Test
  public void rejectsUnapprovedStrategiesWhenConstructingServiceUnderDefaultPolicy() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptedFuzzySearchService(
                keyDeriver(new AtomicInteger()),
                new AuthenticatedFieldCipher(),
                new CanonicalTextNormalizer(),
                List.of(new ProtectedDeletionStrategy(new ExactBlindIndexer())),
                new InMemoryRepository()));
  }

  @Test
  public void indexesEncryptedValuesAndReturnsVerifiedTypoMatchesInStableOrder() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "alicia", "key-v1", "Alicia");
    service.indexValue("tenant-a", "name", "maria", "key-v1", "Maria");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "Alicia", 10, 0.5);

    assertEquals(results.size(), 2);
    assertEquals(results.get(0).recordId(), "alicia");
    assertEquals(results.get(0).value(), "Alicia");
    assertEquals(results.get(0).similarity(), 1.0);
    assertEquals(results.get(1).recordId(), "alice");
    assertTrue(results.get(1).similarity() >= 0.5);
    assertTrue(results.get(0).overlapCount() > 0);
    byte[] plaintext = "Alice".getBytes(StandardCharsets.UTF_8);
    assertTrue(!Arrays.equals(plaintext, repository.documents.getFirst().ciphertext()));
    assertTrue(
        repository.documents.getFirst().protectedTokens().stream()
            .noneMatch(token -> Arrays.equals(plaintext, token.value())));
  }

  @Test
  public void searchesAcrossKeyVersionsDuringRotationAndReturnsMixedVersionMatches()
      throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "old-record", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "new-record", "key-v2", "Alicia");

    List<FuzzySearchResult> results =
        service.searchAcrossKeyVersions(
            "tenant-a", "name", List.of("key-v1", "key-v2"), "Alicia", 10, 0.5);

    assertEquals(
        results.stream().map(FuzzySearchResult::recordId).toList(),
        List.of("new-record", "old-record"));
    assertEquals(
        results.stream().map(FuzzySearchResult::recordId).distinct().count(), results.size());
    assertEquals(
        service
            .searchAcrossKeyVersions("tenant-a", "name", List.of("key-v2"), "Alicia", 10, 1.0)
            .getFirst()
            .recordId(),
        "new-record");
  }

  @Test
  public void keyVersionSearchRequiresABoundedUniqueVersionList() {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.searchAcrossKeyVersions("tenant-a", "name", List.of(), "Alice", 10, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.searchAcrossKeyVersions(
                "tenant-a", "name", List.of("key-v1", "key-v1"), "Alice", 10, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.searchAcrossKeyVersions(
                "tenant-a",
                "name",
                java.util.Collections.nCopies(
                    EncryptedFuzzySearchService.MAX_KEY_VERSIONS_PER_SEARCH + 1, "key-v1"),
                "Alice",
                10,
                0.0));
    assertThrows(
        NullPointerException.class,
        () -> service.searchAcrossKeyVersions("tenant-a", "name", null, "Alice", 10, 0.0));
  }

  @Test
  public void keepsTheBestVerifiedMatchWhenVersionsContainTheSameRecordId() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    repository.preserveMultipleKeyVersions();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "alice", "key-v2", "Alicia");

    List<FuzzySearchResult> results =
        service.searchAcrossKeyVersions(
            "tenant-a", "name", List.of("key-v1", "key-v2"), "Alicia", 10, 0.5);

    assertEquals(results.size(), 1);
    assertEquals(results.getFirst().recordId(), "alice");
    assertEquals(results.getFirst().value(), "Alicia");
    assertEquals(results.getFirst().similarity(), 1.0);
  }

  @Test
  public void exactAndNoMatchQueriesHaveExplicitResults() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    service.indexValue("tenant-a", "name", "one", "key-v1", "alice");

    List<FuzzySearchResult> exact = service.search("tenant-a", "name", "key-v1", "alice", 10, 1.0);
    List<FuzzySearchResult> noMatch =
        service.search("tenant-a", "name", "key-v1", "not-present", 10, 0.5);

    assertEquals(exact.size(), 1);
    assertEquals(exact.get(0).similarity(), 1.0);
    assertTrue(noMatch.isEmpty());
  }

  @Test
  public void normalizesCanonicalUnicodeBeforeEncryptionAndSearch() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    service.indexValue("tenant-a", "name", "accented", "key-v1", "Cafe\u0301");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "Caf\u00e9", 10, 1.0);

    assertEquals(results.size(), 1);
    assertEquals(results.get(0).value(), "Caf\u00e9");
  }

  @Test
  public void indexesAndSearchesChineseAndToneFoldedLatinWithTheSameProfile() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    service.indexValue("tenant-a", "name", "greeting", "key-v1", "你好", chinese);

    List<FuzzySearchResult> hanQuery =
        service.search("tenant-a", "name", "key-v1", "你好", 10, 1.0, chinese);
    List<FuzzySearchResult> pinyinQuery =
        service.search("tenant-a", "name", "key-v1", "ni hao", 10, 1.0, chinese);
    List<FuzzySearchResult> pinyinWithTones =
        service.search("tenant-a", "name", "key-v1", "nǐ hǎo", 10, 1.0, chinese);
    List<FuzzySearchResult> compactPinyin =
        service.search("tenant-a", "name", "key-v1", "nihao", 10, 1.0, chinese);

    assertEquals(hanQuery.size(), 1);
    assertEquals(pinyinQuery.size(), 1);
    assertEquals(pinyinQuery.get(0).value(), "你好");
    assertEquals(pinyinQuery.get(0).similarity(), 1.0);
    assertEquals(pinyinWithTones.size(), 1);
    assertEquals(compactPinyin.size(), 1);
    assertTrue(service.search("tenant-a", "name", "key-v1", "ni hao", 10, 1.0).isEmpty());
  }

  @Test
  public void multilingualTransformVersionsMustMatchForIndexAndQuery() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    service.indexValue("tenant-a", "name", "greeting", "key-v1", "你好", chinese);
    repository.overrideTransform("older-profile-version");

    assertThrows(
        GeneralSecurityException.class,
        () -> service.search("tenant-a", "name", "key-v1", "你好", 10, 0.0, chinese));
  }

  @Test
  public void multilingualEntryPointsRequireAnExplicitProfile() {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());

    assertThrows(
        NullPointerException.class,
        () -> service.indexValue("tenant-a", "name", "record", "key-v1", "你好", null));
    assertThrows(
        NullPointerException.class,
        () -> service.search("tenant-a", "name", "key-v1", "你好", 10, 0.0, null));
  }

  @Test
  public void multilingualSearchCorpusIncludesPositiveAndNegativeCrossScriptCases()
      throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    service.indexValue("tenant-a", "name", "greeting", "key-v1", "你好", chinese);

    try (InputStream stream =
        getClass().getResourceAsStream("/multilingual-cross-script-search-corpus.tsv")) {
      assertTrue(stream != null, "cross-script search corpus resource must exist");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] fields = line.split("\\t", -1);
          assertEquals(fields.length, 4, "each row has outcome, tag, query, and expected record");
          switch (fields[0]) {
            case "positive" -> {
              TransliterationProfile profile = TransliterationProfile.forLanguageTag(fields[1]);
              List<FuzzySearchResult> results =
                  service.search("tenant-a", "name", "key-v1", fields[2], 10, 1.0, profile);
              assertEquals(results.size(), 1);
              assertEquals(results.getFirst().recordId(), fields[3]);
            }
            case "negative" -> {
              TransliterationProfile profile = TransliterationProfile.forLanguageTag(fields[1]);
              assertEquals(
                  service.search("tenant-a", "name", "key-v1", fields[2], 10, 0.0, profile),
                  List.of(),
                  "unexpected Chinese candidate for cross-script query: " + fields[2]);
            }
            case "unsupported" ->
                assertThrows(
                    IllegalArgumentException.class,
                    () -> TransliterationProfile.forLanguageTag(fields[1]));
            default ->
                throw new AssertionError("unknown cross-script corpus outcome: " + fields[0]);
          }
        }
      }
    }
  }

  @Test
  public void transliterationHomophonesCanReturnDistinctChineseWords() throws Exception {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    service.indexValue("tenant-a", "name", "particle", "key-v1", "吗", chinese);
    service.indexValue("tenant-a", "name", "mother", "key-v1", "妈", chinese);

    List<FuzzySearchResult> matches =
        service.search("tenant-a", "name", "key-v1", "ma", 10, 1.0, chinese);

    assertEquals(matches.size(), 2);
    assertTrue(matches.stream().anyMatch(result -> result.recordId().equals("particle")));
    assertTrue(matches.stream().anyMatch(result -> result.recordId().equals("mother")));
    assertTrue(matches.stream().allMatch(result -> result.similarity() == 1.0));
  }

  @Test
  public void rejectsMultilingualIndexOrQueryThatExceedsAggregateTokenLimit() {
    EncryptedFuzzySearchService service = service(new AtomicInteger(), new InMemoryRepository());
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    String highEntropyHanText = sequentialHanText(800);

    IllegalArgumentException indexFailure =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                service.indexValue(
                    "tenant-a", "name", "too-large", "key-v1", highEntropyHanText, chinese));
    assertTrue(indexFailure.getMessage().contains("multilingual variants exceed maximum"));
    IllegalArgumentException queryFailure =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                service.search("tenant-a", "name", "key-v1", highEntropyHanText, 10, 0.0, chinese));
    assertTrue(queryFailure.getMessage().contains("multilingual variants exceed maximum"));
  }

  private static String sequentialHanText(int codePointCount) {
    StringBuilder text = new StringBuilder(codePointCount);
    for (int offset = 0; offset < codePointCount; offset++) {
      text.appendCodePoint(0x4e00 + offset);
    }
    return text.toString();
  }

  @Test
  public void appliesTenantAndFieldIsolationBeforeDecryptingCandidates() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "same-name", "key-v1", "alexander");
    service.indexValue("tenant-b", "name", "other-tenant", "key-v1", "alexander");
    service.indexValue("tenant-a", "email", "other-field", "key-v1", "alexander");

    List<FuzzySearchResult> results =
        service.search("tenant-a", "name", "key-v1", "alexander", 10, 1.0);

    assertEquals(results.size(), 1);
    assertEquals(results.get(0).recordId(), "same-name");
  }

  @Test
  public void updatesAndDeletesReplaceCiphertextAndTokens() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "record", "key-v1", "alice");
    service.indexValue("tenant-a", "name", "record", "key-v1", "bob");

    assertTrue(service.search("tenant-a", "name", "key-v1", "alice", 10, 0.5).isEmpty());
    assertEquals(
        service.search("tenant-a", "name", "key-v1", "bob", 10, 1.0).get(0).value(), "bob");

    service.delete("tenant-a", "name", "record");
    assertTrue(service.search("tenant-a", "name", "key-v1", "bob", 10, 0.5).isEmpty());
  }

  @Test
  public void decryptsOnlyShortlistedCandidates() throws Exception {
    AtomicInteger keyLookups = new AtomicInteger();
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(keyLookups, repository);
    service.indexValue("tenant-a", "name", "one", "key-v1", "alex");
    service.indexValue("tenant-a", "name", "two", "key-v1", "alexa");
    service.indexValue("tenant-a", "name", "three", "key-v1", "alexander");
    keyLookups.set(0);

    List<FuzzySearchResult> results = service.search("tenant-a", "name", "key-v1", "alex", 1, 0.0);

    assertEquals(results.size(), 1);
    assertEquals(keyLookups.get(), 2);
  }

  @Test
  public void rejectsUnsupportedTransformAndInvalidRequestBounds() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    repository.insertWithTransform("unsupported-transform");

    assertThrows(
        GeneralSecurityException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 1001, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 1.1));
    assertThrows(
        NullPointerException.class,
        () -> service.search("tenant-a", "name", "key-v1", null, 10, 0.0));
  }

  @Test
  public void rejectsCiphertextReplayedUnderAnotherRecordIdentity() throws Exception {
    InMemoryRepository repository = new InMemoryRepository();
    EncryptedFuzzySearchService service = service(new AtomicInteger(), repository);
    service.indexValue("tenant-a", "name", "record-1", "key-v1", "alpha");
    repository.overrideCandidateIdentity("record-2");

    assertThrows(
        InvalidCiphertextException.class,
        () -> service.search("tenant-a", "name", "key-v1", "alpha", 10, 0.0));
  }

  @Test
  public void serviceRequiresCorrectStrategiesAndNonNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncryptedFuzzySearchService(
                keyDeriver(new AtomicInteger()),
                new AuthenticatedFieldCipher(),
                new ExactBlindIndexer(),
                new CanonicalTextNormalizer(),
                new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
                new InMemoryRepository()));
    assertThrows(
        NullPointerException.class,
        () ->
            new EncryptedFuzzySearchService(
                null,
                new AuthenticatedFieldCipher(),
                new ExactBlindIndexer(),
                new CanonicalTextNormalizer(),
                new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
                new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
                new InMemoryRepository()));
  }

  private static EncryptedFuzzySearchService service(
      AtomicInteger keyLookups, InMemoryRepository repository) {
    return new EncryptedFuzzySearchService(
        keyDeriver(keyLookups),
        new AuthenticatedFieldCipher(),
        new ExactBlindIndexer(),
        new CanonicalTextNormalizer(),
        new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
        new ProtectedNgramStrategy(3, new NgramBlindIndexer()),
        repository);
  }

  private static SearchPolicy explicitPolicy(List<? extends ProtectedIndexStrategy> strategies) {
    Set<String> allowedStrategyIds =
        strategies.stream()
            .map(strategy -> strategy.strategyId(null))
            .collect(java.util.stream.Collectors.toSet());
    return new SearchPolicy(allowedStrategyIds, Set.of(IndexLeakage.values()));
  }

  private static SearchPolicy expansionPolicy(
      boolean aliasesAllowed, Set<String> languageProviderIds) {
    return new SearchPolicy(
        Set.of("efs-exact", "efs-ngram-2", "efs-ngram-3"),
        Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY, IndexLeakage.TOKEN_OVERLAP),
        aliasesAllowed,
        languageProviderIds);
  }

  private static EncryptedFuzzySearchService serviceWithPolicy(
      InMemoryRepository repository, SearchPolicy policy) {
    return new EncryptedFuzzySearchService(
        keyDeriver(new AtomicInteger()),
        new AuthenticatedFieldCipher(),
        new CanonicalTextNormalizer(),
        List.of(
            new ProtectedExactStrategy(new ExactBlindIndexer()),
            new ProtectedNgramStrategy(2, new NgramBlindIndexer()),
            new ProtectedNgramStrategy(3, new NgramBlindIndexer())),
        repository,
        policy);
  }

  private static ScopedKeyDeriver keyDeriver(AtomicInteger keyLookups) {
    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x37);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    return new ScopedKeyDeriver(
        ignored -> {
          keyLookups.incrementAndGet();
          return rootKey;
        });
  }

  private static final class InMemoryRepository implements TokenIndexRepository {
    private final List<EncryptedSearchDocument> documents = new ArrayList<>();
    private String transformOverride;
    private String recordIdOverride;
    private int lastCandidateCount;
    private boolean preserveVersions;
    private int findCandidateCalls;

    @Override
    public void initializeSchema() {}

    @Override
    public void upsert(EncryptedSearchDocument document) {
      if (!preserveVersions) {
        delete(document.tenantId(), document.fieldId(), document.recordId());
      }
      documents.add(document);
    }

    @Override
    public List<SearchCandidate> findCandidates(
        String tenantId,
        String fieldId,
        String keyId,
        List<ProtectedToken> queryTokens,
        int limit) {
      findCandidateCalls++;
      if (recordIdOverride != null && !documents.isEmpty()) {
        EncryptedSearchDocument document = documents.getFirst();
        return List.of(
            new SearchCandidate(
                recordIdOverride,
                document.keyId(),
                document.transformVersion(),
                document.ciphertext(),
                1));
      }
      List<SearchCandidate> matches = new ArrayList<>();
      for (EncryptedSearchDocument document : documents) {
        if (!document.tenantId().equals(tenantId)
            || !document.fieldId().equals(fieldId)
            || !document.keyId().equals(keyId)) {
          continue;
        }
        long overlap = queryTokens.stream().filter(document.protectedTokens()::contains).count();
        if (overlap > 0) {
          matches.add(
              new SearchCandidate(
                  document.recordId(),
                  document.keyId(),
                  transformOverride == null ? document.transformVersion() : transformOverride,
                  document.ciphertext(),
                  (int) overlap));
        }
      }
      matches.sort(
          Comparator.comparingInt(SearchCandidate::overlapCount)
              .reversed()
              .thenComparing(SearchCandidate::recordId));
      List<SearchCandidate> results = matches.stream().limit(limit).toList();
      lastCandidateCount = results.size();
      return results;
    }

    @Override
    public void delete(String tenantId, String fieldId, String recordId) {
      documents.removeIf(
          document ->
              document.tenantId().equals(tenantId)
                  && document.fieldId().equals(fieldId)
                  && document.recordId().equals(recordId));
    }

    private void insertWithTransform(String transformVersion) throws Exception {
      EncryptedFuzzySearchService service =
          EncryptedFuzzySearchServiceTest.service(new AtomicInteger(), this);
      service.indexValue("tenant-a", "name", "bad-transform", "key-v1", "alpha");
      transformOverride = transformVersion;
    }

    private void overrideCandidateIdentity(String recordId) {
      recordIdOverride = recordId;
    }

    private void overrideTransform(String transformVersion) {
      transformOverride = transformVersion;
    }

    private void preserveMultipleKeyVersions() {
      preserveVersions = true;
    }
  }
}
