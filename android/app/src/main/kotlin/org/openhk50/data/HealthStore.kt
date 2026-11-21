package org.openhk50.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

class HealthStore(context: Context) : SQLiteOpenHelper(context, "health.sqlite3", null, 1) {
    data class Day(
        val heart: List<Reading>, val steps: List<Reading>, val latestHeart: Reading?,
        val band: String?, val earlier: Boolean
    ) {
        val stepTotal = steps.sumOf { it.value.toLong() }
    }
    data class Checkpoint(val store: String, val cursor: Long)

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE readings (
                band TEXT NOT NULL, kind TEXT NOT NULL, origin TEXT NOT NULL, slot TEXT NOT NULL,
                time_ms INTEGER NOT NULL, value INTEGER NOT NULL, revised_at INTEGER NOT NULL,
                revision TEXT NOT NULL, pending INTEGER NOT NULL CHECK(pending IN (0,1)),
                PRIMARY KEY(band, kind, origin, slot)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("CREATE INDEX readings_band_time ON readings(band, time_ms DESC)")
        db.execSQL("CREATE INDEX readings_kind_time ON readings(band, kind, time_ms DESC)")
        db.execSQL("CREATE INDEX readings_recent ON readings(time_ms DESC)")
        db.execSQL("CREATE INDEX readings_pending ON readings(revised_at, revision) WHERE pending=1")
        db.execSQL("""
            CREATE TABLE backup (id INTEGER PRIMARY KEY CHECK(id=1),
                scope TEXT NOT NULL, store TEXT NOT NULL, cursor INTEGER NOT NULL)
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing database migration from $oldVersion to $newVersion")
    }

    fun save(readings: List<Reading>) {
        if (readings.isNotEmpty()) transaction { db -> readings.forEach { upsert(db, it, true) } }
    }

    fun day(selected: String?, date: LocalDate, zone: ZoneId): Day {
        val db = readableDatabase
        val band = selected ?: db.rawQuery("SELECT band FROM readings ORDER BY time_ms DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (band == null) return Day(emptyList(), emptyList(), null, null, false)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli().toString()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()
        val rows = read(db, "band=? AND time_ms>=? AND time_ms<?", arrayOf(band, start, end), "time_ms ASC")
        val latest = rows.lastOrNull { it.metric == Metric.HEART }
            ?: read(db, "band=? AND kind='HEART' AND time_ms<?", arrayOf(band, start), "time_ms DESC", "1").firstOrNull()
        val earlier = db.rawQuery("SELECT 1 FROM readings WHERE band=? AND time_ms<? LIMIT 1", arrayOf(band, start))
            .use { it.moveToFirst() }
        return Day(rows.filter { it.metric == Metric.HEART }, rows.filter { it.metric == Metric.STEPS }, latest, band, earlier)
    }

    fun pendingCount(): Long = readableDatabase.rawQuery("SELECT count(*) FROM readings WHERE pending=1", null)
        .use { it.moveToFirst(); it.getLong(0) }

    fun pending(limit: Int): List<Reading> =
        read(readableDatabase, "pending=1", null, "revised_at,revision", limit.toString())

    fun checkpoint(scope: String): Checkpoint? = readableDatabase.rawQuery(
        "SELECT store,cursor FROM backup WHERE scope=?", arrayOf(scope)
    ).use { if (it.moveToFirst()) Checkpoint(it.getString(0), it.getLong(1)) else null }

    fun resetBackup(scope: String, store: String) = transaction { db ->
        db.execSQL("UPDATE readings SET pending=1 WHERE pending=0")
        writeCheckpoint(db, scope, store, 0)
    }

    fun applyBackup(scope: String, store: String, cursor: Long, sent: List<Reading>, received: List<Reading>) =
        transaction { db ->
            for (row in sent) {
                db.execSQL("UPDATE readings SET pending=0 WHERE $KEY AND revision=?", key(row) + row.revision)
            }
            for (row in received) upsert(db, row, false)
            writeCheckpoint(db, scope, store, cursor)
        }

    private fun upsert(db: SQLiteDatabase, reading: Reading, pending: Boolean) {
        val values = ContentValues(9).apply {
            put("band", reading.band)
            put("kind", reading.metric.name)
            put("origin", reading.origin.name)
            put("slot", reading.slot)
            put("time_ms", reading.time)
            put("value", reading.value)
            put("revised_at", reading.revisedAt)
            put("revision", reading.revision)
            put("pending", if (pending) 1 else 0)
        }
        if (db.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) return
        val whenNewer = "$KEY AND (revised_at < ? OR (revised_at = ? AND revision < ?))"
        val revision = reading.revisedAt.toString()
        val args = key(reading) + arrayOf(revision, revision, reading.revision)
        if (pending) {
            db.update("readings", values, "$whenNewer AND (value != ? OR time_ms != ?)",
                args + arrayOf(reading.value.toString(), reading.time.toString()))
        } else {
            db.update("readings", values, "$whenNewer AND pending=0", args)
        }
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun writeCheckpoint(db: SQLiteDatabase, scope: String, store: String, cursor: Long) {
        db.execSQL("INSERT OR REPLACE INTO backup(id,scope,store,cursor) VALUES(1,?,?,?)",
            arrayOf<Any>(scope, store, cursor))
    }

    private fun key(row: Reading) = arrayOf(row.band, row.metric.name, row.origin.name, row.slot)

    private fun read(db: SQLiteDatabase, filter: String, args: Array<String>?, sort: String,
                     limit: String? = null): List<Reading> =
        db.query("readings", COLUMNS, filter, args, null, null, sort, limit).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Reading(
                    cursor.getString(0), Metric.valueOf(cursor.getString(1)),
                    Origin.valueOf(cursor.getString(2)), cursor.getString(3), cursor.getLong(4),
                    cursor.getInt(5), cursor.getLong(6), cursor.getString(7)
                ))
            }
        }

    companion object {
        private const val KEY = "band=? AND kind=? AND origin=? AND slot=?"
        private val COLUMNS = arrayOf("band", "kind", "origin", "slot", "time_ms", "value", "revised_at", "revision")
    }
}
