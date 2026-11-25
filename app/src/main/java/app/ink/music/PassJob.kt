package app.ink.music

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spotify's passes kept fresh on a paired computer while ink music isn't open here (2026-11-24: listening on the
 * computer, its passes ran out an hour after the phone was last opened, and its canvases stopped). The computer has no
 * web player to make its own, so every half hour or so, on wifi, with Spotify signed in and a computer paired, the
 * phone makes new passes and is in step once (Sync carries them), as if it had been opened. Android runs it when it
 * suits (a little late on a phone asleep, never on mobile data).
 */
class PassJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartJob(p: JobParameters): Boolean {
        if (!Spotify.signedIn()) return false
        SyncPhone.start(this)
        if (SyncPhone.peers.value.isEmpty()) return false
        scope.launch {
            runCatching {
                // the passes made where the web player lives (the main thread); the sync on the network's own
                withTimeoutOrNull(60_000) { Spotify.fresh(this@PassJob) }
                withTimeoutOrNull(60_000) { kotlinx.coroutines.withContext(Dispatchers.IO) { SyncPhone.now() } }
            }.onFailure { android.util.Log.d("ink-spotify", "passes for the computer: $it") }
            jobFinished(p, false)
        }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean { scope.coroutineContext.cancelChildren(); return true }

    companion object {
        private const val ID = 4711

        /** Asked for once (as ink music starts): every 30 minutes on an unmetered network; already asked, left be. */
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            runCatching {
                js.schedule(JobInfo.Builder(ID, ComponentName(c, PassJob::class.java))
                    .setPeriodic(30 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .build())
            }
        }
    }
}
