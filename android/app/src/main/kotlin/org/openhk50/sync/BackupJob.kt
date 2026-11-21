package org.openhk50.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import org.openhk50.OpenHkApp
import java.util.concurrent.Future

class BackupJob : JobService() {
    private val running = mutableMapOf<Int, Pair<JobParameters, Future<*>>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as OpenHkApp
        val network = if (Build.VERSION.SDK_INT >= 28) params.network else null
        val future = app.backupThread.submit {
            val retry = app.backup.run(network) == BackupSync.Result.RETRY
            mainExecutorCompat(app) {
                if (running[params.jobId]?.first === params) {
                    running.remove(params.jobId)
                    jobFinished(params, retry)
                }
            }
        }
        running[params.jobId] = params to future
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.second?.cancel(true)
        return true
    }

    override fun onDestroy() {
        running.values.forEach { it.second.cancel(true) }
        running.clear()
        super.onDestroy()
    }

    private fun mainExecutorCompat(app: OpenHkApp, work: () -> Unit) { app.main.post(work) }

    companion object {
        private const val PERIODIC = 510
        private const val MANUAL = 511

        internal fun schedule(context: Context, immediate: Boolean = false, replaceManual: Boolean = false): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, BackupJob::class.java)
            fun job(id: Int) = JobInfo.Builder(id, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            val periodicOk = scheduler.getPendingJob(PERIODIC) != null ||
                scheduler.schedule(job(PERIODIC).setPersisted(true).setPeriodic(3_600_000).build()) == JobScheduler.RESULT_SUCCESS
            if (immediate) {
                if (replaceManual) scheduler.cancel(MANUAL)
                val manualOk = scheduler.getPendingJob(MANUAL) != null ||
                    scheduler.schedule(job(MANUAL).build()) == JobScheduler.RESULT_SUCCESS
                return manualOk && periodicOk
            }
            return periodicOk
        }

        internal fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).apply {
                cancel(PERIODIC)
                cancel(MANUAL)
            }
        }
    }
}
