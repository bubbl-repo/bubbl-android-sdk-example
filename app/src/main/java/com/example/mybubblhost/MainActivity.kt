package com.example.mybubblhost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolygonOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tech.bubbl.sdk.BubblSdk
import tech.bubbl.sdk.config.BubblConfig
import tech.bubbl.sdk.config.Environment
import tech.bubbl.sdk.notifications.NotificationRouter
import tech.bubbl.sdk.permissions.PermissionManager
import tech.bubbl.sdk.permissions.toast
import tech.bubbl.sdk.models.ChoiceSelection
import tech.bubbl.sdk.models.SurveyAnswer
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import tech.bubbl.sdk.utils.Logger
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import android.graphics.Rect
import androidx.core.widget.NestedScrollView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton


class MainActivity : AppCompatActivity(), OnMapReadyCallback, ConfigDialogFragment.Listener {
    private var pendingModal: NotificationRouter.DomainNotification? = null

    /* ---------- permissions ---------- */
    private val permMgr by lazy { PermissionManager(this) }
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>

    /* ---------- UI refs ---------- */
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var locStatusTv: TextView
    private lateinit var notifStatusTv: TextView
    private lateinit var resetBtn: Button
    private lateinit var downloadButton: Button
    private lateinit var clearLogsButton: Button
    private lateinit var apiStatusTv: TextView

    /* ---------- Segment UI refs ---------- */
    private lateinit var segmentFeedbackTv: TextView
    private lateinit var customSegmentInput: EditText
    private lateinit var segmentButtonsGrid: GridLayout

    fun tokenButton(view: View) {
        try {
            val tenant = TenantConfigStore.load(this)
            val token = tenant?.apiKey ?: "Not set"

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Bubbl Token", token)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Token copied: $token")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying token", e)
            Toast.makeText(this, "Error copying token: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogFile() {
        val logFile = Logger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No log file found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Bubbl Logs"))
    }

    private fun hasLocationPermission(): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        val coarse =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun enableMyLocationIfPermitted() {
        if (!::googleMap.isInitialized) return
        try {
            val allowed = hasLocationPermission()
            googleMap.isMyLocationEnabled = allowed
            googleMap.uiSettings.isMyLocationButtonEnabled = allowed
        } catch (_: SecurityException) {
            googleMap.isMyLocationEnabled = false
            googleMap.uiSettings.isMyLocationButtonEnabled = false
        }
    }

    private fun clearLogs() {
        Logger.clearLogs()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    // ═════════════════════════════════════════════════════════════
    // SEGMENT TESTING FUNCTIONALITY
    // ═════════════════════════════════════════════════════════════

    private fun updateSegments(segments: List<String>) {
        val segmentsText = segments.joinToString(", ")
        Logger.log("MainActivity", "🏷️ Updating segments: $segmentsText")

        BubblSdk.updateSegments(segments) { success ->
            runOnUiThread {
                if (success) {
                    showSegmentFeedback("✅ Segments updated: $segmentsText", isSuccess = true)
                    Logger.log("MainActivity", "✅ Segments updated successfully")
                } else {
                    showSegmentFeedback("❌ Failed to update segments", isSuccess = false)
                    Logger.log("MainActivity", "❌ Failed to update segments")
                }
            }
        }
    }

    private fun showSegmentFeedback(message: String, isSuccess: Boolean) {
        segmentFeedbackTv.text = message
        segmentFeedbackTv.visibility = View.VISIBLE

        //Background color based on success/failure
        val backgroundColor = if (isSuccess) {
            android.graphics.Color.parseColor("#C8E6C9") // Light green
        } else {
            android.graphics.Color.parseColor("#FFCDD2") // Light red
        }
        segmentFeedbackTv.setBackgroundColor(backgroundColor)

        // Auto-dismiss after 3 seconds
        segmentFeedbackTv.postDelayed({
            segmentFeedbackTv.visibility = View.GONE
        }, 3000)
    }

    private fun submitCustomSegments() {
        val input = customSegmentInput.text.toString().trim()

        if (input.isEmpty()) {
            showSegmentFeedback("⚠️ Please enter at least one segment", isSuccess = false)
            return
        }

        val segments = input.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            showSegmentFeedback("⚠️ No valid segments found", isSuccess = false)
            return
        }

        customSegmentInput.text.clear()

        updateSegments(segments)
    }

    private fun setupSegmentButtons() {
        // Preset buttons
        findViewById<Button>(R.id.btn_segment_premium).setOnClickListener {
            updateSegments(listOf("premium"))
        }

        findViewById<Button>(R.id.btn_segment_free).setOnClickListener {
            updateSegments(listOf("free_user"))
        }

        findViewById<Button>(R.id.btn_segment_vip).setOnClickListener {
            updateSegments(listOf("vip"))
        }

        findViewById<Button>(R.id.btn_segment_trial).setOnClickListener {
            updateSegments(listOf("trial"))
        }

        findViewById<Button>(R.id.btn_segment_inactive).setOnClickListener {
            updateSegments(listOf("inactive"))
        }

        findViewById<Button>(R.id.btn_segment_power_user).setOnClickListener {
            updateSegments(listOf("beta", "power_user", "supporter"))
        }

        findViewById<Button>(R.id.btn_segment_clear).setOnClickListener {
            updateSegments(emptyList())
        }

        // Custom segment button
        findViewById<Button>(R.id.btn_send_custom_segments).setOnClickListener {
            hideKeyboard()
            submitCustomSegments()
        }
    }

    // ═════════════════════════════════════════════════════════════

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val scroll = findViewById<NestedScrollView>(R.id.main_scroll)
        val input = findViewById<EditText>(R.id.custom_segment_input)

// Pad the scroll area when the keyboard is shown
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, max(ime.bottom, sys.bottom))
            insets
        }

