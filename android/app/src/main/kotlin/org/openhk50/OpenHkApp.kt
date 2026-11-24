package org.openhk50

import android.app.Application
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import org.openhk50.alerts.PhoneVibration
import org.openhk50.band.BandClient
import org.openhk50.data.HealthStore
import org.openhk50.data.EcgStore
import org.openhk50.sync.BackupSettings
import org.openhk50.sync.BackupSync
import java.util.concurrent.Executors

class OpenHkApp : Application() {
    internal val main = Handler(Looper.getMainLooper())
    internal val databaseThread = Executors.newSingleThreadExecutor()
    internal val backupThread = Executors.newSingleThreadExecutor()
    internal val health by lazy { HealthStore(this) }
    internal val ecg by lazy { EcgStore(filesDir) }
    internal val phoneVibration by lazy { PhoneVibration(this) }
    internal val settings by lazy { BackupSettings(this) }
    internal val backup by lazy { BackupSync(health, settings, ::notifyChanged) }
    internal var changed: (() -> Unit)? = null
    internal var callAlertsRunning = false
    internal var callAlertsIssue: String? = null
    internal var storageIssue: String? = null
        private set

    internal val band by lazy {
        BandClient(this, { readings -> write { health.save(readings) } },
            { band, nights -> write { health.saveSleep(band, nights) } },
            { band, date, activity -> write { health.saveActivitySnapshot(band, date, activity) } })
    }

    internal fun write(issue: String = "Couldn’t save readings. Check phone storage and sync again.", save: () -> Unit) {
        databaseThread.execute {
            try {
                save()
                storageIssue = null
            } catch (_: SQLiteException) {
                storageIssue = issue
            }
            notifyChanged()
        }
    }

    internal fun notifyChanged() { main.post { changed?.invoke() } }
}
