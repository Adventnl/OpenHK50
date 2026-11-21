package org.openhk50.sync

import android.net.Network
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.openhk50.data.HealthStore
import org.openhk50.data.Metric
import org.openhk50.data.Origin
import org.openhk50.data.Reading
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

internal class BackupSync(
    private val store: HealthStore,
    private val settings: BackupSettings,
    private val changed: () -> Unit
) {
    enum class Result { DONE, RETRY }
    private class BackupError(val detail: String, val retry: Boolean) : Exception(detail)

    fun run(network: Network?): Result {
        val config = try {
            settings.read()
        } catch (_: Exception) {
            settings.unreadable()
            changed()
            return Result.DONE
        } ?: return Result.DONE

        return try {
            exchange(config, network)
        } catch (error: BackupError) {
            failure(config, error.detail, error.retry)
        } catch (_: SSLException) {
            failure(config, "Check the server’s HTTPS certificate.", false)
        } catch (_: IOException) {
            failure(config, "Server unreachable. Backup will retry.", true)
        } catch (_: JSONException) {
            failure(config, "Unexpected server response. Check the backup service.", false)
        } catch (_: IllegalArgumentException) {
            failure(config, "Unexpected server response. Check the backup service.", false)
        } catch (_: android.database.sqlite.SQLiteException) {
            failure(config, "Couldn’t save backup progress. Backup will retry.", true)
        }
    }

    private fun failure(config: BackupSettings.Config, message: String, retry: Boolean): Result {
        if (!Thread.currentThread().isInterrupted) {
            settings.status(config, message, false)
            changed()
        }
        return if (retry) Result.RETRY else Result.DONE
    }

    private fun exchange(config: BackupSettings.Config, network: Network?): Result {
        val until = SystemClock.elapsedRealtime() + 120_000
        var reset = false
        while (SystemClock.elapsedRealtime() < until) {
            if (stopped(config)) return Result.DONE
            val checkpoint = store.checkpoint(config.scope)
            val sent = if (checkpoint == null) emptyList() else store.pending(PAGE)
            val after = checkpoint?.cursor ?: 0
            val body = JSONObject()
                .put("store", checkpoint?.store ?: JSONObject.NULL)
                .put("after", after)
                .put("readings", JSONArray(sent.map(::encode)))
            val (status, response) = request(config, network, body)
            if (stopped(config)) return Result.DONE
            val server = response.getString("store")
            require(STORE.matches(server))
            if (status == 409) {
                if (reset) throw BackupError("Backup storage changed. Try again.", true)
                reset = true
                if (!settings.ifCurrent(config) { store.resetBackup(config.scope, server) }) return Result.DONE
                continue
            }
            require(server == checkpoint?.store)
            val cursor = response.getLong("cursor")
            val more = response.getBoolean("more")
            require(cursor >= after && (!more || cursor > after))
            val readings = decodeList(response.getJSONArray("readings"))
            val canonical = decodeList(response.getJSONArray("accepted"))
            require(canonical.size == sent.size && canonical.zip(sent).all { (remote, local) ->
                remote.band == local.band && remote.metric == local.metric &&
                    remote.origin == local.origin && remote.slot == local.slot &&
                    (remote.revisedAt > local.revisedAt ||
                        (remote.revisedAt == local.revisedAt && remote.revision >= local.revision))
            })
            if (!settings.ifCurrent(config) { store.applyBackup(config.scope, server, cursor, sent, readings + canonical) }) {
                return Result.DONE
            }
            if (readings.isNotEmpty() || sent.isNotEmpty()) changed()
            if (!more && sent.size < PAGE) {
                settings.status(config, null, true)
                changed()
                return Result.DONE
            }
        }
        return failure(config, "Backup will continue when the server is reachable.", true)
    }

    private fun stopped(config: BackupSettings.Config) =
        Thread.currentThread().isInterrupted || !settings.current(config)

    private fun request(config: BackupSettings.Config, network: Network?, body: JSONObject): Pair<Int, JSONObject> {
        val url = URL("${config.endpoint}/v1/exchange")
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.key}")
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            when (status) {
                200, 409 -> Unit
                401, 403 -> throw BackupError("Backup key rejected. Check your server key.", false)
                404 -> throw BackupError("Backup service wasn’t found. Check the server address.", false)
                408, 425, 429, in 500..599 -> throw BackupError("Server unavailable. Backup will retry.", true)
                else -> throw BackupError("Server returned HTTP $status. Check the backup configuration.", false)
            }
            val stream = (if (status == 200) connection.inputStream else connection.errorStream)
                ?: throw IOException("Empty backup response")
            val payload = stream.use {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count == -1) break
                    if (Thread.currentThread().isInterrupted || out.size() + count > 512 * 1024) {
                        throw IOException("Aborted backup response")
                    }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            return status to JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(row: Reading) = JSONObject()
        .put("band", row.band).put("kind", row.metric.name).put("origin", row.origin.name)
        .put("slot", row.slot).put("time", row.time).put("value", row.value)
        .put("revised_at", row.revisedAt).put("revision", row.revision)

    private fun decodeList(rows: JSONArray): List<Reading> {
        require(rows.length() <= PAGE)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            Reading(row.getString("band"), Metric.valueOf(row.getString("kind")),
                Origin.valueOf(row.getString("origin")), row.getString("slot"), row.getLong("time"),
                row.getInt("value"), row.getLong("revised_at"), row.getString("revision"))
        }
    }

    companion object {
        private const val PAGE = 200
        private val STORE = Regex("[0-9a-f]{32}")
    }
}
