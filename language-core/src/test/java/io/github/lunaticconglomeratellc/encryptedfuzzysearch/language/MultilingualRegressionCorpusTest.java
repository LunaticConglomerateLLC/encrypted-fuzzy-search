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

/** Regression corpus for supported transliteration and intentionally unsupported scripts. */
public class MultilingualRegressionCorpusTest {
  private static final String CORPUS = "/multilingual-regression-corpus.tsv";

  @Test
  public void explicitLanguageCorpusProducesExpectedVariantsOrRejectsUnsupportedProfiles()
      throws IOException {
    Icu4jTransliterationProvider provider = new Icu4jTransliterationProvider();
    try (InputStream stream = getClass().getResourceAsStream(CORPUS)) {
      assertTrue(stream != null, "regression corpus resource must exist");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] fields = line.split("\\t", -1);
          assertEquals(fields.length, 3, "each corpus row has tag, input, and expected value");
          if ("UNSUPPORTED".equals(fields[2])) {
            assertThrows(
                fields[0] + " must not be auto-detected or handled by the Chinese profile",
                IllegalArgumentException.class,
                () -> TransliterationProfile.forLanguageTag(fields[0]));
          } else {
            TransliterationProfile profile = TransliterationProfile.forLanguageTag(fields[0]);
            List<TransliterationVariant> variants = provider.variants(fields[1], profile);
            assertTrue(
                variants.stream().anyMatch(variant -> variant.value().equals(fields[2])),
                fields[0] + " should emit the expected romanized form for " + fields[1]);
          }
        }
      }
    }
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
