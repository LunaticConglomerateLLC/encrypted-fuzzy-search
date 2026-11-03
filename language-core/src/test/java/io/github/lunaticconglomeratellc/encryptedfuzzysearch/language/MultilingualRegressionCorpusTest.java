package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.testng.annotations.Test;

/** Regression corpus for explicit provider selection and unsupported language boundaries. */
public class MultilingualRegressionCorpusTest {
  private static final String CORPUS = "/multilingual-regression-corpus.tsv";

  @Test
  public void explicitLanguageCorpusProducesExpectedVariantsOrRejectsUnsupportedProfiles()
      throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(CORPUS)) {
      assertTrue(stream != null, "regression corpus resource must exist");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] fields = line.split("\\t", -1);
          assertEquals(fields.length, 4, "each corpus row has tag, provider, input, and expected");
          if ("UNSUPPORTED".equals(fields[3])) {
            assertThrows(
                fields[0] + " must not be auto-detected or handled by the Chinese profile",
                IllegalArgumentException.class,
                () -> providerFor(fields[1]).transformId(fields[0]));
          } else {
            LanguageVariantProvider selected = providerFor(fields[1]);
            List<TransliterationVariant> variants =
                selected.variantsForLanguage(fields[2], fields[0]);
            if ("LATIN".equals(fields[3])) {
              assertTrue(
                  variants.stream()
                      .anyMatch(variant -> variant.kind() == TransliterationVariant.Kind.LATIN),
                  fields[0] + " should emit a Latin form for " + fields[2] + "; got " + variants);
            } else {
              assertTrue(
                  variants.stream().anyMatch(variant -> variant.value().equals(fields[3])),
                  fields[0]
                      + " should emit "
                      + fields[3]
                      + " for "
                      + fields[2]
                      + "; got "
                      + variants);
            }
          }
        }
      }
    }
  }

  private static LanguageVariantProvider providerFor(String providerId) {
    return switch (providerId) {
      case Icu4jTransliterationProvider.PROVIDER_ID -> new Icu4jTransliterationProvider();
      case KuromojiJapaneseVariantProvider.PROVIDER_ID -> new KuromojiJapaneseVariantProvider();
      default -> throw new IllegalArgumentException("unknown provider in regression corpus");
    };
  }

  @Test
  public void representativeVariantCountsStayWithinTheExpectedExpansionBudget() {
    Icu4jTransliterationProvider icu = new Icu4jTransliterationProvider();
    KuromojiJapaneseVariantProvider kuromoji = new KuromojiJapaneseVariantProvider();

    assertEquals(icu.variantsForLanguage("重庆银行", "zh-Hans").size(), 4);
    assertEquals(kuromoji.variantsForLanguage("東京都新宿区", "ja-JP").size(), 3);
    assertEquals(icu.variantsForLanguage("القاهرة", "ar-Arab").size(), 2);
    assertEquals(icu.variantsForLanguage("नमस्ते दुनिया", "hi-Deva").size(), 3);
    assertEquals(icu.variantsForLanguage("தமிழ்நாடு", "ta-Taml").size(), 2);
  }

  @Test
  public void transliterationIsNotTranslationAndHomophonesCanCreateFalseMatches() {
    Icu4jTransliterationProvider provider = new Icu4jTransliterationProvider();
    TransliterationProfile chinese =
        TransliterationProfile.forLanguageTag(
            TransliterationProfile.SIMPLIFIED_CHINESE_LANGUAGE_TAG);
    List<TransliterationVariant> greeting = provider.variants("你好", chinese);
    assertFalse(greeting.stream().anyMatch(variant -> variant.value().equals("hello")));

    List<TransliterationVariant> questionParticle = provider.variants("吗", chinese);
    List<TransliterationVariant> mother = provider.variants("妈", chinese);
    assertTrue(questionParticle.stream().anyMatch(variant -> variant.value().equals("ma")));
    assertTrue(mother.stream().anyMatch(variant -> variant.value().equals("ma")));
    assertEquals(
        questionParticle.stream()
            .filter(variant -> variant.value().equals("ma"))
            .findFirst()
            .orElseThrow()
            .value(),
        mother.stream()
            .filter(variant -> variant.value().equals("ma"))
            .findFirst()
            .orElseThrow()
            .value(),
        "tone folding can merge distinct Chinese words; final plaintext verification is required");
  }
}
