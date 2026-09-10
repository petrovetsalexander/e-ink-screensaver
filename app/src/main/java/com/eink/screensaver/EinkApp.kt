package com.eink.screensaver

import android.app.Application

/**
 * Exists so that [EventLog] has a context before anything else in the process
 * runs. Every other entry point — the service, the lock screen, the two
 * system-bound services, the settings screens — can be started first by the
 * system, and the trace is worth nothing if it misses whichever one that was.
 */
class EinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
