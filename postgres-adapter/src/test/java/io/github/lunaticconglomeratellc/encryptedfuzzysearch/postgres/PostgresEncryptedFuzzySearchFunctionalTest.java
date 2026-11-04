package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.Icu4jTransliterationProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.KuromojiJapaneseVariantProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.LanguageVariantProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.TransliterationProfile;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.TransliterationVariant;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.FuzzySearchResult;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.IndexLeakage;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.LanguageProviderSelection;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedExactStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchAlias;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchExpansionOptions;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchOutcome;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchPolicy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchRequest;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** End-to-end database tests with deterministic synthetic fixtures and test-only key material. */
public class PostgresEncryptedFuzzySearchFunctionalTest {
  private final PostgresTestDataSource dataSource = new PostgresTestDataSource();
  private final PostgresTokenIndexRepository repository =
      new PostgresTokenIndexRepository(dataSource);
  private final EncryptedFuzzySearchService service = createService(repository);

  @BeforeClass
  public void initializeSchema() throws Exception {
    repository.initializeSchema();
  }

  @BeforeMethod
  public void clearPreviousFixture() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("TRUNCATE TABLE efs_encrypted_records CASCADE");
    }
  }

  @Test
  public void seedsWritesSearchesAndVerifiesFuzzyMatchesAgainstPostgres() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "entity-002", "key-v1", "Alicia");
    service.indexValue("tenant-a", "name", "entity-003", "key-v1", "Maria");

    List<FuzzySearchResult> matches =
        service.search("tenant-a", "name", "key-v1", "Alica", 25, 0.65);

    assertEquals(matches.size(), 2);
    assertEquals(matches.get(0).recordId(), "entity-002");
    assertEquals(matches.get(0).value(), "Alicia");
    assertEquals(matches.get(1).recordId(), "entity-001");
    assertEquals(matches.get(1).value(), "Alice");
    assertTrue(matches.get(0).overlapCount() > 0);
  }

  @Test
  public void readsBackExactMatchesAndReturnsNoResultsForNoMatchQueries() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Caf\u00e9");

    List<FuzzySearchResult> exact =
        service.search("tenant-a", "name", "key-v1", "Cafe\u0301", 25, 1.0);
    List<FuzzySearchResult> none = service.search("tenant-a", "name", "key-v1", "zzzz", 25, 0.5);

    assertEquals(exact.size(), 1);
    assertEquals(exact.get(0).value(), "Caf\u00e9");
    assertTrue(none.isEmpty());
  }

  @Test
  public void searchesChineseIndexUsingToneFoldedPinyinAgainstPostgres() throws Exception {
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    service.indexValue("tenant-a", "name", "entity-cn-001", "key-v1", "你好", chinese);

    List<FuzzySearchResult> matches =
        service.search("tenant-a", "name", "key-v1", "ni hao", 25, 1.0, chinese);

    assertEquals(matches.size(), 1);
    assertEquals(matches.get(0).recordId(), "entity-cn-001");
    assertEquals(matches.get(0).value(), "你好");
    assertEquals(matches.get(0).similarity(), 1.0);
  }

  @Test
  public void isolatesTenantsAndFieldsAndKeepsUpdatesAndDeletesConsistent() throws Exception {
    service.indexValue("tenant-a", "name", "entity-001", "key-v1", "Victoria");
    service.indexValue("tenant-b", "name", "entity-002", "key-v1", "Victoria");
    service.indexValue("tenant-a", "email", "entity-003", "key-v1", "Victoria");

    assertEquals(service.search("tenant-a", "name", "key-v1", "Victoria", 25, 1.0).size(), 1);
    assertEquals(
        service.search("tenant-b", "name", "key-v1", "Victoria", 25, 1.0).get(0).recordId(),
        "entity-002");

    service.indexValue("tenant-a", "name", "entity-001", "key-v2", "Victor");
    assertTrue(service.search("tenant-a", "name", "key-v1", "Victoria", 25, 0.5).isEmpty());
    assertEquals(
        service.search("tenant-a", "name", "key-v2", "Victor", 25, 1.0).get(0).value(), "Victor");
    service.delete("tenant-a", "name", "entity-001");
    assertTrue(service.search("tenant-a", "name", "key-v2", "Victor", 25, 0.5).isEmpty());
  }

  @Test
  public void schemaContainsNoPlaintextOrRawGramColumns() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      ResultSetMetaData recordColumns;
      try (var result = statement.executeQuery("SELECT * FROM efs_encrypted_records LIMIT 0")) {
        recordColumns = result.getMetaData();
      }
      for (int index = 1; index <= recordColumns.getColumnCount(); index++) {
        String name = recordColumns.getColumnName(index).toLowerCase(java.util.Locale.ROOT);
        assertFalse(name.contains("plaintext"));
        assertFalse(name.contains("normalized"));
        assertFalse(name.contains("value"));
      }
      try (var result = statement.executeQuery("SELECT * FROM efs_protected_tokens LIMIT 0")) {
        ResultSetMetaData tokenColumns = result.getMetaData();
        for (int index = 1; index <= tokenColumns.getColumnCount(); index++) {
          String name = tokenColumns.getColumnName(index).toLowerCase(java.util.Locale.ROOT);
          assertFalse(name.contains("gram"));
          assertFalse(name.contains("plaintext"));
        }
      }
    }
  }

  @Test(dataProvider = "supportedLanguageProfiles")
  public void transliteratedLanguageProfilesRoundTripThroughPostgres(
      String providerId, String languageTag, String sourceText) throws Exception {
    EncryptedFuzzySearchService multilingualService = createConfiguredService(repository, false);
    LanguageProviderSelection selection = new LanguageProviderSelection(providerId, languageTag);
    LanguageVariantProvider provider = providerFor(providerId);
    String latinQuery =
        provider.variantsForLanguage(sourceText, languageTag).stream()
            .filter(variant -> variant.kind() == TransliterationVariant.Kind.LATIN)
            .map(TransliterationVariant::value)
            .findFirst()
            .orElseThrow();

    multilingualService.indexValueWithLanguageProvider(
        "tenant-a", "name", "language-match", "key-v1", sourceText, selection);
    multilingualService.indexValueWithLanguageProvider(
        "tenant-a", "name", "language-decoy", "key-v1", "zzzz unrelated", selection);

    SearchRequest translatedQuery =
        SearchRequest.approximate("tenant-a", "name", "key-v1", latinQuery, 25, 1.0)
            .withExpansions(SearchExpansionOptions.language(selection));
    SearchOutcome matching = multilingualService.search(translatedQuery);
    assertEquals(matching.matches().size(), 1, languageTag + " returns one matching entity");
    assertEquals(matching.matches().get(0).recordId(), "language-match");
    assertEquals(matching.matches().get(0).value(), sourceText);

    SearchRequest noMatchQuery =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "no-such-language-record", 25, 1.0)
            .withExpansions(SearchExpansionOptions.language(selection));
    assertTrue(multilingualService.search(noMatchQuery).matches().isEmpty(), languageTag);
  }

  @DataProvider(name = "supportedLanguageProfiles")
  public Object[][] supportedLanguageProfiles() {
    return new Object[][] {
      {Icu4jTransliterationProvider.PROVIDER_ID, "zh-Hans", "你好"},
      {KuromojiJapaneseVariantProvider.PROVIDER_ID, "ja-JP", "東京"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "ar-Arab", "القاهرة"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "hi-Deva", "नमस्ते"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "bn-Beng", "বাংলা"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "gu-Gujr", "ગુજરાત"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "pa-Guru", "ਪੰਜਾਬ"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "ta-Taml", "தமிழ்"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "te-Telu", "తెలుగు"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "kn-Knda", "ಕನ್ನಡ"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "ml-Mlym", "മലയാളം"},
      {Icu4jTransliterationProvider.PROVIDER_ID, "or-Orya", "ଓଡ଼ିଶା"}
    };
  }

  @Test
  public void aliasExpansionMeasuresMixedScriptHardNegativeAndPreservesScope() throws Exception {
    EncryptedFuzzySearchService aliasService = createConfiguredService(repository, true);
    aliasService.indexValue("tenant-a", "name", "paypal-latin", "key-v1", "PayPal");
    aliasService.indexValue("tenant-a", "name", "paypal-lookalike", "key-v1", "ΡayPal");
    aliasService.indexValue("tenant-b", "name", "other-tenant", "key-v1", "PayPal");
    aliasService.indexValue("tenant-a", "email", "other-field", "key-v1", "PayPal");

    SearchRequest request =
        SearchRequest.approximate("tenant-a", "name", "key-v1", "ΡayPal", 25, 0.8)
            .withExpansions(
                SearchExpansionOptions.aliases(
                    List.of(new SearchAlias("PayPal", "functional-fixture", "v1"))));
    SearchOutcome outcome = aliasService.search(request);
    Set<String> retrieved =
        outcome.matches().stream().map(FuzzySearchResult::recordId).collect(Collectors.toSet());
    Set<String> relevant = Set.of("paypal-latin");
    long truePositives = retrieved.stream().filter(relevant::contains).count();
    double precision = (double) truePositives / retrieved.size();
    double recall = (double) truePositives / relevant.size();

    assertEquals(retrieved, Set.of("paypal-latin", "paypal-lookalike"));
    assertEquals(precision, 0.5);
    assertEquals(recall, 1.0);
    assertFalse(retrieved.contains("other-tenant"));
    assertFalse(retrieved.contains("other-field"));
    assertThrows(IllegalArgumentException.class, () -> service.search(request));
  }

  @Test
  public void labeledFuzzyQueryReportsPrecisionAndRecallAgainstPostgres() throws Exception {
    service.indexValue("tenant-a", "name", "alice-positive", "key-v1", "Alice");
    service.indexValue("tenant-a", "name", "aline-hard-negative", "key-v1", "Aline");
    service.indexValue("tenant-a", "name", "bob-negative", "key-v1", "Bob");

    SearchOutcome outcome =
        service.search(SearchRequest.approximate("tenant-a", "name", "key-v1", "Alic", 25, 0.75));
    Set<String> retrieved =
        outcome.matches().stream().map(FuzzySearchResult::recordId).collect(Collectors.toSet());
    Set<String> relevant = Set.of("alice-positive");
    long truePositives = retrieved.stream().filter(relevant::contains).count();
    double precision = (double) truePositives / retrieved.size();
    double recall = (double) truePositives / relevant.size();

    assertTrue(outcome.candidatesVerified() >= 2, "the hard negative reaches final verification");
    assertEquals(retrieved, relevant);
    assertEquals(precision, 1.0);
    assertEquals(recall, 1.0);
  }

  private static EncryptedFuzzySearchService createConfiguredService(
      PostgresTokenIndexRepository repository, boolean aliasesAllowed) {
    NgramBlindIndexer ngramIndexer = new NgramBlindIndexer();
    List<ProtectedNgramStrategy> ngramStrategies =
        List.of(
            new ProtectedNgramStrategy(2, ngramIndexer),
            new ProtectedNgramStrategy(3, ngramIndexer));
    SearchPolicy policy =
        new SearchPolicy(
            Set.of("efs-exact", "efs-ngram-2", "efs-ngram-3"),
            Set.of(IndexLeakage.values()),
            aliasesAllowed,
            Set.of(
                Icu4jTransliterationProvider.PROVIDER_ID,
                KuromojiJapaneseVariantProvider.PROVIDER_ID));
    return new EncryptedFuzzySearchService(
        new ScopedKeyDeriver(ignored -> testRootKey()),
        new AuthenticatedFieldCipher(),
        new CanonicalTextNormalizer(),
        List.of(
            new ProtectedExactStrategy(new ExactBlindIndexer()),
            ngramStrategies.get(0),
            ngramStrategies.get(1)),
        repository,
        policy,
        List.of(new Icu4jTransliterationProvider(), new KuromojiJapaneseVariantProvider()));
  }

  private static LanguageVariantProvider providerFor(String providerId) {
    return switch (providerId) {
      case Icu4jTransliterationProvider.PROVIDER_ID -> new Icu4jTransliterationProvider();
      case KuromojiJapaneseVariantProvider.PROVIDER_ID -> new KuromojiJapaneseVariantProvider();
      default -> throw new IllegalArgumentException("unsupported provider in functional corpus");
    };
  }

  private static SecretKey testRootKey() {
    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x5a);
    return new SecretKeySpec(rootBytes, "HmacSHA256");
  }

  private static EncryptedFuzzySearchService createService(
      PostgresTokenIndexRepository repository) {
    byte[] rootBytes = new byte[32];
    Arrays.fill(rootBytes, (byte) 0x5a);
    SecretKey rootKey = new SecretKeySpec(rootBytes, "HmacSHA256");
    ScopedKeyDeriver keyDeriver = new ScopedKeyDeriver(ignored -> rootKey);
    NgramBlindIndexer ngramIndexer = new NgramBlindIndexer();
    return new EncryptedFuzzySearchService(
        keyDeriver,
        new AuthenticatedFieldCipher(),
        new ExactBlindIndexer(),
        new CanonicalTextNormalizer(),
        new ProtectedNgramStrategy(2, ngramIndexer),
        new ProtectedNgramStrategy(3, ngramIndexer),
        repository);
  }
}
