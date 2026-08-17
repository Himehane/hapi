package app.hapi.companion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.hapi.companion.di.AppGraph

/**
 * Owns the process-singleton [AppGraph]. Compose reads it through
 * [app.hapi.companion.di.LocalAppGraph]; non-Compose entry points (future FCM
 * service, WorkManager workers) reach it via `(context.applicationContext as
 * HapiApp).appGraph`.
 *
 * Also bridges [ProcessLifecycleOwner] into the graph (B-M3ab): foreground /
 * background drives `SseEngine.setLifecycleForeground` (retry deferral,
 * stale-socket rebuild on resume) and `POST /api/visibility` reporting so the
 * hub can suppress redundant push while the app is visibly connected.
 */
class HapiApp : Application() {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
        appGraph.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appGraph.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                appGraph.setForeground(false)
            }
        })
    }
}
