package com.yunx.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark ORDER BY createTime DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    /** 已添加到主页快捷方式的收藏 */
    @Query("SELECT * FROM bookmark WHERE homePinned = 1 ORDER BY createTime DESC")
    fun observeHomePinned(): Flow<List<BookmarkEntity>>

    /** 已出现的分类（去重），用于与预置分类合并展示 */
    @Query("SELECT DISTINCT category FROM bookmark ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmark SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /** 添加 / 移除主页快捷方式 */
    @Query("UPDATE bookmark SET homePinned = :pinned WHERE id = :id")
    suspend fun updateHomePinned(id: Long, pinned: Boolean)

    /** 主页快捷方式色块的自定义文字（空串 = 自动取标题前几个字） */
    @Query("UPDATE bookmark SET homeLabel = :label WHERE id = :id")
    suspend fun updateHomeLabel(id: Long, label: String)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun delete(id: Long)
}
