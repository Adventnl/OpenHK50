package org.openhk50.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

class HealthStore(context: Context) : SQLiteOpenHelper(context, "health.sqlite3", null, 2) {
    data class Day(
        private val readings: Map<Metric, List<Reading>>, val sleep: SleepNight?, val earlier: Boolean
    ) {
        fun readings(metric: Metric) = readings[metric].orEmpty()

        fun value(metric: Metric): Long? {
            val samples = readings(metric)
            if (samples.isEmpty()) return null
            return if (metric == Metric.STEPS) samples.sumOf { it.value.toLong() }
            else samples.last().value.toLong()
        }
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
        createSleepTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createSleepTable(db)
        else error("Missing database migration from $oldVersion to $newVersion")
    }

    private fun createSleepTable(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE sleep_nights (
            band TEXT NOT NULL, day TEXT NOT NULL, start_minute INTEGER NOT NULL, segments BLOB NOT NULL,
            PRIMARY KEY (band, day)
        ) WITHOUT ROWID
    """.trimIndent())

    fun save(readings: List<Reading>) {
        if (readings.isNotEmpty()) transaction { db -> readings.forEach { upsert(db, it, true) } }
    }

    fun saveSleep(band: String, nights: List<SleepNight>) = transaction { db ->
        for (night in nights) {
            val segments = night.segments.flatMap { listOf(it.stage.ordinal.toByte(), it.minutes.toByte()) }.toByteArray()
            db.insertWithOnConflict("sleep_nights", null, ContentValues(4).apply {
                put("band", band)
                put("day", night.day.toString())
                put("start_minute", night.startMinute)
                put("segments", segments)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun day(selected: String?, date: LocalDate, zone: ZoneId): Day {
        val db = readableDatabase
        val band = selected ?: db.rawQuery("SELECT band FROM readings ORDER BY time_ms DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: db.rawQuery("SELECT band FROM sleep_nights ORDER BY day DESC LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
        if (band == null) return Day(emptyMap(), null, false)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli().toString()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()
        val rows = read(db, "band=? AND time_ms>=? AND time_ms<?", arrayOf(band, start, end), "time_ms ASC")
        val earlier = db.rawQuery("""
            SELECT 1 FROM readings WHERE band=? AND time_ms<?
            UNION ALL SELECT 1 FROM sleep_nights WHERE band=? AND day<? LIMIT 1
        """.trimIndent(), arrayOf(band, start, band, date.toString()))
            .use { it.moveToFirst() }
        val sleep = db.rawQuery("SELECT start_minute, segments FROM sleep_nights WHERE band=? AND day=?",
            arrayOf(band, date.toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val bytes = cursor.getBlob(1)
                val segments = (0 until bytes.lastIndex step 2).map { i ->
                    SleepSegment(SleepStage.entries[bytes[i].toInt()], bytes[i + 1].toInt() and 255)
                }
                SleepNight(date, cursor.getInt(0), segments)
            }
        }
        return Day(rows.groupBy { it.metric }, sleep, earlier)
    }

    fun pendingCount(): Long = readableDatabase.rawQuery("SELECT count(*) FROM readings WHERE pending=1", null)
        .use { it.moveToFirst(); it.getLong(0) }

    fun pending(limit: Int): List<Reading> =
        read(readableDatabase, "pending=1", null, "revised_at,revision", limit.toString())

    fun checkpoint(scope: String): Checkpoint? = readableDatabase.rawQuery(
        "SELECT store,cursor FROM backup WHERE scope=?", arrayOf(scope)
    ).use { if (it.moveToFirst()) Checkpoint(it.getString(0), it.getLong(1)) else null }

    fun resetBackup(scope: String, store: String) = transaction { db ->
        db.execSQL("UPDATE readings SET pending=1 WHERE pending=0 AND kind IN ('HEART','STEPS')")
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
            put("pending", if (pending && reading.metric.backedUp) 1 else 0)
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
