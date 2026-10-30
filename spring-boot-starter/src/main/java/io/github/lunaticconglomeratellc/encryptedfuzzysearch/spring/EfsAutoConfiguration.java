package io.github.lunaticconglomeratellc.encryptedfuzzysearch.spring;

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.AuthenticatedFieldCipher;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ExactBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.MasterKeyProvider;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.NgramBlindIndexer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.ScopedKeyDeriver;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.language.CanonicalTextNormalizer;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresTokenIndexRepository;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedExactStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedIndexStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedNgramStrategy;
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenIndexRepository;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Optional, conditional Spring Boot wiring for the EFS search service and PostgreSQL adapter. */
@AutoConfiguration
@ConditionalOnClass(EncryptedFuzzySearchService.class)
public class EfsAutoConfiguration {

  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnMissingBean(TokenIndexRepository.class)
  PostgresTokenIndexRepository efsTokenIndexRepository(DataSource dataSource) {
    return new PostgresTokenIndexRepository(dataSource);
  }

  @Bean
  @ConditionalOnBean(MasterKeyProvider.class)
  @ConditionalOnMissingBean(ScopedKeyDeriver.class)
  ScopedKeyDeriver efsScopedKeyDeriver(MasterKeyProvider masterKeyProvider) {
    return new ScopedKeyDeriver(masterKeyProvider);
  }

  @Bean
  @ConditionalOnMissingBean(AuthenticatedFieldCipher.class)
  AuthenticatedFieldCipher efsAuthenticatedFieldCipher() {
    return new AuthenticatedFieldCipher();
  }

  @Bean
  @ConditionalOnMissingBean(CanonicalTextNormalizer.class)
  CanonicalTextNormalizer efsCanonicalTextNormalizer() {
    return new CanonicalTextNormalizer();
  }

  @Bean
  @ConditionalOnBean({ScopedKeyDeriver.class, TokenIndexRepository.class})
  @ConditionalOnMissingBean(EncryptedFuzzySearchService.class)
  EncryptedFuzzySearchService encryptedFuzzySearchService(
      ScopedKeyDeriver keyDeriver,
      AuthenticatedFieldCipher fieldCipher,
      CanonicalTextNormalizer textNormalizer,
      TokenIndexRepository repository) {
    ExactBlindIndexer exactIndexer = new ExactBlindIndexer();
    NgramBlindIndexer ngramIndexer = new NgramBlindIndexer();
    List<ProtectedIndexStrategy> strategies =
        List.of(
            new ProtectedExactStrategy(exactIndexer),
            new ProtectedNgramStrategy(2, ngramIndexer),
            new ProtectedNgramStrategy(3, ngramIndexer));
    return new EncryptedFuzzySearchService(
        keyDeriver, fieldCipher, textNormalizer, strategies, repository);
  }
}
