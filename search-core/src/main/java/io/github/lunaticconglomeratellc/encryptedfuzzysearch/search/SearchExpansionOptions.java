package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded, explicit query expansions; no language detection or alias lookup runs implicitly. */
public record SearchExpansionOptions(
    List<SearchAlias> aliases, Optional<LanguageProviderSelection> languageProvider) {
  public static final int MAX_ALIAS_COUNT = 8;
  public static final int MAX_LANGUAGE_VARIANT_COUNT = 8;

  public SearchExpansionOptions {
    Objects.requireNonNull(aliases, "aliases");
    Objects.requireNonNull(languageProvider, "languageProvider");
    aliases = List.copyOf(aliases);
    if (aliases.size() > MAX_ALIAS_COUNT) {
      throw new IllegalArgumentException("at most " + MAX_ALIAS_COUNT + " aliases are allowed");
    }
    if (new HashSet<>(aliases).size() != aliases.size()) {
      throw new IllegalArgumentException("alias entries must be unique");
    }
  }

  public static SearchExpansionOptions none() {
    return new SearchExpansionOptions(List.of(), Optional.empty());
  }

  public static SearchExpansionOptions aliases(List<SearchAlias> aliases) {
    return new SearchExpansionOptions(aliases, Optional.empty());
  }

  public static SearchExpansionOptions language(LanguageProviderSelection selection) {
    return new SearchExpansionOptions(List.of(), Optional.of(selection));
  }

  public boolean hasExpansions() {
    return !aliases.isEmpty() || languageProvider.isPresent();
  }
}
