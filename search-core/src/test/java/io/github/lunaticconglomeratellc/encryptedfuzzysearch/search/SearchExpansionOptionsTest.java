package io.github.lunaticconglomeratellc.encryptedfuzzysearch.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

public class SearchExpansionOptionsTest {
  @Test
  public void aliasesHaveBoundedTextAndStableProvenance() {
    SearchAlias alias = new SearchAlias("Robert", "crm", "2026-10");
    SearchExpansionOptions options = SearchExpansionOptions.aliases(List.of(alias));

    assertEquals(options.aliases(), List.of(alias));
    assertEquals(options.hasExpansions(), true);
    assertFalse(alias.toString().contains("Robert"));
    assertThrows(IllegalArgumentException.class, () -> new SearchAlias(" ", "crm", "v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchAlias("x".repeat(SearchAlias.MAX_CODE_POINTS + 1), "crm", "v1"));
    assertThrows(IllegalArgumentException.class, () -> new SearchAlias("value", " ", "v1"));
  }

  @Test
  public void expansionOptionsBoundAliasesAndRejectDuplicateEntries() {
    SearchAlias alias = new SearchAlias("Robert", "crm", "v1");
    assertFalse(SearchExpansionOptions.none().hasExpansions());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchExpansionOptions(List.of(alias, alias), java.util.Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SearchExpansionOptions.aliases(
                java.util.Collections.nCopies(SearchExpansionOptions.MAX_ALIAS_COUNT + 1, alias)));
  }

  @Test
  public void policyTracksExpansionPermissionsIndependentlyFromStrategyLeakage() {
    SearchPolicy policy =
        new SearchPolicy(
            Set.of("efs-exact"),
            Set.of(IndexLeakage.EQUALITY, IndexLeakage.FREQUENCY),
            true,
            Set.of("icu4j"));

    assertEquals(policy.aliasesAllowed(), true);
    assertEquals(policy.allowsLanguageProvider("icu4j"), true);
    assertEquals(policy.allowsLanguageProvider("other"), false);
    assertEquals(SearchPolicy.productionDefault().aliasesAllowed(), false);
    assertEquals(SearchPolicy.productionDefault().allowedLanguageProviderIds(), Set.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchPolicy(Set.of("efs-exact"), Set.of(), false, Set.of(" ")));
  }

  @Test
  public void expansionProvenanceContainsIdentifiersButNeverTheAliasValue() {
    ExpansionProvenance alias = ExpansionProvenance.alias("crm", "v3");
    ExpansionProvenance provider =
        ExpansionProvenance.languageProvider("icu4j", "transform-v1", "zh-Hans");

    assertEquals(alias.kind(), ExpansionProvenance.Kind.KNOWN_ALIAS);
    assertEquals(alias.origin(), "crm");
    assertEquals(alias.version(), "v3");
    assertEquals(provider.kind(), ExpansionProvenance.Kind.LANGUAGE_PROVIDER);
    assertEquals(provider.languageTag(), "zh-Hans");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExpansionProvenance(ExpansionProvenance.Kind.KNOWN_ALIAS, "crm", "v1", "en"));
  }
}
