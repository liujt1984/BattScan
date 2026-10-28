package com.battscan.leadacid

import android.app.Application
import androidx.room.Room
import com.battscan.leadacid.data.SettingsStore
import com.battscan.leadacid.data.db.AppDatabase
import com.battscan.leadacid.monitor.MonitorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled composition root. The graph is three objects, so a DI framework would cost more to
 * read than it saves, and keeping it here lets the foreground service and the UI share one engine.
 */
class BattScanApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var engine: MonitorEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        database = Room.databaseBuilder(this, AppDatabase::class.java, "battscan.db")
            .fallbackToDestructiveMigration()
            .build()
        engine = MonitorEngine(this, appScope, settings, database)
        engine.start()
    }
}
