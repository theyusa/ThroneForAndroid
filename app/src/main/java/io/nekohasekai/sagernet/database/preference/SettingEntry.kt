package io.nekohasekai.sagernet.database.preference

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row of the desktop's `settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)` table (SettingsRepo.cpp), the value
 * in the desktop encoding: "true"/"false", decimal integers, compact JSON arrays, raw text. The table lives in
 * SagerDatabase next to the profiles, as in the desktop's single database.
 */
@Entity(tableName = "settings")
class SettingEntry(
    @PrimaryKey var key: String = "",
    var value: String = "",
) {

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `settings` ORDER BY `key`")
        fun all(): List<SettingEntry>

        @Query("SELECT `value` FROM `settings` WHERE `key` = :key")
        operator fun get(key: String): String?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(entry: SettingEntry)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun putAll(entries: List<SettingEntry>)

        @Query("DELETE FROM `settings` WHERE `key` = :key")
        fun delete(key: String): Int

        @Query("DELETE FROM `settings`")
        fun reset(): Int

        /**
         * Single-statement compare-and-swap on one settings row: set `value` to [newValue] only if the current
         * `value` still equals [expectedValue]. Returns 1 when the swap happened and 0 when the row is missing or a
         * concurrent writer already changed it.
         *
         * A plain `UPDATE ... WHERE value = :expectedValue` (no `ON CONFLICT ... DO UPDATE`), so it runs on every
         * SQLite the project targets: UPSERT (`ON CONFLICT ... DO UPDATE`) needs SQLite 3.24.0 (API 29+), while this
         * works on SQLite 3.9.2 (API 24, the project's minSdk). The check-and-set is one statement, so it is atomic at
         * the SQLite statement level: of two concurrent writers whose `expectedValue` no longer matches, exactly one
         * affects a row. The absent-row (first-claim) case is materialized by [insertOrIgnore] on the caller's side.
         */
        @Query("UPDATE `settings` SET `value` = :newValue WHERE `key` = :key AND `value` = :expectedValue")
        fun compareAndSwapValue(key: String, expectedValue: String, newValue: String): Int

        /**
         * Inserts a settings row only when the key is absent, ignoring a concurrent insert. Returns the rowid of the
         * inserted row (`>= 0`), or `-1` when the key already existed and the insert was ignored. `INSERT OR IGNORE`
         * is SQLite 3.0+ (safe on API 24+).
         */
        @Query("INSERT OR IGNORE INTO `settings` (`key`, `value`) VALUES (:key, :value)")
        fun insertOrIgnore(key: String, value: String): Long
    }
}
