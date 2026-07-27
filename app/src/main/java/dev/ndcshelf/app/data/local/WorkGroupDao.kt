package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WorkGroupDao {
    @Query("SELECT * FROM work_groups ORDER BY id")
    suspend fun getAllGroups(): List<WorkGroupEntity>

    @Query("SELECT * FROM work_group_memberships ORDER BY id")
    suspend fun getAllMemberships(): List<WorkGroupMembershipEntity>

    @Query("SELECT * FROM work_groups WHERE id = :groupId LIMIT 1")
    suspend fun findGroupById(groupId: String): WorkGroupEntity?

    @Query("SELECT * FROM work_group_memberships WHERE id = :membershipId LIMIT 1")
    suspend fun findMembershipById(membershipId: String): WorkGroupMembershipEntity?

    @Query("SELECT * FROM work_group_memberships WHERE workId = :workId LIMIT 1")
    suspend fun findMembershipByWorkId(workId: String): WorkGroupMembershipEntity?

    @Query(
        "SELECT * FROM work_group_memberships WHERE groupId = :groupId ORDER BY createdAt, id",
    )
    suspend fun getMembershipsForGroup(groupId: String): List<WorkGroupMembershipEntity>

    @Upsert
    suspend fun upsertGroups(groups: List<WorkGroupEntity>)

    @Upsert
    suspend fun upsertMemberships(memberships: List<WorkGroupMembershipEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: WorkGroupEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembership(membership: WorkGroupMembershipEntity)

    @Query(
        "UPDATE work_groups SET seriesSubstitutionEnabled = :enabled, updatedAt = :updatedAt " +
            "WHERE id = :groupId",
    )
    suspend fun updateSeriesSubstitution(groupId: String, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM work_group_memberships WHERE id = :membershipId")
    suspend fun deleteMembership(membershipId: String): Int

    @Query("DELETE FROM work_group_memberships")
    suspend fun deleteAllMemberships()

    @Query("DELETE FROM work_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String): Int

    @Query("DELETE FROM work_groups")
    suspend fun deleteAllGroups()

    @Query(
        "DELETE FROM work_groups WHERE id IN (" +
            "SELECT group_rows.id FROM work_groups AS group_rows " +
            "LEFT JOIN work_group_memberships AS memberships ON memberships.groupId = group_rows.id " +
            "GROUP BY group_rows.id HAVING COUNT(memberships.id) < 2" +
            ")",
    )
    suspend fun deleteUndersizedGroups(): Int
}
