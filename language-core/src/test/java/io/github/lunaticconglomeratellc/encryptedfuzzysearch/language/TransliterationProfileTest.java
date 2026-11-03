package io.github.lunaticconglomeratellc.encryptedfuzzysearch.language;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

public class TransliterationProfileTest {
  @Test
  public void resolvesCanonicalSimplifiedChineseLanguageTagAndStableVersion() {
    TransliterationProfile profile = TransliterationProfile.forLanguageTag("zh-hans");

    assertEquals(profile.languageTag(), "zh-Hans");
    assertEquals(profile.transformId(), "efs-translit-zh-Hans-icu4j-78.3-v2");
    assertSame(
        TransliterationProfile.forLanguageTag("zh-Hans"),
        TransliterationProfile.forLanguageTag("zh-hans"));
  }

  @Test
  public void requiresExplicitSupportedLanguageTag() {
    assertThrows(NullPointerException.class, () -> TransliterationProfile.forLanguageTag(null));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("en-US"));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("zh-Hant"));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("zh_Hans"));
    assertThrows(
        IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("ja-JP"));
  }

  @Test
  public void resolvesExplicitArabicAndIndicScriptProfiles() {
    assertEquals(
        TransliterationProfile.forLanguageTag("ar-Arab").transliteratorId(), "Arabic-Latin");
    assertEquals(
        TransliterationProfile.forLanguageTag("hi-Deva").transliteratorId(), "Devanagari-Latin");
    assertEquals(
        TransliterationProfile.forLanguageTag("bn-Beng").transliteratorId(), "Bengali-Latin");
    assertEquals(
        TransliterationProfile.forLanguageTag("ta-Taml").transliteratorId(), "Tamil-Latin");
    assertEquals(
        TransliterationProfile.forLanguageTag("ar-Arab").transformId(),
        "efs-translit-ar-Arab-icu4j-78.3-v1");
    assertThrows(IllegalArgumentException.class, () -> TransliterationProfile.forLanguageTag("ar"));
  }

  @Test
  public void variantRequiresAllFields() {
    assertNotNull(
        new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, "canonical-v1", "你好"));
    assertThrows(
        NullPointerException.class, () -> new TransliterationVariant(null, "canonical-v1", "你好"));
    assertThrows(
        NullPointerException.class,
        () -> new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, null, "你好"));
    assertThrows(
        NullPointerException.class,
        () ->
            new TransliterationVariant(TransliterationVariant.Kind.ORIGINAL, "canonical-v1", null));
  }
}
