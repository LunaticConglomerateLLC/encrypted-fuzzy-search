package io.github.lunaticconglomeratellc.encryptedfuzzysearch.benchmark;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.Icu4jTransliterationProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.KuromojiJapaneseVariantProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.LanguageVariantProvider;
import java.util.concurrent.TimeUnit;
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

/** Measures explicit language transform cost on small synthetic text samples. */
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LanguageVariantBenchmark {
  @Param({
    "zh-Hans", "ja-JP", "ar-Arab", "hi-Deva", "bn-Beng", "gu-Gujr", "pa-Guru", "ta-Taml", "te-Telu",
    "kn-Knda", "ml-Mlym", "or-Orya"
  })
  public String languageTag;

  @Param({"synthetic-language-regression-v1"})
  public String inputGenerator;

  @Param({"unknown"})
  public String sourceRevision;

  @Param({"unknown"})
  public String sourceTreeDirty;

  private LanguageVariantProvider provider;
  private String input;

  @Setup(Level.Trial)
  public void setup() {
    if ("ja-JP".equals(languageTag)) {
      provider = new KuromojiJapaneseVariantProvider();
      input = "東京都新宿区";
    } else {
      provider = new Icu4jTransliterationProvider();
      input =
          switch (languageTag) {
            case "zh-Hans" -> "重庆银行";
            case "ar-Arab" -> "القاهرة";
            case "hi-Deva" -> "नमस्ते दुनिया";
            case "bn-Beng" -> "বাংলা";
            case "gu-Gujr" -> "ગુજરાત";
            case "pa-Guru" -> "ਪੰਜਾਬ";
            case "ta-Taml" -> "தமிழ்நாடு";
            case "te-Telu" -> "తెలుగు";
            case "kn-Knda" -> "ಕನ್ನಡ";
            case "ml-Mlym" -> "മലയാളം";
            case "or-Orya" -> "ଓଡ଼ିଶା";
            default -> throw new IllegalArgumentException("unsupported benchmark language");
          };
    }
  }

  @Benchmark
  public Object transformVariants() {
    return provider.variantsForLanguage(input, languageTag);
  }
}
