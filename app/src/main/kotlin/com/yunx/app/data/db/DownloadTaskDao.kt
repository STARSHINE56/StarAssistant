package com.yunx.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task ORDER BY createTime DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_task WHERE status = 0 OR status = 1 ORDER BY createTime ASC")
    suspend fun getInterruptedTasks(): List<DownloadTaskEntity>

    @Query("SELECT id FROM download_task")
    suspend fun getAllTaskIds(): List<Long>

    @Query("UPDATE download_task SET status = :status, downloadedSize = :downloadedSize, totalSize = :totalSize WHERE id = :id")
    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)

    @Query("UPDATE download_task SET chunkCount = :chunkCount, plannedTotalSize = :totalSize WHERE id = :id")
    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)

    @Query("UPDATE download_task SET requestHeadersJson = :encryptedHeaders WHERE id = :id")
    suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String)

    @Query(
        "UPDATE download_task SET " +
            "url = :url, requestHeadersJson = :encryptedHeaders, " +
            "sourceContext = :sourceContext, urlExpiresAt = :urlExpiresAt, " +
            "etag = :etag, lastModified = :lastModified, " +
            "refreshCount = refreshCount + 1, errorMsg = '' " +
            "WHERE id = :id"
    )
    suspend fun updateRefreshedSource(
        id: Long,
        url: String,
        encryptedHeaders: String,
        sourceContext: String,
        urlExpiresAt: Long,
        etag: String,
        lastModified: String
    )

    @Query("UPDATE download_task SET status = 2, manualPaused = 0 WHERE status = 1 OR status = 0")
    suspend fun markInterruptedAsPaused()

    @Query("UPDATE download_task SET manualPaused = :manualPaused WHERE id = :id")
    suspend fun updateManualPaused(id: Long, manualPaused: Boolean)

    @Query("UPDATE download_task SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE download_task SET errorMsg = :errorMsg WHERE id = :id")
    suspend fun updateError(id: Long, errorMsg: String)

    @Query("UPDATE download_task SET status = :status, savePath = :savePath, avgSpeed = :avgSpeed, completedTime = :completedTime WHERE id = :id")
    suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long = 0L, completedTime: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)

    /** 记下这条任务对应的 Gopeed 引擎任务 ID（走引擎下载时才写） */
    @Query("UPDATE download_task SET engineTaskId = :engineTaskId WHERE id = :id")
    suspend fun updateEngineTaskId(id: Long, engineTaskId: String)

    /** 磁力任务完成时把占位显示名换成真正的种子名（种子名要等引擎解析出元数据才有） */
    @Query("UPDATE download_task SET fileName = :name WHERE id = :id")
    suspend fun updateFileName(id: Long, name: String)

    /** 还在引擎里跑、需要同步进度的任务（已完成/失败的不再同步） */
    @Query(
        "SELECT * FROM download_task WHERE engineTaskId != '' " +
            "AND status != 3 AND status != 4"
    )
    suspend fun listSyncableEngineTasks(): List<DownloadTaskEntity>
}
