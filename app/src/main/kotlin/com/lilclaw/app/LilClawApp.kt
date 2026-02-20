package com.lilclaw.app

import android.app.Application
import com.lilclaw.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LilClawApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LilClawApp)
            modules(appModule)
        }
        // Gateway lifecycle is now managed by SetupViewModel:
        // - First launch: bootstrap() after provider config
        // - App restart: quickStart() when SetupViewModel detects setup is complete
    }
}
