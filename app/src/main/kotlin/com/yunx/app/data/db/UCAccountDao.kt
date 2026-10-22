package com.yunx.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UCAccountDao {

    /**
     * 主账号（列目录 / 转存 / 上传 / 取链默认用它）。
     * ★ 取「主账号优先、否则最近更新的一条」而不是「唯一那条」：多账号下退出主账号后
     *   能立刻回退到同平台其他账号，不会出现「明明还有账号却显示未登录」。
     */
    @Query("SELECT * FROM uc_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    fun observeAccount(): Flow<UCAccountEntity?>

    @Query("SELECT * FROM uc_account ORDER BY isPrimary DESC, updatedAt DESC LIMIT 1")
    suspend fun getAccount(): UCAccountEntity?

    /** 该平台全部已登录账号（主账号在前）：账号管理页与多账号下载轮询用 */
    @Query("SELECT * FROM uc_account ORDER BY isPrimary DESC, updatedAt DESC")
    fun observeAccounts(): Flow<List<UCAccountEntity>>

    @Query("SELECT * FROM uc_account ORDER BY isPrimary DESC, updatedAt DESC")
    suspend fun getAccounts(): List<UCAccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: UCAccountEntity)

    /** 设为主账号：一条 SQL 把目标行置 1、同平台其余行置 0（免事务） */
    @Query("UPDATE uc_account SET isPrimary = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setPrimary(id: String)

    @Query("UPDATE uc_account SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: String, remark: String)

    @Query("DELETE FROM uc_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空该平台全部账号（「网盘认证」导入前的整体覆盖等场景） */
    @Query("DELETE FROM uc_account")
    suspend fun clear()
}