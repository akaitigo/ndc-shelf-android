package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 目的別同意の端末内記録。完全バックアップの対象外であり、復元しても
 * 変更されない（同意は端末上の利用者の判断そのものであるため）。
 */
@Entity(tableName = "consent_records")
data class ConsentRecordEntity(
    @PrimaryKey val purpose: String,
    val consentedVersion: Int,
    val grantedAt: Long?,
    val revokedAt: Long?,
)

@Dao
interface ConsentDao {
    @Query("SELECT * FROM consent_records")
    fun observeAll(): Flow<List<ConsentRecordEntity>>

    @Query("SELECT * FROM consent_records WHERE purpose = :purpose")
    suspend fun find(purpose: String): ConsentRecordEntity?

    @Upsert
    suspend fun upsert(record: ConsentRecordEntity)
}
