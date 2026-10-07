package com.yunx.app.data.db

/** Additive schema changes after StarAssistant v16; prior migrations remain immutable. */
object Sync129Schema {
    val statements = listOf(
        "ALTER TABLE bookmark ADD COLUMN homePinned INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE bookmark ADD COLUMN homeLabel TEXT NOT NULL DEFAULT ''",
        "CREATE TABLE IF NOT EXISTS `pan115_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "ALTER TABLE `download_task` ADD COLUMN `engineTaskId` TEXT NOT NULL DEFAULT ''",
        "CREATE TABLE IF NOT EXISTS `guangya_account` (`id` TEXT NOT NULL, `accessToken` TEXT NOT NULL, `refreshToken` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `deviceSign` TEXT NOT NULL, `account` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ilanzou_account` (`id` TEXT NOT NULL, `appToken` TEXT NOT NULL, `uuid` TEXT NOT NULL, `account` TEXT NOT NULL, `password` TEXT NOT NULL, `userId` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `lanzou_account` (`id` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nickname` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "ALTER TABLE `xunlei_account` ADD COLUMN `authType` TEXT NOT NULL DEFAULT ''"
    )
}
