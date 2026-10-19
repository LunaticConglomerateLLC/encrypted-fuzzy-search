package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

public class CodePointLevenshteinScorerTest {
  private final CodePointLevenshteinScorer scorer = new CodePointLevenshteinScorer();

  @Test
  public void computesNormalizedEditSimilarity() {
    assertEquals(scorer.score("", ""), 1.0);
    assertEquals(scorer.score("abc", "abc"), 1.0);
    assertEquals(scorer.score("abc", "axc"), 2.0 / 3.0, 0.000001);
    assertEquals(scorer.score("abc", "abcd"), 0.75);
    assertEquals(scorer.score("abcd", "ab"), 0.5);
    assertEquals(scorer.score("abc", "xyz"), 0.0);
  }

  @Test
  public void comparesUnicodeCodePointsAndRemainsCaseSensitive() {
    assertEquals(scorer.score("a😀b", "a😃b"), 2.0 / 3.0, 0.000001);
    assertEquals(scorer.score("Alice", "alice"), 0.8);
  }

  @Test
  public void rejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> scorer.score(null, "value"));
    assertThrows(NullPointerException.class, () -> scorer.score("value", null));
  }
}
