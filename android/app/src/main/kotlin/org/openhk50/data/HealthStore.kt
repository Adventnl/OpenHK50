package org.openhk50.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

class HealthStore(context: Context) : SQLiteOpenHelper(context, "health.sqlite3", null, 5) {
    internal data class Day(
        private val readings: Map<Metric, List<Aggregate>>, val sleep: SleepNight?, val earlier: Boolean,
        val spot: Long? = null
    ) {
        fun buckets(metric: Metric) = readings[metric].orEmpty().map { it.value(metric.isTotal) }
        fun value(metric: Metric) = readings[metric].orEmpty().combined().value(metric.isTotal)
    }
    internal data class Trend(val buckets: List<Aggregate>, val earlier: Boolean)
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
        createWorkoutTable(db)
        createWorkoutPauses(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSleepTable(db)
        if (oldVersion < 3) createWorkoutTable(db)
        if (oldVersion in 2..3) {
            db.execSQL("ALTER TABLE sleep_nights ADD COLUMN asleep_minutes INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT band, day, segments FROM sleep_nights", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val asleep = decodeSleep(cursor.getBlob(2)).filter { it.stage != SleepStage.AWAKE }.sumOf { it.minutes }
                    db.execSQL("UPDATE sleep_nights SET asleep_minutes=? WHERE band=? AND day=?",
                        arrayOf(asleep, cursor.getString(0), cursor.getString(1)))
                }
            }
        }
        if (oldVersion < 5) createWorkoutPauses(db)
    }

    private fun createSleepTable(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE sleep_nights (
            band TEXT NOT NULL, day TEXT NOT NULL, start_minute INTEGER NOT NULL, segments BLOB NOT NULL,
            asleep_minutes INTEGER NOT NULL,
            PRIMARY KEY (band, day)
        ) WITHOUT ROWID
    """.trimIndent())

    private fun createWorkoutTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE workouts (
                start_ms INTEGER PRIMARY KEY, type TEXT NOT NULL, band TEXT,
                end_ms INTEGER CHECK(end_ms >= start_ms)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX workouts_active ON workouts((1)) WHERE end_ms IS NULL")
    }

    private fun createWorkoutPauses(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE workout_pauses (
            workout_ms INTEGER NOT NULL, pause_ms INTEGER NOT NULL,
            resume_ms INTEGER CHECK(resume_ms >= pause_ms),
            PRIMARY KEY (workout_ms, pause_ms)
        ) WITHOUT ROWID
    """.trimIndent())

    internal fun workouts(limit: Int, offset: Int = 0): WorkoutLog {
        val db = readableDatabase
        val active = db.workoutRows("end_ms IS NULL", 1).firstOrNull()
        val recorded = if (limit == 0) emptyList() else db.workoutRows("end_ms IS NOT NULL", limit + 1, offset)
        return WorkoutLog(active, recorded.take(limit), recorded.size > limit)
    }

    internal fun startWorkout(type: WorkoutType, start: Long, band: String?) {
        writableDatabase.insertWithOnConflict("workouts", null, ContentValues(3).apply {
            put("start_ms", start)
            put("type", type.name)
            put("band", band)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    internal fun pauseWorkout(start: Long, now: Long) {
        writableDatabase.execSQL("""
            INSERT OR IGNORE INTO workout_pauses (workout_ms, pause_ms)
            SELECT start_ms, max(?, start_ms, coalesce((
                SELECT max(resume_ms) FROM workout_pauses WHERE workout_ms=start_ms
            ), start_ms)) FROM workouts WHERE start_ms=? AND end_ms IS NULL AND NOT EXISTS (
                SELECT 1 FROM workout_pauses WHERE workout_ms=start_ms AND resume_ms IS NULL
            )
        """.trimIndent(), arrayOf(now, start))
    }

    internal fun resumeWorkout(start: Long, now: Long) = closeWorkoutPause(writableDatabase, start, now)

    internal fun endWorkout(start: Long, now: Long) = transaction { db ->
        closeWorkoutPause(db, start, now)
        db.execSQL("""
            UPDATE workouts SET end_ms=max(?, start_ms, coalesce((
                SELECT max(resume_ms) FROM workout_pauses WHERE workout_ms=start_ms
            ), start_ms)) WHERE start_ms=? AND end_ms IS NULL
        """.trimIndent(), arrayOf(now, start))
    }

    private fun closeWorkoutPause(db: SQLiteDatabase, start: Long, now: Long) = db.execSQL("""
        UPDATE workout_pauses SET resume_ms=max(pause_ms, ?)
        WHERE workout_ms=? AND resume_ms IS NULL
    """.trimIndent(), arrayOf(now, start))

    internal fun workoutMinutes(date: LocalDate, zone: ZoneId): Long {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli().toString()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().toString()
        return readableDatabase.rawQuery("""
            WITH bounds AS (SELECT CAST(? AS INTEGER) AS day_start, CAST(? AS INTEGER) AS day_end)
            SELECT coalesce(sum(min(end_ms, day_end) - max(start_ms, day_start) - coalesce((
                SELECT sum(min(resume_ms, day_end) - max(pause_ms, day_start)) FROM workout_pauses
                WHERE workout_ms=start_ms AND pause_ms<day_end AND resume_ms>day_start
            ), 0)), 0) FROM workouts CROSS JOIN bounds WHERE start_ms<day_end AND end_ms>day_start
        """.trimIndent(), arrayOf(start, end)).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) / 60_000
        }
    }

    private fun SQLiteDatabase.workoutRows(filter: String, limit: Int, offset: Int = 0) =
        rawQuery("""
            SELECT start_ms, type, end_ms, (
                SELECT round(avg(value)) FROM readings
                WHERE band=workouts.band AND kind='HEART' AND origin='HISTORY'
                AND time_ms>=start_ms AND time_ms<=end_ms
                AND NOT EXISTS (SELECT 1 FROM workout_pauses WHERE workout_ms=start_ms
                    AND time_ms>=pause_ms AND time_ms<resume_ms)
            ), (SELECT pause_ms FROM workout_pauses WHERE workout_ms=start_ms AND resume_ms IS NULL),
               (SELECT coalesce(sum(resume_ms-pause_ms), 0) FROM workout_pauses WHERE workout_ms=start_ms)
            FROM workouts WHERE $filter ORDER BY start_ms DESC LIMIT ? OFFSET ?
        """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Workout(cursor.getLong(0), WorkoutType.valueOf(cursor.getString(1)),
                    if (cursor.isNull(2)) null else cursor.getLong(2),
                    if (cursor.isNull(3)) null else cursor.getInt(3),
                    if (cursor.isNull(4)) null else cursor.getLong(4), cursor.getLong(5)))
            }
        }

    fun save(readings: List<Reading>) {
        if (readings.isNotEmpty()) transaction { db -> readings.forEach { upsert(db, it, true) } }
    }

    fun saveSleep(band: String, nights: List<SleepNight>) = transaction { db ->
        for (night in nights) {
            val segments = night.segments.flatMap { listOf(it.stage.ordinal.toByte(), it.minutes.toByte()) }.toByteArray()
            db.insertWithOnConflict("sleep_nights", null, ContentValues(5).apply {
                put("band", band)
                put("day", night.day.toString())
                put("start_minute", night.startMinute)
                put("segments", segments)
                put("asleep_minutes", night.asleepMinutes)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    internal fun day(selected: String?, date: LocalDate, zone: ZoneId, metric: Metric? = null,
                     history: Boolean = false): Day {
        val db = readableDatabase
        val band = selected ?: latestBand(db) ?: return Day(emptyMap(), null, false)
        val bounds = dayBoundaries(date, zone)
        val wantsReadings = !history || metric != null
        val series = if (wantsReadings) readingBuckets(db, band, bounds, metric) else emptyMap()
        val sleep = if (!history || metric == null) sleep(db, band, date) else null
        val earlier = history && earlier(db, band, date, bounds.first(), metric)
        val spot = metric?.takeIf { it.liveType != null }?.let {
            db.rawQuery("""
                SELECT value FROM readings WHERE band=? AND kind=? AND time_ms>=? AND time_ms<?
                AND origin='LIVE' ORDER BY time_ms DESC, revision DESC LIMIT 1
            """.trimIndent(), arrayOf(band, it.name, bounds.first().toString(), bounds.last().toString()))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }
        return Day(series, sleep, earlier, spot)
    }

    internal fun trend(selected: String?, dates: List<LocalDate>, end: LocalDate, zone: ZoneId, metric: Metric?): Trend {
        val db = readableDatabase
        val band = selected ?: latestBand(db) ?: return Trend(List(dates.size) { Aggregate() }, false)
        val days = dates + end.plusDays(1)
        val bounds = days.map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
        val buckets = if (metric == null) sleepBuckets(db, band, days) else readingBuckets(db, band, bounds, metric)[metric]
        return Trend(buckets ?: List(dates.size) { Aggregate() }, earlier(db, band, dates.first(), bounds.first(), metric))
    }

    private fun readingBuckets(db: SQLiteDatabase, band: String, bounds: List<Long>, metric: Metric?): Map<Metric, List<Aggregate>> {
        val buckets = bounds.zipWithNext()
        val filter = if (metric == null) "" else "AND kind=?"
        val sql = buckets.indices.joinToString(" UNION ALL ") { index ->
            "SELECT $index, kind, sum(value), count(*) FROM readings " +
                "WHERE band=? $filter AND time_ms>=? AND time_ms<? AND origin='HISTORY' GROUP BY kind"
        }
        val args = buckets.flatMap { (start, end) ->
            listOfNotNull(band, metric?.name, start.toString(), end.toString())
        }
        return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildMap<Metric, MutableList<Aggregate>> {
                while (cursor.moveToNext()) {
                    val kind = Metric.valueOf(cursor.getString(1))
                    getOrPut(kind) { MutableList(buckets.size) { Aggregate() } }[cursor.getInt(0)] =
                        Aggregate(cursor.getLong(2), cursor.getLong(3))
                }
            }
        }
    }

    private fun sleepBuckets(db: SQLiteDatabase, band: String, bounds: List<LocalDate>): List<Aggregate> {
        val buckets = bounds.zipWithNext()
        val sql = buckets.indices.joinToString(" UNION ALL ") { index ->
            "SELECT $index AS bucket, coalesce(sum(asleep_minutes), 0), count(*) " +
                "FROM sleep_nights WHERE band=? AND day>=? AND day<?"
        } + " ORDER BY bucket"
        val args = buckets.flatMap { (start, end) -> listOf(band, start.toString(), end.toString()) }
        return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Aggregate(cursor.getLong(1), cursor.getLong(2))) }
        }
    }

    private fun latestBand(db: SQLiteDatabase) =
        db.rawQuery("SELECT band FROM readings ORDER BY time_ms DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: db.rawQuery("SELECT band FROM sleep_nights ORDER BY day DESC LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun earlier(db: SQLiteDatabase, band: String, date: LocalDate, start: Long, metric: Metric?) =
        if (metric == null) db.rawQuery("SELECT 1 FROM sleep_nights WHERE band=? AND day<? LIMIT 1",
            arrayOf(band, date.toString())).use { it.moveToFirst() }
        else db.rawQuery("SELECT 1 FROM readings WHERE band=? AND kind=? AND time_ms<? LIMIT 1",
            arrayOf(band, metric.name, start.toString())).use { it.moveToFirst() }

    private fun sleep(db: SQLiteDatabase, band: String, date: LocalDate) = db.rawQuery(
        "SELECT start_minute, segments FROM sleep_nights WHERE band=? AND day=?", arrayOf(band, date.toString())
    ).use { cursor ->
        if (cursor.moveToFirst()) SleepNight(date, cursor.getInt(0), decodeSleep(cursor.getBlob(1))) else null
    }

    private fun decodeSleep(bytes: ByteArray) = (0 until bytes.lastIndex step 2).map { i ->
        SleepSegment(SleepStage.entries[bytes[i].toInt()], bytes[i + 1].toInt() and 255)
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
