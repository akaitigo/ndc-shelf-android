package dev.ndcshelf.app.data.consent

import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.ConsentRecordEntity
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.domain.consent.ConsentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConsentRepository(
    database: AppDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConsentRepository {
    private val dao = database.consentDao()

    override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> =
        dao.observeAll().map { entities ->
            entities
                .mapNotNull(ConsentRecordEntity::toDomainOrNull)
                .associateBy(ConsentRecord::purpose)
        }

    override suspend fun isGranted(purpose: ConsentPurpose): Boolean = dao.find(purpose.name)?.toDomainOrNull()?.granted == true

    override suspend fun grant(purpose: ConsentPurpose): ConsentRecord {
        val record =
            ConsentRecord(
                purpose = purpose,
                consentedVersion = purpose.policyVersion,
                grantedAtMillis = nowMillis(),
                revokedAtMillis = null,
            )
        dao.upsert(record.toEntity())
        return record
    }

    override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord? {
        val current = dao.find(purpose.name)?.toDomainOrNull() ?: return null
        if (current.revokedAtMillis != null) return current
        val revoked = current.copy(revokedAtMillis = nowMillis())
        dao.upsert(revoked.toEntity())
        return revoked
    }
}

private fun ConsentRecordEntity.toDomainOrNull(): ConsentRecord? {
    val knownPurpose = ConsentPurpose.entries.firstOrNull { it.name == purpose } ?: return null
    return ConsentRecord(
        purpose = knownPurpose,
        consentedVersion = consentedVersion,
        grantedAtMillis = grantedAt,
        revokedAtMillis = revokedAt,
    )
}

private fun ConsentRecord.toEntity(): ConsentRecordEntity =
    ConsentRecordEntity(
        purpose = purpose.name,
        consentedVersion = consentedVersion,
        grantedAt = grantedAtMillis,
        revokedAt = revokedAtMillis,
    )
