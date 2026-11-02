package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SimilarityTokens {
  private SimilarityTokens() {}

  static List<String> words(String text) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    text.toLowerCase(Locale.ROOT)
        .codePoints()
        .forEach(
            point -> {
              if (Character.isLetterOrDigit(point)) {
                word.appendCodePoint(point);
              } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
              }
            });
    if (!word.isEmpty()) {
      words.add(word.toString());
    }
    return List.copyOf(words);
  }

  static Map<String, Integer> frequencies(List<String> forms) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String form : forms) {
      for (String token : words(form)) {
        counts.merge(token, 1, Integer::sum);
      }
    }
    return counts;
  }

  static List<String> characterNgrams(String text, int size) {
    int[] points = text.codePoints().toArray();
    if (points.length == 0) {
      return List.of();
    }
    if (points.length < size) {
      return List.of(text);
    }
    List<String> grams = new ArrayList<>(points.length - size + 1);
    for (int start = 0; start <= points.length - size; start++) {
      grams.add(new String(points, start, size));
    }
    return List.copyOf(grams);
  }

  static Map<String, Integer> frequencies(String text, int ngramSize) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String gram : characterNgrams(text, ngramSize)) {
      counts.merge(gram, 1, Integer::sum);
    }
    return counts;
  }
}
