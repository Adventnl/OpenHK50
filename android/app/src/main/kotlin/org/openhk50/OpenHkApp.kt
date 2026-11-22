package org.openhk50

import android.app.Application
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import org.openhk50.band.BandClient
import org.openhk50.data.HealthStore
import org.openhk50.sync.BackupSettings
import org.openhk50.sync.BackupSync
import java.util.concurrent.Executors

class OpenHkApp : Application() {
    internal val main = Handler(Looper.getMainLooper())
    internal val databaseThread = Executors.newSingleThreadExecutor()
    internal val backupThread = Executors.newSingleThreadExecutor()
    internal val health by lazy { HealthStore(this) }
    internal val settings by lazy { BackupSettings(this) }
    internal val backup by lazy { BackupSync(health, settings, ::notifyChanged) }
    internal var changed: (() -> Unit)? = null
    internal var storageIssue: String? = null
        private set

    internal val band by lazy {
        BandClient(this, { readings -> write { health.save(readings) } },
            { band, nights -> write { health.saveSleep(band, nights) } })
    }

    private fun write(save: () -> Unit) {
        databaseThread.execute {
            try {
                save()
                storageIssue = null
            } catch (_: SQLiteException) {
                storageIssue = "Couldn’t save readings. Check phone storage and sync again."
            }
            notifyChanged()
        }
    }

    private fun notifyChanged() { main.post { changed?.invoke() } }
}
