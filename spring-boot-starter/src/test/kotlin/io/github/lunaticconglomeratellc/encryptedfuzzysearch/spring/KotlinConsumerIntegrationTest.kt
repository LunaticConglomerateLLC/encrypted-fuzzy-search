package io.github.lunaticconglomeratellc.encryptedfuzzysearch.spring

import io.github.lunaticconglomeratellc.encryptedfuzzysearch.crypto.MasterKeyProvider
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresTokenIndexRepository
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedFuzzySearchService
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.EncryptedSearchDocument
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.ProtectedToken
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.SearchCandidate
import io.github.lunaticconglomeratellc.encryptedfuzzysearch.search.TokenIndexRepository
import java.sql.SQLException
import java.util.Arrays
import java.util.function.Supplier
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.annotations.Test

class KotlinConsumerIntegrationTest {
    @Test
    fun kotlinConsumerCanUseTheAutoConfiguredService() {
        val rootKeyBytes = ByteArray(32)
        Arrays.fill(rootKeyBytes, 0x41.toByte())
        val rootKey = SecretKeySpec(rootKeyBytes, "HmacSHA256")
        val repository = MemoryRepository()

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EfsAutoConfiguration::class.java))
            .withBean(MasterKeyProvider::class.java, Supplier { MasterKeyProvider { rootKey } })
            .withBean(TokenIndexRepository::class.java, Supplier { repository })
            .run { context ->
                assertFalse(context.containsBean("efsTokenIndexRepository"))
                val service = context.getBean(EncryptedFuzzySearchService::class.java)
                assertNotNull(service)

                service.indexValue("tenant-a", "name", "alice", "key-v1", "Alice")
                val matches = service.search("tenant-a", "name", "key-v1", "Alice", 10, 1.0)

                assertEquals(matches.size, 1)
                assertEquals(matches.first().recordId(), "alice")
            }
    }

    private class MemoryRepository : TokenIndexRepository {
        private val documents = mutableListOf<EncryptedSearchDocument>()

        override fun initializeSchema() = Unit

        override fun upsert(document: EncryptedSearchDocument) {
            delete(document.tenantId(), document.fieldId(), document.recordId())
            documents.add(document)
        }

        override fun findCandidates(
            tenantId: String,
            fieldId: String,
            keyId: String,
            queryTokens: MutableList<ProtectedToken>,
            limit: Int,
        ): MutableList<SearchCandidate> =
            documents.asSequence()
                .filter {
                    it.tenantId() == tenantId && it.fieldId() == fieldId && it.keyId() == keyId
                }
                .map { document ->
                    val overlap = document.protectedTokens().count { it in queryTokens }
                    document to overlap
                }
                .filter { (_, overlap) -> overlap > 0 }
                .sortedWith(compareByDescending<Pair<EncryptedSearchDocument, Int>> { it.second }
                    .thenBy { it.first.recordId() })
                .take(limit)
                .map { (document, overlap) ->
                    SearchCandidate(
                        document.recordId(),
                        document.keyId(),
                        document.transformVersion(),
                        document.ciphertext(),
                        overlap,
                    )
                }
                .toMutableList()

        override fun delete(tenantId: String, fieldId: String, recordId: String) {
            documents.removeIf {
                it.tenantId() == tenantId && it.fieldId() == fieldId && it.recordId() == recordId
            }
        }
    }
}
