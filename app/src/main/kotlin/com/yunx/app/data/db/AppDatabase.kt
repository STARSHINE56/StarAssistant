package com.yunx.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yunx.app.data.security.AndroidKeystoreCredentialCipher
import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.data.security.CredentialStore

@Database(
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class, Pan115AccountEntity::class, GuangYaAccountEntity::class, ILanzouAccountEntity::class, LanzouAccountEntity::class, BookmarkEntity::class],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rawQuarkAccountDao(): QuarkAccountDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun rawUcAccountDao(): UCAccountDao

    abstract fun rawXunleiAccountDao(): XunleiAccountDao

    abstract fun rawBaiduAccountDao(): BaiduAccountDao

    abstract fun rawC139AccountDao(): C139AccountDao

    abstract fun rawPan123AccountDao(): Pan123AccountDao

    abstract fun rawPan115AccountDao(): Pan115AccountDao

    abstract fun rawGuangYaAccountDao(): GuangYaAccountDao

    abstract fun rawILanzouAccountDao(): ILanzouAccountDao

    abstract fun rawLanzouAccountDao(): LanzouAccountDao

    abstract fun bookmarkDao(): BookmarkDao

    private lateinit var credentialCipher: CredentialCipher

    fun quarkAccountDao(): QuarkAccountDao = SecureAccountDaos.quark(rawQuarkAccountDao(), credentialCipher)
    fun ucAccountDao(): UCAccountDao = SecureAccountDaos.uc(rawUcAccountDao(), credentialCipher)
    fun xunleiAccountDao(): XunleiAccountDao = SecureAccountDaos.xunlei(rawXunleiAccountDao(), credentialCipher)
    fun baiduAccountDao(): BaiduAccountDao = SecureAccountDaos.baidu(rawBaiduAccountDao(), credentialCipher)
    fun c139AccountDao(): C139AccountDao = SecureAccountDaos.c139(rawC139AccountDao(), credentialCipher)
    fun pan123AccountDao(): Pan123AccountDao = SecureAccountDaos.pan123(rawPan123AccountDao(), credentialCipher)

    fun pan115AccountDao(): Pan115AccountDao = SecureAccountDaos.pan115(rawPan115AccountDao(), credentialCipher)
    fun guangyaAccountDao(): GuangYaAccountDao = SecureAccountDaos.guangya(rawGuangYaAccountDao(), credentialCipher)
    fun ilanzouAccountDao(): ILanzouAccountDao = SecureAccountDaos.ilanzou(rawILanzouAccountDao(), credentialCipher)
    fun lanzouAccountDao(): LanzouAccountDao = SecureAccountDaos.lanzou(rawLanzouAccountDao(), credentialCipher)

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yunx.db"
                )
                    .addMigrations(
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17
                    )
                    // 早期开发版（1-8）无可靠 schema；从 v9 起必须保留凭证和下载任务
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
                    .build()
                    .also { database ->
                        database.credentialCipher = AndroidKeystoreCredentialCipher.shared
                        CredentialStore.installRecovery(context)
                        instance = database
                    }
            }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN requestHeadersJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE download_task ADD COLUMN chunkCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN plannedTotalSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cleanupId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN platform TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN avgSpeed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`link` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`pwd` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createTime` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN completedTime INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN sourceFileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_task ADD COLUMN sourceType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_task ADD COLUMN urlExpiresAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN etag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_task ADD COLUMN lastModified TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_task ADD COLUMN manualPaused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_task ADD COLUMN refreshCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_task ADD COLUMN sourceContext TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Sync129Schema.statements.forEach(db::execSQL)
            }
        }
    }
}
