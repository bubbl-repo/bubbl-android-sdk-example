package com.example.mybubblhost

import android.app.Application

import com.google.firebase.FirebaseApp
import tech.bubbl.sdk.BubblSdk
import tech.bubbl.sdk.utils.Logger


class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
//      initialize Firebase first
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
        TenantConfigStore.load(this)?.let { cfg ->
            Logger.log("MyApplication", "⚙️ Loaded tenant: $cfg")
            Logger.log("MyApplication", "PID=${android.os.Process.myPid()} ⚙️ Loaded tenant: $cfg")

            BubblSdk.init(
                application = this,
                config = TenantConfigStore.toBubblConfig(cfg)
            )
        }

    }
}