// Ensure the input is brought fully into view on focus
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scroll.post {
                    val r = Rect()
                    input.getDrawingRect(r)
                    scroll.offsetDescendantRectToMyCoords(input, r)
                    scroll.requestChildRectangleOnScreen(input, r, true)
                }
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                input.clearFocus(); true
            } else false
        }

        /* bind views */
        mapView = findViewById(R.id.map_view)
        locStatusTv = findViewById(R.id.location_permission_status)
        notifStatusTv = findViewById(R.id.notification_permission_status)
        resetBtn = findViewById(R.id.btn_reset_camera)
        apiStatusTv = findViewById(R.id.api_key_label)
        downloadButton = findViewById(R.id.download_logs_button)
        clearLogsButton = findViewById(R.id.clear_logs_button)

        /* bind segment views */
        segmentFeedbackTv = findViewById(R.id.segment_feedback_tv)
        customSegmentInput = findViewById(R.id.custom_segment_input)
        segmentButtonsGrid = findViewById(R.id.segment_buttons_grid)

        mapView.onCreate(savedInstanceState)
        resetBtn.setOnClickListener { centreOnUser() }

        downloadButton.setOnClickListener {
            shareLogFile()
        }

        clearLogsButton.setOnClickListener {
            clearLogs()
        }

        /* Setup segment buttons */
        setupSegmentButtons()

        /* Register for SDK broadcasts */
        registerModalReceiver()
        /* Handle notification intent if launched from notification */
        handleNotificationIntent(intent)

        val tenant = TenantConfigStore.load(this)
        if (tenant == null) {
            apiStatusTv.text = "API Key: Not set"

            // First time → blocking dialog, but only if not already shown
            if (supportFragmentManager.findFragmentByTag("bubbl_config") == null) {
                ConfigDialogFragment.newInstance(firstTime = true)
                    .show(supportFragmentManager, "bubbl_config")
            }
        } else {
            apiStatusTv.text = "API Key: ${tenant.apiKey} (${tenant.environment})"
        }

        /* register launcher early */
        permLauncher = permMgr.registerLauncher { granted ->
            if (granted) {
                onAllPermissionsGranted()
                enableMyLocationIfPermitted()
                centreOnUser()
            } else {
                enableMyLocationIfPermitted()
                toast("Permissions denied")
            }
        }

        val granted = permMgr.locationGranted() &&
                (Build.VERSION.SDK_INT < 33 || permMgr.notificationGranted())

        if (granted) onAllPermissionsGranted()
        else permLauncher.launch(permMgr.requiredPermissions())


        val configBtn: ImageButton = findViewById(R.id.btn_config)
        configBtn.setOnClickListener {
            val existing = supportFragmentManager.findFragmentByTag("bubbl_config")
            if (existing == null) {
                ConfigDialogFragment.newInstance(firstTime = false)
                    .show(supportFragmentManager, "bubbl_config")
            }
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun registerModalReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            modalReceiver,
            IntentFilter(NotificationRouter.BROADCAST)
        )
    }

    private val modalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("payload")?.let { json ->
                pendingModal = Gson().fromJson(
                    json,
                    NotificationRouter.DomainNotification::class.java
                )
                maybeShowModal()
            }
        }
    }

    private fun maybeShowModal() {
        val notification = pendingModal ?: return

        if (supportFragmentManager.isStateSaved) {
            Logger.log("MainActivity", "FragmentManager is saving state, deferring modal")
            return
        }

        pendingModal = null
        ModalFragment.newInstance(notification)
            .show(supportFragmentManager, "notification_modal")
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent?.getStringExtra("payload")?.let { json ->
            pendingModal = Gson().fromJson(
                json,
                NotificationRouter.DomainNotification::class.java
            )
            intent.removeExtra("payload")
            maybeShowModal()
        }
    }

    private fun hideKeyboard() {
        val controller = ViewCompat.getWindowInsetsController(window.decorView)
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = currentFocus?.windowToken ?: window.decorView.windowToken
            imm.hideSoftInputFromWindow(token, 0)
        }
        customSegmentInput.clearFocus()
    }


    private fun onAllPermissionsGranted() {
        val tenant = TenantConfigStore.load(this)
        apiStatusTv.text = if (tenant != null) {
            "API Key: ${tenant.apiKey} (${tenant.environment})"
        } else {
            "API Key: Not set"
        }

        locStatusTv.text = "Location Permission: GRANTED"
        locStatusTv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        notifStatusTv.text = "Notification Permission: GRANTED"
        notifStatusTv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

//        apiStatusTv.text = "API Key: ${BubblSdk.getApiKey}"
        BubblSdk.startLocationTracking(this)

        refreshCampaignsWithLocation()
        mapView.getMapAsync(this)
    }

    private fun refreshCampaignsWithLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    BubblSdk.refreshGeofence(location.latitude, location.longitude)
                    Toast.makeText(
                        this,
                        "Refreshing at ${String.format("%.5f", location.latitude)}, ${
                            String.format(
                                "%.5f",
                                location.longitude
                            )
                        }",
                        Toast.LENGTH_SHORT
                    ).show()
                    Logger.log(
                        "MainActivity",
                        "Manual refresh at ${location.latitude}, ${location.longitude}"
                    )
                } else {
                    BubblSdk.forceRefreshCampaigns()
                    Toast.makeText(
                        this,
                        "Location unavailable, forcing refresh",
                        Toast.LENGTH_SHORT
                    ).show()
                    Logger.log("MainActivity", "Force refresh campaigns (no location)")
                }
            }
            .addOnFailureListener { exception ->
                Logger.log("MainActivity", "Failed to get location: ${exception.message}")
                BubblSdk.forceRefreshCampaigns()
                Toast.makeText(this, "Error getting location, forcing refresh", Toast.LENGTH_SHORT)
                    .show()
            }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        enableMyLocationIfPermitted()

        lifecycleScope.launch {
            BubblSdk.geofenceFlow.collectLatest { snap ->
                snap ?: return@collectLatest
                googleMap.clear()
                snap.polygons.forEach { poly ->
                    googleMap.addPolygon(
                        PolygonOptions()
                            .addAll(poly.vertices)
                            .strokeColor(0xFF1976D2.toInt())
                            .fillColor(0x331976D2)
                    )
                }
            }
        }
        centreOnUser()
    }

    override fun onConfigSaved(apiKey: String, env: Environment, changed: Boolean) {
        apiStatusTv.text = "API Key: $apiKey ($env)"

        if (changed) {
            Toast.makeText(
                this,
                "Bubbl tenant changed to $env. Geofences will be refreshed.",
                Toast.LENGTH_SHORT
            ).show()

            // Kick geofence/campaign refresh
            refreshCampaignsWithLocation()
        }
    }

    private fun centreOnUser() {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .lastLocation
                .addOnSuccessListener { loc: Location? ->
                    loc?.let {
                        val ll = LatLng(it.latitude, it.longitude)
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15f))
                    } ?: toast("Position unavailable yet")
                }
        } catch (_: SecurityException) { /* permission already granted */
        }
    }

    /* ---------- MapView lifecycle ---------- */
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        maybeShowModal()

        if (!BubblSdk.hasCampaigns()) {
            Logger.log("MainActivity", "No campaigns on resume, refreshing")
            BubblSdk.forceRefreshCampaigns()
        }
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(modalReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        mapView.onSaveInstanceState(out)

    }
}
