package com.yunx.app.data.db
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test
class Sync129MigrationTest {
    @Test fun v16UpgradePreservesExistingHistoryAndCredentials() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE bookmark (id INTEGER NOT NULL DEFAULT 0, link TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '', pwd TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', createTime INTEGER NOT NULL DEFAULT 0)")
                sql.execute("CREATE TABLE download_task (id INTEGER NOT NULL DEFAULT 0, url TEXT NOT NULL DEFAULT '', fileName TEXT NOT NULL DEFAULT '', totalSize INTEGER NOT NULL DEFAULT 0, downloadedSize INTEGER NOT NULL DEFAULT 0, status INTEGER NOT NULL DEFAULT 0, errorMsg TEXT NOT NULL DEFAULT '', savePath TEXT NOT NULL DEFAULT '', requestHeadersJson TEXT NOT NULL DEFAULT '', chunkCount INTEGER NOT NULL DEFAULT 0, plannedTotalSize INTEGER NOT NULL DEFAULT 0, cleanupId TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '', sourceFileId TEXT NOT NULL DEFAULT '', sourceType TEXT NOT NULL DEFAULT '', sourceContext TEXT NOT NULL DEFAULT '', urlExpiresAt INTEGER NOT NULL DEFAULT 0, etag TEXT NOT NULL DEFAULT '', lastModified TEXT NOT NULL DEFAULT '', manualPaused INTEGER NOT NULL DEFAULT 0, refreshCount INTEGER NOT NULL DEFAULT 0, avgSpeed INTEGER NOT NULL DEFAULT 0, completedTime INTEGER NOT NULL DEFAULT 0, createTime INTEGER NOT NULL DEFAULT 0)")
                sql.execute("CREATE TABLE xunlei_account (id TEXT NOT NULL DEFAULT '', accessToken TEXT NOT NULL DEFAULT '', refreshToken TEXT NOT NULL DEFAULT '', deviceId TEXT NOT NULL DEFAULT '', captchaToken TEXT NOT NULL DEFAULT '', nickname TEXT NOT NULL DEFAULT '', updatedAt INTEGER NOT NULL DEFAULT 0)")
                sql.execute("INSERT INTO bookmark (id, link, pwd) VALUES (7, 'https://pan.quark.cn/s/example', '1234')")
                sql.execute("INSERT INTO download_task (id,fileName,status,savePath,requestHeadersJson,manualPaused,completedTime) VALUES (42,'kept.zip',3,'content://saved/item','encrypted-headers',1,123456)")
                sql.execute("INSERT INTO xunlei_account (id, accessToken, refreshToken) VALUES ('xunlei','encrypted-access','encrypted-refresh')")
                Sync129Schema.statements.forEach(sql::execute)
                sql.executeQuery("SELECT * FROM download_task WHERE id=42").use { row ->
                    assertTrue(row.next()); assertEquals("kept.zip",row.getString("fileName"))
                    assertEquals("content://saved/item",row.getString("savePath"))
                    assertEquals("encrypted-headers",row.getString("requestHeadersJson"))
                    assertEquals(1,row.getInt("manualPaused")); assertEquals(123456L,row.getLong("completedTime"))
                    assertEquals("",row.getString("engineTaskId"))
                }
                sql.executeQuery("SELECT * FROM xunlei_account").use { row ->
                    assertTrue(row.next()); assertEquals("encrypted-refresh",row.getString("refreshToken")); assertEquals("",row.getString("authType"))
                }
                sql.executeQuery("SELECT * FROM bookmark").use { row ->
                    assertTrue(row.next()); assertEquals("1234",row.getString("pwd")); assertEquals(0,row.getInt("homePinned"))
                }
                for (table in listOf("pan115_account","guangya_account","ilanzou_account","lanzou_account")) {
                    sql.executeQuery("SELECT COUNT(*) FROM $table").use { row -> assertTrue(row.next()); assertEquals(0,row.getInt(1)) }
                }
            }
        }
    }
}
