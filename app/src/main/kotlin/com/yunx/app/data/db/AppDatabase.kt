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
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class, XunleiAccountEntity::class, BaiduAccountEntity::class, C139AccountEntity::class, Pan123AccountEntity::class, Pan115AccountEntity::class, GuangYaAccountEntity::class, ILanzouAccountEntity::class, LanzouAccountEntity::class, BookmarkEntity::class, VideoAccountEntity::class],
    version = 23,
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

    /** 视频解析账号：原始 DAO（未加密），对外请用 [videoAccountDao] */
    abstract fun rawVideoAccountDao(): VideoAccountDao

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

    /** 视频解析账号：Cookie 与网盘凭证同等加密后落库 */
    fun videoAccountDao(): VideoAccountDao = SecureAccountDaos.videoAccount(rawVideoAccountDao(), credentialCipher)

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
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23
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

        /**
         * v18 / v19：上游 YunX 在 17→18 建了 guangya_account、ilanzou_account 两张表，
         * 18→19 给 xunlei_account 加了 authType 列。星辰这边的 [MIGRATION_16_17] 通过
         * [Sync129Schema] 一次性把 1.2.9 的 schema 铺好了（上述三处已含在内），
         * 所以这两个编号对星辰已有用户是空操作 —— 但必须显式存在，
         * 否则 Room 找不到 17→19 的迁移路径，老用户升级会直接抛 IllegalStateException。
         * 全新安装由 Room 按实体建表，不会走到这里。
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        /** 需要「多账号」两列的全部账号表（与 [entities] 里的 `*_account` 一一对应） */
        private val ACCOUNT_TABLES = listOf(
            "quark_account", "uc_account", "xunlei_account", "baidu_account", "c139_account",
            "pan123_account", "pan115_account", "guangya_account", "ilanzou_account", "lanzou_account"
        )

        /**
         * v20：网盘多账号 —— 每个平台可以同时登录多个账号。
         *
         * - 10 张账号表各加两列：`isPrimary`（主账号标记，列目录 / 转存 / 上传等需要单一身份的操作走它）、
         *   `remark`（用户备注，便于区分同平台多个账号）；
         * - **老数据每个平台只有一行，且它就是用户一直在用的账号** ⇒ 直接标成主账号：
         *   不新增行、不动任何凭证，升级后表现与升级前完全一致；
         * - 之后新增的账号主键是 `<platform>-<时间戳36进制>`，与老行的平台常量主键（如 `"quark"`）并存，
         *   所以「主账号」既可能是老行也可能是新行，DAO 统一按 `isPrimary` 取用；
         * - `download_task.altSourcesJson` 供「单文件多源分片」（多账号对同一文件各取一条直链）记录备选源，
         *   默认空串 = 单源，与升级前行为一致。
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ACCOUNT_TABLES.forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `isPrimary` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `remark` TEXT NOT NULL DEFAULT ''")
                }
                ACCOUNT_TABLES.forEach { table ->
                    db.execSQL("UPDATE `$table` SET `isPrimary` = 1")
                }
                db.execSQL(
                    "ALTER TABLE `download_task` ADD COLUMN `altSourcesJson` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v21：视频解析账号。
         *
         * 视频平台（抖音/小红书/快手/微博/AcFun/哔哩哔哩/Pixiv）的凭证都是一个完整 Cookie，
         * 结构与语义一致，所以**所有平台共用一张表**、用 `platform` 列区分 —— 以后新增视频平台
         * 不需要再写迁移（与网盘「一平台一表」不同，那里各平台凭证字段差异很大）。
         * 列定义必须与 [VideoAccountEntity] 完全一致（类型/非空），否则 Room 打开时会校验失败。
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_account` (" +
                        "`id` TEXT NOT NULL, " +
                        "`platform` TEXT NOT NULL, " +
                        "`cookie` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`isPrimary` INTEGER NOT NULL, " +
                        "`remark` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v22：哔哩哔哩 DASH 音视频合轨。
         *
         * 高清是**音视频分离**的两路流，需要先各自下载再用 mp4parser 合轨，所以下载任务要多记
         * 「音轨直链 + 音轨请求头」。两列默认空 = 单文件下载，升级后既有任务与行为完全不变。
         */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `mergeAudioUrl` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `mergeAudioHeadersJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v23：动图（Live Photo）合成。
         *
         * 微博 / 抖音的动图是「静图 + 一段视频」，要合成一张「动态照片」得把那段视频的直链
         * 也随任务带走（见 `MotionPhotoWriter`）。默认空 = 普通单文件下载，既有任务不受影响。
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `motionUrl` TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
