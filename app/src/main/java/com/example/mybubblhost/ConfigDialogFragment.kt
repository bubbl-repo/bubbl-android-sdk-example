package com.example.mybubblhost

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import tech.bubbl.sdk.BubblSdk
import tech.bubbl.sdk.config.Environment
import tech.bubbl.sdk.utils.Logger

class ConfigDialogFragment : DialogFragment() {

    interface Listener {
        fun onConfigSaved(apiKey: String, env: Environment, changed: Boolean)
    }

    private var isFirstTime: Boolean = true


    private fun restartApp() {
        val act = activity ?: return
        val appContext = act.applicationContext
        val pm = appContext.packageManager
        val intent = pm.getLaunchIntentForPackage(appContext.packageName) ?: return

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        )

        appContext.startActivity(intent)

        // Finish current task and kill the process so that Application.onCreate runs cleanly
        act.finishAffinity()
        Runtime.getRuntime().exit(0)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFirstTime = arguments?.getBoolean(ARG_FIRST_TIME, true) ?: true

        // First-time config: cannot cancel with back / outside
        isCancelable = !isFirstTime
    }

    // ConfigDialogFragment.kt
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_config, null, false)

        val apiInput   = view.findViewById<EditText>(R.id.et_api_key)
        val envSpinner = view.findViewById<Spinner>(R.id.spinner_env)

        val envValues = Environment.values()
        envSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_item,
            envValues.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Pre-fill if we already have a tenant (for non-firstTime)
        val existing = TenantConfigStore.load(ctx)
        if (existing != null) {
            apiInput.setText(existing.apiKey)
            val idx = envValues.indexOf(existing.environment).takeIf { it >= 0 } ?: 0
            envSpinner.setSelection(idx)
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle("Bubbl configuration")
            .setView(view)
            .setPositiveButton("Save & Start", null)  // we'll override click
            .apply {
                if (!isFirstTime) {
                    setNegativeButton("Cancel") { _, _ ->
                        // Just dismiss; config stays as-is
                        dismiss()
                    }
                }
            }

        val dialog = builder.create()

        // First-time: block outside touches as well
        if (isFirstTime) {
            dialog.setCanceledOnTouchOutside(false)
        }

        dialog.setOnShowListener {
            val positive = dialog.getButton(Dialog.BUTTON_POSITIVE)
            val negative = if (!isFirstTime) dialog.getButton(Dialog.BUTTON_NEGATIVE) else null

            fun updateButtonsEnabled() {
                val hasText = apiInput.text.toString().trim().isNotEmpty()
                // If no API key → cannot close with Save or Cancel
                positive.isEnabled = hasText
                negative?.isEnabled = hasText
            }

            // Initial state
            updateButtonsEnabled()

            // Re-evaluate whenever text changes
            apiInput.addTextChangedListener {
                updateButtonsEnabled()
            }

            positive.setOnClickListener {
                val apiKey = apiInput.text.toString().trim()
                if (apiKey.isBlank()) {
                    apiInput.error = "API key is required"
                    return@setOnClickListener
                }

                val env = envValues[envSpinner.selectedItemPosition]

                val prev = TenantConfigStore.load(ctx)
                val changed = prev == null ||
                        prev.apiKey != apiKey ||
                        prev.environment != env

                TenantConfigStore.save(ctx, apiKey, env)

                if (changed) {
                    if (prev == null) {
                        // First-ever config in this process → safe to init SDK once
//                        BubblSdk.init(
//                            application = requireActivity().application,
//                            config = TenantConfigStore.toBubblConfig(
//                                TenantConfigStore.TenantConfig(apiKey, env)
//                            )
//                        )
                        // Workaround so that app gets to restarts when the apikey and environment changes, as this is the only way to achieve tenancy-switching on the android
                        restartApp()
                    } else {
                        // Existing tenant changing → save and restart app (SDK will re-init in MyApplication)
                        (activity as? Listener)?.onConfigSaved(apiKey, env, true)
                        dismiss()
                        restartApp()
                        return@setOnClickListener
                    }
                }

                (activity as? Listener)?.onConfigSaved(apiKey, env, changed)
                dismiss()
            }
        }

        return dialog
    }

    companion object {
        private const val ARG_FIRST_TIME = "first_time"

        fun newInstance(firstTime: Boolean): ConfigDialogFragment =
            ConfigDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_FIRST_TIME, firstTime)
                }
            }
    }
}
