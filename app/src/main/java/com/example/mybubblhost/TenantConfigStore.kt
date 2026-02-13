package com.example.mybubblhost

import android.content.Context
import tech.bubbl.sdk.config.BubblConfig
import tech.bubbl.sdk.config.Environment
import tech.bubbl.sdk.utils.Logger

object TenantConfigStore {
    private const val PREFS_NAME = "bubbl_tenant_config"
    private const val KEY_API = "bubbl_api_key"
    private const val KEY_ENV = "bubbl_environment"

    data class TenantConfig(
        val apiKey: String,
        val environment: Environment
    )

    fun load(context: Context): TenantConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val api = prefs.getString(KEY_API, null) ?: return null
        if (api.isBlank()) return null

        val envName = prefs.getString(KEY_ENV, Environment.STAGING.name)
        Logger.log("TenantConfigStore", "load() raw envName=$envName")

        val env = try {
            Environment.valueOf(envName ?: Environment.STAGING.name)
        } catch (_: IllegalArgumentException) {
            Environment.STAGING
        }

        return TenantConfig(apiKey = api, environment = env)
    }

    fun save(context: Context, apiKey: String, environment: Environment) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 🔴 was .apply()
        val ok = prefs.edit()
            .putString(KEY_API, apiKey)
            .putString(KEY_ENV, environment.name)
            .commit()   // ✅ synchronous – guarantees write finished

        Logger.log(
            "TenantConfigStore",
            "save() → apiKey=$apiKey env=$environment (commit=$ok)"
        )

    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    fun toBubblConfig(config: TenantConfig): BubblConfig =
        BubblConfig(
            apiKey = config.apiKey,
            environment = config.environment,
            segmentationTags = emptyList(),
            geoPollInterval = 5 * 60_000L,
            defaultDistance = 10
        )
}
