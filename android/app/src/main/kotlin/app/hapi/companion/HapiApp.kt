package app.hapi.companion

import android.app.Application
import app.hapi.companion.di.AppGraph

/**
 * Owns the process-singleton [AppGraph]. Compose reads it through
 * [app.hapi.companion.di.LocalAppGraph]; non-Compose entry points (future FCM
 * service, WorkManager workers) reach it via `(context.applicationContext as
 * HapiApp).appGraph`.
 */
class HapiApp : Application() {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
        appGraph.start()
    }
}
