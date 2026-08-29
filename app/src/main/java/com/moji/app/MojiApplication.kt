package com.moji.app

import android.app.Application
import com.moji.app.data.MojiDatabase
import com.moji.app.data.MojiRepository
import com.moji.app.settings.MojiSettings
import com.moji.app.capture.CaptureCleanupWorker
import com.moji.app.ai.AiCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MojiApplication : Application() {
    val database by lazy { MojiDatabase.create(this) }
    val repository by lazy { MojiRepository(database) }
    val settings by lazy { MojiSettings(this) }
    val aiCredentials by lazy { AiCredentialStore(this) }

    override fun onCreate() {
        super.onCreate()
        CaptureCleanupWorker.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.seedCategories()
        }
    }
}
