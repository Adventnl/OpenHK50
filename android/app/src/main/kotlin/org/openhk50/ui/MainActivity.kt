package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.TextClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import org.openhk50.BuildConfig
import org.openhk50.OpenHkApp
import org.openhk50.band.BandAction
import org.openhk50.band.BandClient
import org.openhk50.band.BandState
import org.openhk50.band.CallRelayService
import org.openhk50.band.Connection
import org.openhk50.data.HealthStore
import org.openhk50.data.Metric
import org.openhk50.data.DAY_BUCKET_HOURS
import org.openhk50.data.DailyEnergy
import org.openhk50.data.HistoryPeriod
import org.openhk50.data.SleepNight
import org.openhk50.data.SleepStage
import org.openhk50.data.Workout
import org.openhk50.data.WorkoutEnergyProfile
import org.openhk50.data.WorkoutLog
import org.openhk50.data.WorkoutType
import org.openhk50.data.combined
import org.openhk50.data.dayBoundaries
import org.openhk50.sync.BackupJob
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private enum class Tab(val title: String, val icon: Glyph, val inNavigation: Boolean = true) {
        TODAY("Today", Glyph.TODAY), ACTIVITY("Workouts", Glyph.WALK), HEALTH("Health", Glyph.PULSE),
        BAND("Band", Glyph.BAND), HISTORY("History", Glyph.CALENDAR, false),
        DAILY_ACTIVITY("Daily activity", Glyph.TODAY, false),
        WORKOUT_TYPES("Choose workout", Glyph.WALK, false), BACKUP("Backup", Glyph.BACKUP, false),
        CAMERA("Camera remote", Glyph.CAMERA, false), CLOCK("Phone clock", Glyph.CLOCK, false),
        MEASURE("Measure", Glyph.PULSE, false), VIBRATION("Phone vibration", Glyph.PHONE, false),
        PRIVACY("Privacy", Glyph.CHECK, false)
    }
    private enum class PermissionAction { SCAN, RECONNECT, CALL_ALERTS }
    private enum class Goal(val title: String, val unit: String, val target: Int, val max: Int, val metric: Metric?) {
        CALORIES("Band calories", "kcal", 300, 10_000, Metric.CALORIES),
        WORKOUT("Workout time", "min", 30, 1_440, null),
        STEPS("Steps", "steps", 8_000, 100_000, Metric.STEPS)
    }

    private val app get() = application as OpenHkApp
    private val band get() = app.band
    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var navigation: GlassNavigation
    private val navCells = mutableMapOf<Tab, NavCell>()
    private var scroll: PageViewport? = null
    private var scrollTab: Tab? = null
    private val tabPositions = mutableMapOf<Tab, Int>()
    private var deferredRefresh: PageViewport? = null
    private var renderBandAlerts: (() -> Unit)? = null
    private var shownBandStorageIssue: String? = null
    private var shownBandState: BandState? = null
    private var callAlertTarget: Boolean? = null

    private data class NavCell(val view: LinearLayout, val icon: Icon, val label: TextView)
    private var todayDial: ActivityDial? = null
    private var dailyDial: ActivityDial? = null
    private val revealedCharts = mutableSetOf<String>()
    private var tab = Tab.TODAY
    private var detailParent = Tab.TODAY
    private var historyMetric: Metric? = Metric.HEART
    private var historyDate = LocalDate.now()
    private var historyPeriod = HistoryPeriod.DAY
    private var currentDay: HealthStore.Day? = null
    private var activityDate: LocalDate? = null
    private var activityMinutes: List<Long>? = null
    private var updateEnergy: (() -> Unit)? = null
    private var trend: HealthStore.Trend? = null
    private var summary: SummaryData? = null
    private var workouts: WorkoutLog? = null
    private var workoutBusy = false
    private var workoutPage = 0
    private var pendingWorkoutPage: Int? = null
    private var workoutTimer: TextView? = null
    private var workoutCalories: TextView? = null
    private var popup: Dialog? = null
    private var measureMetric = Metric.HEART
    private var pendingMeasurement = false
    private var renderMeasurement: ((BandState) -> Unit)? = null
    private val workoutEnergyProfile = BuildConfig.WORKOUT_WEIGHT_KG?.let { weight ->
        BuildConfig.WORKOUT_BODY_FAT_PERCENT?.let { bodyFat -> WorkoutEnergyProfile(weight, bodyFat) }
    }
    private var camera: BandCamera? = null
    private var cameraPermissionPending = false
    private var resumed = false
    private val tickWorkout = object : Runnable {
        override fun run() {
            val workout = workouts?.active ?: return
            updateWorkoutTimer(workout)
            app.main.postDelayed(this, 1_000)
        }
    }
    private val checkDay = object : Runnable {
        override fun run() {
            if (tab in DAILY_TABS && lastShownDate != LocalDate.now(band.zone)) {
                currentDay = null
                activityMinutes = null
                dailyDial = null
                summary = null
                drawScreen(keepScroll = true)
                loadData()
            }
            updateEnergy?.invoke()
            app.main.postDelayed(this, 30_000)
        }
    }
    private var pendingCount = 0L
    private var queryGeneration = 0
    private val cachedData = mutableMapOf<Tab, Pair<DataQuery, DataSnapshot>>()
    private var shownWorkoutBusy = false
    private var shownStorageIssue: String? = null
    private var readIssue: String? = null
    private var backupMessage: String? = null
    private var backupRequestedAt = 0L
    private var permissionAction: PermissionAction? = null
    private var backCallback: OnBackInvokedCallback? = null
    private var expanded = false
    private var keyboardVisible = false
    private var active = false
    private var lastDevice: String? = null
    private var lastShownDate: LocalDate? = null
    private val displayPrefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = permissionAction
        permissionAction = null
        if (action != null) {
            if (BandClient.hasPermissions(this)) startBluetoothAction(action)
            else {
                if (action == PermissionAction.CALL_ALERTS) resetCallAlertTarget()
                permissionSettings("Bluetooth access", "Allow Bluetooth access to find and connect to your band.")
            }
        }
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = permissionAction
        permissionAction = null
        if (result.resultCode == RESULT_OK && action != null && BandClient.hasPermissions(this)) {
            startBluetoothAction(action)
        } else if (action == PermissionAction.CALL_ALERTS) resetCallAlertTarget()
    }
    private val callPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) startCallAlerts()
        else {
            resetCallAlertTarget()
            permissionSettings("Phone access", "Allow phone access to relay incoming calls to the band.")
        }
    }
    private val vibrationCallPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.phoneVibration.setCalls(true)
        else permissionSettings("Phone access", "Allow phone access to vibrate while calls ring.")
        if (tab == Tab.VIBRATION) drawScreen(keepScroll = true)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionPending = false
        if (!granted) permissionSettings("Camera access", "Allow access to the phone camera to take photos.")
        else if (tab == Tab.BAND && phoneCameraAvailable()) go(Tab.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        palette = Palette.of(night)
        expanded = resources.configuration.screenWidthDp >= 680
        tab = savedInstanceState?.getString("tab")?.let(Tab::valueOf) ?: Tab.TODAY
        detailParent = savedInstanceState?.getString("detailParent")?.let(Tab::valueOf) ?: Tab.TODAY
        if (tab == Tab.CAMERA && (!phoneCameraAvailable() ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            tab = Tab.BAND
        }
        historyDate = savedInstanceState?.getString("day")?.let(LocalDate::parse) ?: LocalDate.now(band.zone)
        activityDate = savedInstanceState?.getString("activityDate")?.let(LocalDate::parse)
        historyPeriod = savedInstanceState?.getString("period")?.let(HistoryPeriod::valueOf) ?: HistoryPeriod.DAY
        workoutPage = savedInstanceState?.getInt("workoutPage") ?: 0
        measureMetric = savedInstanceState?.getString("measureMetric")?.let(Metric::valueOf) ?: Metric.HEART
        historyMetric = when (val metric = savedInstanceState?.getString("metric")) {
            null -> Metric.HEART
            "SLEEP" -> null
            else -> Metric.valueOf(metric)
        }
        permissionAction = savedInstanceState?.getString("permission")?.let(PermissionAction::valueOf)
        savedInstanceState?.getBundle("tabPositions")?.let { positions ->
            for (item in Tab.entries) if (positions.containsKey(item.name)) {
                tabPositions[item] = positions.getInt(item.name)
            }
        }
        buildShell()
        setupWindow()
        drawScreen()
    }

    override fun onStart() {
        super.onStart()
        active = true
        app.changed = {
            if (callAlertTarget == app.callAlertsRunning || app.callAlertsIssue != null) callAlertTarget = null
            when (tab) {
                Tab.BAND -> {
                    renderBandAlerts?.invoke()
                    if (shownBandStorageIssue != app.storageIssue) refreshScreen()
                }
                Tab.MEASURE -> renderMeasurement?.invoke(band.state)
                else -> loadData()
            }
        }
        band.changed = {
            val device = band.state.device?.address
            if (lastDevice != device) {
                lastDevice = device
                currentDay = null
                activityMinutes = null
                dailyDial = null
                trend = null
                summary = null
                loadData()
            }
            if (tab in DAILY_TABS && lastShownDate != LocalDate.now(band.zone)) {
                currentDay = null
                activityMinutes = null
                summary = null
                loadData()
            }
            if (tab == Tab.CAMERA) {
                camera?.update()
            } else if (tab == Tab.MEASURE) updateMeasurement()
            else if (tab == Tab.BAND && shownBandState != band.state) refreshScreen()
        }
        band.foreground(true)
        if (tab == Tab.MEASURE) updateMeasurement()
        if (app.settings.state().endpoint != null) BackupJob.schedule(this)
        loadData()
        app.main.postDelayed(checkDay, 30_000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (callAlertTarget == app.callAlertsRunning || app.callAlertsIssue != null) resetCallAlertTarget()
        if (tab == Tab.CAMERA && !phoneCameraAvailable()) go(Tab.BAND)
        else camera?.resume(true)
        if (tab == Tab.VIBRATION || tab == Tab.PRIVACY) drawScreen(keepScroll = true)
        updateEnergy?.invoke()
    }

    override fun onPause() {
        resumed = false
        camera?.resume(false)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        active = false
        app.changed = null
        band.changed = null
        app.main.removeCallbacks(tickWorkout)
        app.main.removeCallbacks(checkDay)
        if (tab == Tab.MEASURE && !isChangingConfigurations) stopHealthMeasurement()
        if (!isChangingConfigurations && permissionAction == null && !cameraPermissionPending) band.foreground(false)
    }

    override fun onDestroy() {
        popup?.dismiss()
        camera?.close()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll()
        outState.putBundle("tabPositions", Bundle().apply {
            for ((item, y) in tabPositions) putInt(item.name, y)
        })
        outState.putString("tab", tab.name)
        outState.putString("detailParent", detailParent.name)
        outState.putString("day", historyDate.toString())
        outState.putString("activityDate", activityDate?.toString())
        outState.putString("period", historyPeriod.name)
        outState.putInt("workoutPage", workoutPage)
        outState.putString("measureMetric", measureMetric.name)
        outState.putString("metric", historyMetric?.name ?: "SLEEP")
        outState.putString("permission", permissionAction?.name)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                if (palette.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (palette.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) or
                (if (!palette.night && Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 27) Color.TRANSPARENT else Color.BLACK
        window.setBackgroundDrawable(shape(palette.background, 0))
    }

    private fun buildShell() {
        root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                view.setPadding(safe.left, safe.top, safe.right, maxOf(keyboard, safe.bottom))
                val showing = keyboard > safe.bottom
                if (showing != keyboardVisible) {
                    keyboardVisible = showing
                    updateNavigationVisibility()
                }
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        content = object : FrameLayout(this) {
            override fun onDescendantInvalidated(child: View, target: View) {
                super.onDescendantInvalidated(child, target)
                if (::navigation.isInitialized && navigation.isVisible) navigation.invalidate()
            }
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        navigation = GlassNavigation(this, palette, content, if (expanded) 25 else 32).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) updateScrollInsets()
            }
        }
        buildNavigationCells()
        val placement = if (expanded) {
            FrameLayout.LayoutParams(dp(96), -2, Gravity.START or Gravity.TOP).apply {
                marginStart = dp(12)
                topMargin = dp(12)
            }
        } else {
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                bottomMargin = dp(9)
            }
        }
        root.addView(navigation, placement)
        drawNavigation()
        updateBack()
    }

    private fun updateScrollInsets() {
        val viewport = scroll ?: return
        val visible = navigation.isVisible
        val side = if (visible && expanded) dp(RAIL_SPACE) else 0
        val bottom = if (visible && !expanded) maxOf(navigation.height, dp(60)) + dp(9) else 0
        viewport.setPaddingRelative(side, 0, 0, bottom)
    }

    private fun updateNavigationVisibility() {
        navigation.visibility = if (keyboardVisible || tab == Tab.CAMERA || tab == Tab.CLOCK) View.GONE else View.VISIBLE
        updateScrollInsets()
    }

    private fun buildNavigationCells() {
        val cells = if (expanded) column() else row().apply { gravity = Gravity.TOP }
        navigation.addView(cells, FrameLayout.LayoutParams(-1, -2))
        for (item in Tab.entries.filter { it.inNavigation }) {
            val icon = Icon(this, item.icon, palette.muted)
            val title = label(item.title, 12, palette.muted, "sans-serif-medium").apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val cell = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(if (expanded) 60 else 54)
                setPadding(dp(2), dp(5), dp(2), dp(5))
                contentDescription = item.title
                isFocusable = true
                foreground = pressable(Color.TRANSPARENT, palette.ink, 24)
                setOnClickListener { go(item) }
                addView(icon, lp(dp(22), dp(22)))
                addView(title, lp(-1, -2).apply { topMargin = dp(4) })
            }
            cells.addView(cell, if (expanded) lp(-1, -2) else lp(0, -1, 1f))
            navCells[item] = NavCell(cell, icon, title)
        }
    }

    private fun drawNavigation(animate: Boolean = false) {
        updateNavigationVisibility()
        val section = if (tab.inNavigation) tab else backDestination()
        for ((item, cell) in navCells) {
            val selected = item == section
            val accent = palette.glyph(item.icon)
            val selectedNow = selected && !cell.view.isSelected
            cell.view.isSelected = selected
            cell.icon.setColor(if (selected) palette.legible(accent) else palette.muted)
            cell.label.setTextColor(if (selected) palette.ink else palette.muted)
            if (selected) navigation.select(cell.view, accent, animate)
            cell.icon.animate().cancel()
            cell.icon.translationY = 0f
            cell.icon.scaleX = 1f
            cell.icon.scaleY = 1f
            if (selectedNow && animate && ValueAnimator.areAnimatorsEnabled()) {
                cell.icon.scaleX = .78f
                cell.icon.scaleY = .78f
                cell.icon.translationY = dp(3).toFloat()
                cell.icon.animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setInterpolator(arrive).setDuration(330).start()
            }
        }
    }

    private fun rememberScroll() {
        val item = scrollTab ?: return
        scroll?.let { tabPositions[item] = it.position }
    }

    private fun go(next: Tab) {
        if (tab == next) return
        val previous = tab
        val returning = next == backDestination()
        rememberScroll()
        revealedCharts.clear()
        deferredRefresh = null
        if (!next.inNavigation && !returning) tabPositions.remove(next)
        if (tab == Tab.MEASURE) stopHealthMeasurement()
        val previousCamera = camera
        camera = null
        if (next == Tab.HISTORY || next == Tab.WORKOUT_TYPES) detailParent = tab
        pendingWorkoutPage = null
        tab = next
        previousCamera?.close()
        queryGeneration++
        backupMessage = null
        val query = screenQuery()
        applyData(cachedData[next]?.takeIf { it.first == query }?.second)
        readIssue = null
        if (next != Tab.TODAY) todayDial = null
        if (next != Tab.DAILY_ACTIVITY) dailyDial = null
        drawNavigation(animate = true)
        updateBack()
        drawScreen()
        val axis = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val direction = when {
            previous.inNavigation && next.inNavigation -> if (next.ordinal > previous.ordinal) 1 else -1
            returning -> -1
            else -> 1
        }
        content.getChildAt(0)?.let {
            animateIn(it, x = dp(if (previous.inNavigation && next.inNavigation) 34 else 46) * direction * axis,
                duration = 375)
        }
        loadData()
    }

    private fun animateIn(view: View, x: Int = 0, y: Int = 0, duration: Long = 300) {
        view.animate().cancel()
        val animate = ValueAnimator.areAnimatorsEnabled()
        view.alpha = if (animate) .84f else 1f
        view.translationX = if (animate) x.toFloat() else 0f
        view.translationY = if (animate) y.toFloat() else 0f
        view.scaleX = if (animate) .975f else 1f
        view.scaleY = view.scaleX
        if (animate) view.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(duration).setInterpolator(arrive).start()
    }

    private fun backDestination() = when (tab) {
        Tab.BACKUP, Tab.CAMERA, Tab.CLOCK, Tab.VIBRATION, Tab.PRIVACY -> Tab.BAND
        Tab.MEASURE -> Tab.HEALTH
        Tab.DAILY_ACTIVITY -> Tab.TODAY
        Tab.HISTORY, Tab.WORKOUT_TYPES -> detailParent
        else -> Tab.TODAY
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
            if (tab != Tab.TODAY) {
                val callback = OnBackInvokedCallback { go(backDestination()) }
                backCallback = callback
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation") // updateBack handles API 33+ with the platform dispatcher.
    @Deprecated("Required on Android 12 and earlier")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33 && tab != Tab.TODAY) go(backDestination()) else super.onBackPressed()
    }

    private data class DataQuery(
        val address: String?, val zone: ZoneId, val today: LocalDate, val date: LocalDate,
        val period: HistoryPeriod, val metric: Metric?, val workoutPage: Int
    )

    private fun screenQuery(): DataQuery {
        val zone = band.zone
        val today = LocalDate.now(zone)
        val history = tab == Tab.HISTORY
        val date = when (tab) {
            Tab.HISTORY -> historyPeriod.endDate(historyDate, today)
            Tab.DAILY_ACTIVITY -> selectedActivityDate(today)
            else -> today
        }
        return DataQuery(band.state.device?.address, zone, today, date,
            if (history) historyPeriod else HistoryPeriod.DAY, historyMetric.takeIf { history },
            if (tab == Tab.ACTIVITY) pendingWorkoutPage ?: workoutPage else 0)
    }

    private fun loadData() {
        if (!active || tab !in DATA_TABS) return
        val generation = ++queryGeneration
        val destination = tab
        val query = screenQuery()
        val (address, zone, today, date, period, metric, page) = query
        val history = destination == Tab.HISTORY
        app.databaseThread.execute {
            val result = runCatching {
                DataSnapshot(
                    day = when {
                        destination == Tab.BACKUP || destination == Tab.ACTIVITY -> null
                        !history -> app.health.day(address, date, zone)
                        period == HistoryPeriod.DAY -> app.health.day(address, date, zone, metric, history = true)
                        else -> null
                    },
                    trend = if (history && period != HistoryPeriod.DAY) {
                        app.health.trend(address, period.dates(date), date, zone, metric)
                    } else null,
                    workouts = when (destination) {
                        Tab.TODAY -> app.health.workouts(3)
                        Tab.ACTIVITY -> app.health.workouts(WORKOUT_PAGE_SIZE, page * WORKOUT_PAGE_SIZE)
                        else -> null
                    },
                    pending = if (destination == Tab.BACKUP) app.health.pendingCount() else 0L,
                    summary = if (destination == Tab.TODAY) loadSummary(address, date, zone) else null,
                    activityMinutes = if (destination == Tab.DAILY_ACTIVITY) {
                        app.health.workoutMinuteBuckets(dayBoundaries(date, zone))
                    } else null
                )
            }
            app.main.post {
                if (!active || generation != queryGeneration) return@post
                if (today != LocalDate.now(zone)) {
                    loadData()
                    return@post
                }
                val changedPage = destination == Tab.ACTIVITY && pendingWorkoutPage != null
                if (changedPage) pendingWorkoutPage = null
                val snapshot = result.getOrNull()
                val cached = cachedData[destination]
                if (snapshot != null) {
                    val visibleChanged = currentDay != snapshot.day || trend != snapshot.trend ||
                        (snapshot.workouts != null && workouts != snapshot.workouts) ||
                        summary != snapshot.summary || activityMinutes != snapshot.activityMinutes
                    val redraw = destination == Tab.BACKUP || changedPage || readIssue != null || visibleChanged ||
                        shownWorkoutBusy != workoutBusy || shownStorageIssue != app.storageIssue ||
                        cached != (query to snapshot)
                    cachedData[destination] = query to snapshot
                    applyData(snapshot)
                    if (destination == Tab.ACTIVITY) workoutPage = page
                    readIssue = null
                    if (redraw) refreshScreen()
                } else {
                    readIssue = "Couldn’t open your records. Reopen the app to try again."
                    refreshScreen()
                }
            }
        }
    }

    private fun applyData(data: DataSnapshot?) {
        currentDay = data?.day
        activityMinutes = data?.activityMinutes
        trend = data?.trend
        if (tab == Tab.TODAY || tab == Tab.ACTIVITY) workouts = data?.workouts
        pendingCount = data?.pending ?: 0
        summary = data?.summary
    }

    private data class DataSnapshot(
        val day: HealthStore.Day?, val trend: HealthStore.Trend?,
        val workouts: WorkoutLog?, val pending: Long, val summary: SummaryData?, val activityMinutes: List<Long>?
    )

    private data class SummaryData(
        val workoutMinutes: Long, val averageSteps: Long?, val averageSleep: Long?
    )

    private fun loadSummary(address: String?, date: LocalDate, zone: ZoneId): SummaryData {
        val days = HistoryPeriod.WEEK.dates(date.minusDays(1))
        val steps = app.health.trend(address, days, days.last(), zone, Metric.STEPS).buckets.map { it.value(true) }
        val sleep = app.health.trend(address, days, days.last(), zone, null).buckets
        val recordedSteps = steps.filterNotNull()
        val averageSteps = recordedSteps.takeIf { it.isNotEmpty() }?.let { (it.sum() + it.size / 2) / it.size }
        return SummaryData(app.health.workoutMinutes(date, zone), averageSteps, sleep.combined().value(false))
    }

    private fun refreshScreen() {
        val viewport = scroll
        if (viewport?.touchInProgress == true) deferredRefresh = viewport
        else drawScreen(keepScroll = true)
    }

    private fun drawScreen(keepScroll: Boolean = false) {
        app.main.removeCallbacks(tickWorkout)
        workoutTimer = null
        workoutCalories = null
        updateEnergy = null
        renderMeasurement = null
        renderBandAlerts = null
        shownWorkoutBusy = workoutBusy
        shownStorageIssue = app.storageIssue
        if (tab == Tab.CAMERA || tab == Tab.CLOCK) {
            scroll = null
            scrollTab = null
            if (tab == Tab.CAMERA) {
                if (camera == null) cameraScreen()
            } else clockScreen()
            return
        }
        val samePage = scrollTab == tab
        val existing = scroll?.takeIf { samePage }
        val previous = if (samePage) {
            if (keepScroll) existing?.position ?: 0 else 0
        } else tabPositions[tab] ?: 0
        val viewport = existing ?: PageViewport(this, onScroll = {
            if (navigation.isVisible) navigation.invalidate()
        }, onGestureEnd = {
            val pending = deferredRefresh
            if (pending != null) {
                deferredRefresh = null
                pending.post { if (active && scroll === pending) refreshScreen() }
            }
        }).also {
            scroll = it
            scrollTab = tab
            content.removeAllViews()
            content.addView(it, FrameLayout.LayoutParams(-1, -1))
            updateScrollInsets()
        }
        val page = column().apply { setPadding(dp(18), dp(16), dp(18), dp(24)) }
        val pageWidth = if (expanded && resources.configuration.screenWidthDp - RAIL_SPACE > 680) dp(680) else -1
        viewport.show(page, pageWidth, previous)
        navigation.invalidate()
        if (!tab.inNavigation && tab != Tab.DAILY_ACTIVITY) pageHeader(page)
        when (tab) {
            Tab.TODAY -> today(page)
            Tab.DAILY_ACTIVITY -> dailyActivity(page)
            Tab.HEALTH -> health(page)
            Tab.ACTIVITY -> activity(page)
            Tab.HISTORY -> history(page)
            Tab.BAND -> bandPage(page)
            Tab.BACKUP -> backupPage(page)
            Tab.WORKOUT_TYPES -> workoutTypes(page)
            Tab.MEASURE -> measurementPage(page)
            Tab.VIBRATION -> vibrationPage(page)
            Tab.PRIVACY -> privacyPage(page)
            Tab.CAMERA, Tab.CLOCK -> Unit
        }
    }

    private fun today(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        val day = currentDay
        showReadIssues(page)
        sectionHeader(page, "Activity", "Goals", 0, open = ::editGoals)
        val activity = panel(radius = 26).apply { background = accentSurface(palette, palette.steps, 26) }
        val values = listOf(day?.value(Metric.CALORIES), summary?.workoutMinutes, day?.value(Metric.STEPS))
        val goals = Goal.entries.mapIndexed { index, goal ->
            GoalProgress(values[index], displayPrefs.getInt(goal.name, goal.target), goalColor(goal))
        }
        val steps = values[Goal.STEPS.ordinal]
        val headline = row().apply {
            minimumHeight = dp(78)
            setPadding(dp(5), dp(3), dp(5), dp(4))
            background = pressable(Color.TRANSPARENT, palette.steps, 16)
            isFocusable = true
            contentDescription = "Today’s steps, ${steps?.let(::number) ?: "no reading"}"
            setOnClickListener { openHistory(Metric.STEPS, today) }
            val words = column().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            block(words, label("Today’s steps", 14, palette.ink, "sans-serif-medium"), 0)
            block(words, readout(steps?.let(::number) ?: "—", size = 51, accent = palette.steps), 1)
            addView(words, lp(0, -2, 1f))
            addView(iconBadge(Glyph.STEPS, palette.steps, glyphSize = 25), lp(dp(45), dp(45)))
            addView(Icon(this@MainActivity, Glyph.RIGHT, palette.legible(palette.steps)),
                lp(dp(16), dp(16)).apply { marginStart = dp(8) })
        }
        block(activity, headline, 0)
        divider(activity)
        val stack = stackTiles()
        val layout = if (stack) column() else row()
        val progress = column()
        for ((index, goal) in Goal.entries.withIndex()) {
            val reading = goals[index]
            val item = column().apply {
                minimumHeight = dp(54)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = pressable(Color.TRANSPARENT, reading.color, 10)
                val value = reading.value?.let(::number) ?: "—"
                val target = number(reading.target.toLong())
                contentDescription = "${goal.title}, ${reading.value?.let { "$value of $target ${goal.unit}" } ?: "no reading, goal $target ${goal.unit}"}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isFocusable = true
                setOnClickListener {
                    val metric = goal.metric
                    if (metric == null) go(Tab.ACTIVITY) else openHistory(metric, today)
                }
                val legend = row()
                legend.addView(View(this@MainActivity).apply { background = shape(reading.color, 4) }, lp(dp(7), dp(7)))
                legend.addView(label(if (goal == Goal.STEPS) "Step goal" else goal.title, 13, palette.ink, "sans-serif-medium").ignoreAccessibility(),
                    lp(0, -2, 1f).apply { marginStart = dp(6) })
                addView(legend, lp(-1, -2))
                val amount = if (goal == Goal.STEPS) target else value
                val suffix = if (goal == Goal.STEPS) "" else " / $target ${goal.unit}"
                addView(label(amount + suffix, 25, palette.legible(reading.color), "sans-serif-medium").apply {
                    letterSpacing = -.025f
                    if (suffix.isNotEmpty()) {
                        val styled = android.text.SpannableString(text)
                        styled.setSpan(android.text.style.RelativeSizeSpan(.57f), amount.length, text.length, 0)
                        styled.setSpan(android.text.style.ForegroundColorSpan(palette.muted), amount.length, text.length, 0)
                        text = styled
                    }
                }.ignoreAccessibility(), lp(-1, -2).apply { topMargin = dp(2) })
            }
            progress.addView(item, lp(-1, -2))
        }
        val chart = ActivityDial(this, palette, goals, todayDial?.visibleProgress)
        todayDial = chart
        if (stack) {
            layout.addView(chart, lp(dp(160), dp(160)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            layout.addView(progress, lp(-1, -2))
        } else {
            val dialSize = when {
                expanded -> 216
                resources.configuration.screenWidthDp >= 405 -> 172
                else -> 150
            }
            layout.addView(chart, lp(dp(dialSize), dp(dialSize)).apply { marginEnd = dp(12) })
            layout.addView(progress, lp(0, -2, 1f))
        }
        block(activity, layout, 7)
        divider(activity)
        activity.addView(summaryRow(Metric.DISTANCE, day?.value(Metric.DISTANCE)) {
            openHistory(Metric.DISTANCE, today)
        }, lp(-1, -2))
        divider(activity)
        activity.addView(summaryRow("Daily activity", Glyph.CALENDAR, null, showMissing = false) {
            activityDate = null
            go(Tab.DAILY_ACTIVITY)
        }, lp(-1, -2))
        block(page, activity, 8)

        block(page, summaryRow("Last night’s sleep", Glyph.MOON, day?.sleep?.let { duration(it.asleepMinutes) }) {
            openHistory(null, today)
        }.apply {
            background = pressable(accentSurface(palette, palette.sleep, 22), palette.sleep, 22)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }, 10)

        heading(page, "Workouts", 17)
        val log = workouts
        val activeWorkout = log?.active
        when {
            activeWorkout != null -> workoutControl(page, 0)
            log?.recent.isNullOrEmpty() -> {
                val empty = panel()
                if (log == null) note(empty, if (readIssue == null) "Loading workouts…" else "Workouts unavailable", 3)
                    else actionRow(empty, "Start workout", Glyph.RUN, !workoutBusy) { go(Tab.WORKOUT_TYPES) }
                block(page, empty, 0)
            }
            else -> workoutRecords(page, log.recent)
        }

        heading(page, "7-day trends", 17)
        val trends = panel()
        trends.addView(summaryRow("Average daily steps", Glyph.STEPS, summary?.averageSteps?.let(::number)) {
            openHistory(Metric.STEPS, today.minusDays(1))
        }, lp(-1, -2))
        divider(trends)
        trends.addView(summaryRow("Average sleep", Glyph.MOON, summary?.averageSleep?.let { duration(it.toInt()) }) {
            openHistory(null, today.minusDays(1), HistoryPeriod.WEEK)
        }, lp(-1, -2))
        block(page, trends, 0)
    }

    private fun selectedActivityDate(today: LocalDate) = activityDate?.coerceAtMost(today) ?: today

    private fun selectActivityDate(date: LocalDate) {
        val today = LocalDate.now(band.zone)
        val selected = date.coerceIn(FIRST_ACTIVITY_DATE, today)
        if (selected == selectedActivityDate(today)) return
        activityDate = selected.takeUnless { it == today }
        revealedCharts.clear()
        currentDay = null
        activityMinutes = null
        dailyDial = null
        readIssue = null
        drawScreen(keepScroll = true)
        scroll?.let { animateIn(it, y = dp(12), duration = 260) }
        loadData()
    }

    private fun dailyActivity(page: LinearLayout) {
        val zone = band.zone
        val today = LocalDate.now(zone)
        lastShownDate = today
        val date = selectedActivityDate(today)
        val day = currentDay
        val minutes = activityMinutes
        val bandCalories = day?.value(Metric.CALORIES)
        val basal = basalKcalPerDay()
        fun energy() = DailyEnergy.estimate(date, zone, Instant.now(), basal, bandCalories)

        val canShare = day != null && (basal != null || bandCalories != null ||
            minutes?.any { it > 0 } == true || day.value(Metric.STEPS) != null || day.value(Metric.DISTANCE) != null)
        val share = if (!canShare) null else iconButton(Glyph.SHARE, "Share day", true) {
            shareActivity(date, energy(), day, minutes?.sum())
        }.apply { background = pressable(Color.TRANSPARENT, palette.ink, 16) }
        pageHeader(page, trailing = share)
        val calendar = row().apply {
            minimumHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            background = pressable(Color.TRANSPARENT, palette.ink, 12)
            contentDescription = "Choose date, ${date.format(FULL_DATE)}"
            isFocusable = true
            setOnClickListener { chooseDate(date, today, select = ::selectActivityDate) }
            addView(Icon(this@MainActivity, Glyph.CALENDAR, palette.muted), lp(dp(18), dp(18)))
            addView(label(date.format(SHORT_DATE), 16, palette.ink, "sans-serif-medium").ignoreAccessibility(),
                lp(-2, -2).apply { marginStart = dp(8) })
            addView(Icon(this@MainActivity, Glyph.DOWN, palette.muted),
                lp(dp(15), dp(15)).apply { marginStart = dp(4) })
        }
        page.addView(calendar, lp(-2, -2).apply { topMargin = dp(6) })
        if (resources.configuration.screenWidthDp >= 390) activityWeek(page, date, today)
        showReadIssues(page)

        val calories = panel(radius = 22)
        val details = column()
        heading(details, "Estimated calorie burn", 0)
        val total = readout("—", size = 42)
        block(details, total, 2)
        val values = listOf(bandCalories, minutes?.sum(), day?.value(Metric.STEPS))
        val goals = Goal.entries.mapIndexed { index, goal ->
            GoalProgress(values[index], displayPrefs.getInt(goal.name, goal.target), goalColor(goal))
        }
        if (stackTiles()) {
            calories.addView(details, lp(-1, -2))
        } else {
            val energyLayout = row()
            val dial = ActivityDial(this, palette, goals, dailyDial?.visibleProgress)
            dailyDial = dial
            energyLayout.addView(details, lp(0, -2, 1f))
            energyLayout.addView(dial, lp(dp(96), dp(96)).apply { marginStart = dp(8) })
            calories.addView(energyLayout, lp(-1, -2))
        }
        val resting = detailLine(calories, "Resting calories", open = ::editBasalEnergy)
        updateEnergy = {
            val estimate = energy()
            total.text = estimate.total?.let { readoutText(number(it), "kcal") } ?: "—"
            resting.text = estimate.resting?.let { displayValue(Metric.CALORIES, it) } ?: "Set BMR"
            (resting.parent as View).contentDescription = if (estimate.resting == null) "Set BMR"
                else "Edit BMR, resting calories ${resting.text}"
        }
        updateEnergy?.invoke()
        when {
            day == null && readIssue == null -> note(calories, "Loading activity…", 4)
            day != null && bandCalories == null -> note(calories,
                if (date == today) "Sync in Band for today’s total." else "No band calories for this day.", 4)
        }
        block(page, calories, 10)

        val breakdown = panel(radius = 22)
        for ((index, goal) in Goal.entries.withIndex()) {
            val metric = goal.metric
            if (index > 0) divider(breakdown)
            val detail = column().apply { setPadding(0, dp(10), 0, dp(10)) }
            val accent = goalColor(goal)
            val value = values[index]
            val target = number(goals[index].target.toLong())
            val header = row()
            header.addView(label(goal.title, 17, palette.ink, "sans-serif-medium").apply {
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, lp(0, -2, 1f).apply { marginEnd = dp(8) })
            val progress = "${value?.let(::number) ?: "—"} / $target"
            val progressText = if (goal == Goal.STEPS) progress else "$progress ${goal.unit}"
            header.addView(label(progressText, 16, palette.ink, "sans-serif-medium").apply {
                gravity = Gravity.END
            }, lp(-2, -2))
            block(detail, header, 0)
            val buckets = if (metric != null) day?.buckets(metric) else minutes
            if (!buckets.isNullOrEmpty() && value != null && (metric != null || value > 0)) {
                plot(detail, dayPoints(buckets), metric, "${goal.title} by time of day", 9, accent = accent)
            }
            if (goal == Goal.STEPS) {
                day?.value(Metric.DISTANCE)?.let { note(detail, "Distance · ${displayValue(Metric.DISTANCE, it)}", 8) }
            }
            block(breakdown, detail, 0)
        }
        block(page, breakdown, 10)
    }

    private fun shareActivity(date: LocalDate, energy: DailyEnergy, day: HealthStore.Day?, minutes: Long?) {
        fun kcal(value: Long) = displayValue(Metric.CALORIES, value)
        val message = buildList {
            add("Daily activity · ${date.format(SHORT_DATE)}")
            energy.total?.let { add("Estimated calorie burn: ${kcal(it)}") }
            energy.resting?.let { add("Estimated resting calories: ${kcal(it)}") }
            energy.band?.let { add("Band calories: ${kcal(it)}") }
            minutes?.let { add("Workout time: ${duration(it.toInt())}") }
            day?.value(Metric.STEPS)?.let { add("Steps: ${number(it)}") }
            day?.value(Metric.DISTANCE)?.let { add("Distance: ${displayValue(Metric.DISTANCE, it)}") }
        }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        startActivity(Intent.createChooser(send, null))
    }

    private fun activityWeek(parent: LinearLayout, date: LocalDate, today: LocalDate) {
        val locale = Locale.getDefault()
        val firstDay = date.with(WeekFields.of(locale).dayOfWeek(), 1)
        val days = row()
        repeat(7) { index ->
            val day = firstDay.plusDays(index.toLong())
            val selected = day == date
            val available = day in FIRST_ACTIVITY_DATE..today
            val color = if (selected) palette.legible(palette.brand) else if (available) palette.ink else palette.muted
            val cell = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                isSelected = selected
                isEnabled = available
                isFocusable = available
                contentDescription = day.format(FULL_DATE)
                if (available) {
                    background = pressable(if (selected) palette.tint else Color.TRANSPARENT, palette.brand, 13)
                    setOnClickListener { selectActivityDate(day) }
                }
                block(this, label(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), 12, color).apply {
                    gravity = Gravity.CENTER
                }.ignoreAccessibility(), 0)
                block(this, label(number(day.dayOfMonth.toLong()), 16, color, "sans-serif-medium").apply {
                    gravity = Gravity.CENTER
                }.ignoreAccessibility(), 2)
            }
            days.addView(cell, lp(0, -2, 1f))
        }
        block(parent, days, 5)
    }

    private fun detailLine(parent: LinearLayout, title: String, open: (() -> Unit)? = null): TextView {
        val line = row()
        val name = label(title, 14, palette.muted)
        line.addView(name, lp(0, -2, 1f))
        val value = label("—", 14, palette.ink, "sans-serif-medium").apply { gravity = Gravity.END }
        line.addView(value, lp(-2, -2).apply { marginStart = dp(8) })
        if (open != null) {
            line.minimumHeight = dp(48)
            line.isFocusable = true
            line.background = pressable(Color.TRANSPARENT, palette.ink, 12)
            line.setOnClickListener { open() }
            name.ignoreAccessibility()
            value.ignoreAccessibility()
            line.addView(Icon(this, Glyph.RIGHT, palette.muted), lp(dp(15), dp(15)).apply { marginStart = dp(7) })
        }
        block(parent, line, 5)
        return value
    }

    private fun basalKcalPerDay() = displayPrefs.getInt(BMR_KCAL, 0).takeIf { it > 0 }?.toDouble()
        ?: workoutEnergyProfile?.basalKcalPerDay

    private fun editBasalEnergy() {
        showPopup("Resting calories", secure = true) { dialog ->
            val name = "Basal metabolic rate (kcal/day)"
            val input = popupInput(name, InputType.TYPE_CLASS_NUMBER).apply {
                val digits = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
                basalKcalPerDay()?.let { setText(digits.format(it.roundToInt())) }
                setSelectAllOnFocus(true)
            }
            block(this, label(name, 14, palette.muted).apply { labelFor = input.id }, 0)
            block(this, input, 5)
            addButton(this, "Estimate from body composition", filled = false, top = 10, action = ::estimateBasalEnergy)
            popupActions(this, dialog, "Save") {
                val kcal = input.text.toString().toIntOrNull()
                if (kcal == null || kcal !in 1..10_000) {
                    input.error = "Enter a daily BMR from 1 to ${number(10_000)} kcal."
                    input.requestFocus()
                } else saveBasalEnergy(dialog, kcal)
            }
            if (displayPrefs.contains(BMR_KCAL)) {
                addButton(this, if (workoutEnergyProfile == null) "Clear BMR" else "Use build profile",
                    filled = false, top = 8) { saveBasalEnergy(dialog, null) }
            }
        }
    }

    private fun estimateBasalEnergy() {
        showPopup("Estimate daily BMR", secure = true) { dialog ->
            val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            fun field(name: String, top: Int) = popupInput(name, decimal).also { input ->
                block(this, label(name, 14, palette.muted).apply { labelFor = input.id }, top)
                block(this, input, 5)
            }
            val weight = field("Weight (kg)", 0)
            val bodyFat = field("Body fat (%)", 10)
            fun decimalValue(input: EditText): Double? {
                val text = input.text.toString().trim()
                val position = java.text.ParsePosition(0)
                val number = NumberFormat.getNumberInstance().apply { isGroupingUsed = false }.parse(text, position)
                return number?.toDouble()?.takeIf { position.index == text.length && it.isFinite() }
                    ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            popupActions(this, dialog, "Save estimate") {
                val kg = decimalValue(weight)
                val percent = decimalValue(bodyFat)
                when {
                    kg == null || kg <= 0 || kg > 400 -> {
                        weight.error = "Enter a weight above 0 and up to 400 kg."
                        weight.requestFocus()
                    }
                    percent == null || percent <= 0 || percent >= 100 -> {
                        bodyFat.error = "Enter a percentage between 0 and 100."
                        bodyFat.requestFocus()
                    }
                    else -> saveBasalEnergy(dialog, WorkoutEnergyProfile(kg, percent).basalKcalPerDay.roundToInt())
                }
            }
        }
    }

    private fun saveBasalEnergy(dialog: Dialog, kcal: Int?) {
        displayPrefs.edit { if (kcal == null) remove(BMR_KCAL) else putInt(BMR_KCAL, kcal) }
        dialog.dismiss()
        drawScreen(keepScroll = true)
    }

    private fun goalColor(goal: Goal) = when (goal) {
        Goal.CALORIES -> palette.calories
        Goal.WORKOUT -> palette.workout
        Goal.STEPS -> palette.steps
    }

    private fun editGoals() {
        val digits = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        showPopup("Daily goals") { dialog ->
            val inputs = Goal.entries.associateWith { goal ->
                val name = if (goal == Goal.STEPS) goal.title else "${goal.title} (${goal.unit})"
                val field = popupInput(name, InputType.TYPE_CLASS_NUMBER).apply {
                    setText(digits.format(displayPrefs.getInt(goal.name, goal.target)))
                    setSelectAllOnFocus(true)
                }
                block(this, label(name, 14, palette.muted).apply { labelFor = field.id }, 10)
                block(this, field, 5)
                field
            }
            popupActions(this, dialog, "Save") {
                val values = mutableMapOf<Goal, Int>()
                for ((goal, input) in inputs) {
                    val value = input.text.toString().toIntOrNull()
                    if (value == null || value !in 1..goal.max) {
                        input.error = "Enter a number from 1 to ${number(goal.max.toLong())}."
                        input.requestFocus()
                        return@popupActions
                    }
                    values[goal] = value
                }
                displayPrefs.edit { values.forEach { (goal, value) -> putInt(goal.name, value) } }
                dialog.dismiss()
                drawScreen(keepScroll = true)
            }
        }
    }

    private fun showReadIssues(page: LinearLayout) {
        app.storageIssue?.let { note(page, it, 12, true) }
        readIssue?.let { note(page, it, 12, true) }
    }

    private fun health(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        block(page, primaryAction("Measure heart rate", Glyph.PULSE, accent = palette.heart) {
            openMeasurement(Metric.HEART)
        }, 0)
        block(page, button("Other measurements", filled = false, action = ::chooseMeasurement), 8)
        heading(page, "Daily averages", 24)
        showReadIssues(page)
        val day = currentDay
        val metrics = panel()
        for (metric in HEALTH_METRICS) {
            if (metrics.childCount > 0) divider(metrics)
            metrics.addView(summaryRow(metric, day?.value(metric)) { openHistory(metric, today) }, lp(-1, -2))
        }
        block(page, metrics, 10)
        block(page, sleepPanel(day?.sleep, today), 14)
    }

    private fun chooseMeasurement() {
        val metrics = HEALTH_METRICS.filter { it != Metric.HEART && it.liveType != null }
        showChoices("Other measurements", metrics.map { Choice(it.title, it.icon) }) {
            openMeasurement(metrics[it])
        }
    }

    private fun openMeasurement(metric: Metric) {
        measureMetric = metric
        pendingMeasurement = band.state.connection == Connection.CONNECTED
        go(Tab.MEASURE)
        updateMeasurement()
    }

    private fun updateMeasurement() {
        if (pendingMeasurement) {
            val state = band.state
            if (state.connection != Connection.CONNECTED) pendingMeasurement = false
            else if (state.action == BandAction.IDLE) {
                pendingMeasurement = false
                band.measure(measureMetric)
            }
        }
        renderMeasurement?.invoke(band.state)
    }

    private fun stopHealthMeasurement() {
        pendingMeasurement = false
        band.stopMeasurement()
    }

    private fun measurementPage(page: LinearLayout) {
        val metric = measureMetric
        val accent = palette.metric(metric)
        val live = panel(radius = 26).apply { background = accentSurface(palette, accent, 26) }
        section(live, metric.icon, metric.title, accent)
        val value = readout("—", size = 58, accent = accent)
        val trace = PulseTrace(this, accent)
        val status = label("", 14, palette.muted).apply {
            minLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        block(live, value, 14)
        block(live, trace, 8, dp(42))
        block(live, status, 10)
        var action: () -> Unit = {}
        val control = button("Start", filled = true, accent = accent) { action() }
        block(live, control, 10)
        block(page, live, 12)
        var lastReading: Int? = null
        renderMeasurement = { state ->
            val connected = state.connection == Connection.CONNECTED
            val selected = state.measurement == metric
            val measuring = selected && state.action == BandAction.MEASURING
            val reading = state.liveValue?.takeIf { selected && !pendingMeasurement }
            if (reading != lastReading) {
                value.text = reading?.let { readoutText(number(it.toLong()), metric.unit) } ?: "—"
                lastReading = reading
            }
            trace.setMeasuring(measuring && !state.measurementStopping)
            val message = when {
                !connected -> state.message ?: "Connect your band to measure."
                pendingMeasurement -> "Waiting for the band…"
                measuring && state.measurementStopping -> "Finishing…"
                measuring -> "Keep your wrist still."
                selected && state.measurementMessage != null -> state.measurementMessage
                reading != null && app.storageIssue != null -> app.storageIssue
                reading != null -> "Spot check"
                else -> ""
            }
            if (status.text != message) status.text = message
            val title = when {
                !connected -> "Open Band"
                measuring || pendingMeasurement -> "Stop"
                reading != null || (selected && state.measurementMessage != null) -> "Measure again"
                else -> "Start"
            }
            if (control.text != title) control.text = title
            control.isEnabled = !connected || pendingMeasurement || (measuring && !state.measurementStopping) ||
                state.action == BandAction.IDLE
            action = {
                when {
                    !connected -> {
                        tabPositions.remove(Tab.BAND)
                        go(Tab.BAND)
                    }
                    measuring || pendingMeasurement -> {
                        stopHealthMeasurement()
                        updateMeasurement()
                    }
                    else -> {
                        pendingMeasurement = true
                        updateMeasurement()
                    }
                }
            }
        }
        renderMeasurement?.invoke(band.state)
    }

    private fun activity(page: LinearLayout) {
        workoutControl(page, 0)
        showReadIssues(page)
        val log = workouts
        heading(page, "Recent sessions", 22)
        if (log?.recent.isNullOrEmpty()) {
            val empty = panel()
            note(empty, if (log == null && readIssue == null) "Loading workouts…" else "No workouts recorded", 2)
            block(page, empty, 9)
        } else {
            workoutRecords(page, log.recent, 9)
        }
        if (workoutPage > 0 || log?.hasMore == true) {
            val pages = row()
            pages.addView(button("Newer", filled = false, enabled = workoutPage > 0 && pendingWorkoutPage == null) {
                changeWorkoutPage(-1)
            }, lp(0, -2, 1f))
            pages.addView(button("Older", filled = false, enabled = log?.hasMore == true && pendingWorkoutPage == null) {
                changeWorkoutPage(1)
            }, lp(0, -2, 1f).apply { marginStart = dp(8) })
            block(page, pages, 10)
        }
    }

    private fun workoutRecords(page: LinearLayout, workouts: List<Workout>, top: Int = 0) {
        val records = panel()
        for (record in workouts) {
            if (records.childCount > 0) divider(records)
            val line = row().apply { setPadding(0, dp(8), 0, dp(8)); minimumHeight = dp(54) }
            val accent = palette.glyph(record.type.icon)
            line.addView(iconBadge(record.type.icon, accent), lp(dp(36), dp(36)))
            val identity = column()
            identity.addView(label(record.type.title, 15, palette.ink, "sans-serif-medium"))
            val date = Instant.ofEpochMilli(record.start).atZone(band.zone).toLocalDate()
            val heart = record.averageHeartRate?.let { " · $it bpm avg" }.orEmpty()
            note(identity, date.format(MONTH_DAY) + heart, 3)
            workoutCaloriesText(record, 0)?.let { note(identity, it, 3) }
            line.addView(identity, lp(0, -2, 1f).apply { marginStart = dp(10) })
            line.addView(label(sessionDuration(record), 15, palette.legible(accent), "sans-serif-medium"),
                lp(-2, -2).apply { marginStart = dp(8) })
            records.addView(line, lp(-1, -2))
        }
        block(page, records, top)
    }

    private fun workoutTypes(page: LinearLayout) {
        val groups = listOf(
            "Cardio" to listOf(WorkoutType.WALK, WorkoutType.RUN, WorkoutType.CYCLE, WorkoutType.HIKE, WorkoutType.HIIT),
            "Strength and mobility" to listOf(WorkoutType.STRENGTH, WorkoutType.YOGA),
            "Other" to listOf(WorkoutType.OTHER)
        )
        groups.forEach { (name, types) ->
            if (types.size > 1) heading(page, name, 16)
            val choices = panel()
            types.forEach { type ->
                actionRow(choices, type.title, type.icon, enabled = !workoutBusy) {
                    val address = band.state.device?.address
                    val start = System.currentTimeMillis()
                    workoutPage = 0
                    changeWorkout { app.health.startWorkout(type, start, address) }
                    go(backDestination())
                }
            }
            block(page, choices, if (types.size > 1) 9 else 14)
        }
    }

    private fun changeWorkoutPage(offset: Int) {
        if (pendingWorkoutPage != null) return
        pendingWorkoutPage = workoutPage + offset
        drawScreen(keepScroll = true)
        loadData()
    }

    private fun workoutControl(page: LinearLayout, top: Int) {
        val log = workouts
        val workout = log?.active
        if (workout == null) {
            val start = primaryAction("Start workout", Glyph.RUN, enabled = !workoutBusy && log != null,
                accent = palette.workout) {
                go(Tab.WORKOUT_TYPES)
            }
            block(page, start, top)
            return
        }
        val paused = workout.pausedAt != null
        val recorder = panel(radius = 26).apply { background = accentSurface(palette, palette.workout, 26) }
        val header = row()
        header.addView(iconBadge(workout.type.icon, palette.workout), lp(dp(36), dp(36)))
        header.addView(label(workout.type.title, 17, palette.ink, "sans-serif-medium"),
            lp(0, -2, 1f).apply { marginStart = dp(10) })
        header.addView(label(if (paused) "Paused" else "In progress", 13, palette.legible(palette.workout)))
        block(recorder, header, 0)
        val now = System.currentTimeMillis()
        workoutTimer = readout(workoutDuration(workout.seconds(now)), size = 48, accent = palette.workout)
            .also { block(recorder, it, 8) }
        workoutCalories = workoutCaloriesText(workout, now)?.let { text ->
            label(text, 14, palette.muted).also { block(recorder, it, 4) }
        }
        if (active && !paused) app.main.postDelayed(tickWorkout, 1_000)
        val controls = row()
        controls.addView(button(if (paused) "Resume" else "Pause", filled = true, enabled = !workoutBusy, accent = palette.workout) {
            val now = System.currentTimeMillis()
            changeWorkout {
                if (paused) app.health.resumeWorkout(workout.start, now) else app.health.pauseWorkout(workout.start, now)
            }
        }, lp(0, -2, 1f))
        controls.addView(button("End workout", filled = false, enabled = !workoutBusy, accent = palette.workout) {
            workoutPage = 0
            val now = System.currentTimeMillis()
            changeWorkout { app.health.endWorkout(workout.start, now) }
        }, lp(0, -2, 1f).apply { marginStart = dp(8) })
        block(recorder, controls, 10)
        block(page, recorder, top)
    }

    private fun changeWorkout(change: () -> Unit) {
        if (workoutBusy) return
        workoutBusy = true
        drawScreen(keepScroll = true)
        app.write("Couldn’t save the workout. Check phone storage and try again.") {
            try {
                change()
            } finally {
                app.main.post { workoutBusy = false }
            }
        }
    }

    private fun updateWorkoutTimer(workout: Workout) {
        val now = System.currentTimeMillis()
        workoutTimer?.text = workoutDuration(workout.seconds(now))
        workoutCalories?.text = workoutCaloriesText(workout, now)
    }

    private fun workoutCaloriesText(workout: Workout, now: Long): String? =
        workout.estimatedActiveKcal(workoutEnergyProfile, now)?.let { "${number(it)} active kcal est." }

    private fun workoutDuration(elapsed: Long): String {
        return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
    }

    private fun openHistory(metric: Metric?, day: LocalDate, period: HistoryPeriod = HistoryPeriod.DAY) {
        historyMetric = metric
        historyPeriod = period
        if (tab == Tab.HISTORY) selectDate(day)
        else {
            historyDate = day
            go(Tab.HISTORY)
        }
    }

    private fun sessionDuration(workout: Workout): String {
        val seconds = workout.seconds(0)
        return if (seconds < 60) "${number(seconds)} sec" else duration((seconds / 60).toInt())
    }

    private fun summaryRow(metric: Metric, value: Long?, action: () -> Unit) =
        summaryRow(metric.title, metric.icon, value?.let { displayValue(metric, it) }, accent = palette.metric(metric), action = action)

    private fun summaryRow(
        title: String, icon: Glyph, value: String?, showMissing: Boolean = true,
        accent: Int = palette.glyph(icon), action: () -> Unit
    ) = row().apply {
        minimumHeight = dp(55)
        setPadding(0, dp(7), 0, dp(7))
        background = pressable(Color.TRANSPARENT, palette.ink, 11)
        isFocusable = true
        contentDescription = if (value != null || showMissing) "$title, ${value ?: "no reading"}" else title
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { action() }
        addView(iconBadge(icon, accent), lp(dp(32), dp(32)))
        addView(label(title, 16, palette.ink, "sans-serif-medium")
            .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(0, -2, 1f).apply { marginStart = dp(12) })
        if (value != null || showMissing) {
            val reading = label(value ?: "—", 15, if (value == null) palette.muted else palette.legible(accent), "sans-serif-medium").apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(reading, lp(-2, -2).apply { marginStart = dp(8) })
        }
        addView(Icon(this@MainActivity, Glyph.RIGHT, palette.muted), lp(dp(15), dp(15)).apply { marginStart = dp(8) })
    }

    private fun history(page: LinearLayout) {
        val zone = band.zone
        val today = LocalDate.now(zone)
        val period = historyPeriod
        val end = period.endDate(historyDate, today)
        val dates = period.dates(end)
        val metric = historyMetric
        val accent = palette.metric(metric)
        val metricName = metric?.title ?: "Sleep"
        val picker = row().apply {
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pressable(accentSurface(palette, accent, 18), accent, 18)
            contentDescription = "$metricName, choose metric"
            isFocusable = true
            addView(iconBadge(metric?.icon ?: Glyph.MOON, accent), lp(dp(34), dp(34)))
            addView(label(metricName, 18, palette.ink, "sans-serif-medium"),
                lp(0, -2, 1f).apply { marginStart = dp(11) })
            addView(Icon(this@MainActivity, Glyph.DOWN, accent), lp(dp(19), dp(19)))
            setOnClickListener {
                val choices = Metric.entries.map { Choice(it.title, it.icon, it == metric) } +
                    Choice("Sleep", Glyph.MOON, metric == null)
                showChoices("Metric", choices) { index ->
                    val chosen = Metric.entries.getOrNull(index)
                    if (chosen != metric) openHistory(chosen, historyDate,
                        if (chosen == Metric.STEPS) HistoryPeriod.DAY else period)
                }
            }
        }
        block(page, picker, 12)
        val periods = row().apply {
            background = shape(palette.tint, 19)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (choice in HistoryPeriod.entries) {
            val control = button(choice.title, filled = period == choice, accent = accent) {
                if (historyPeriod != choice) {
                    historyPeriod = choice
                    selectDate(historyDate)
                }
            }.apply {
                isSelected = period == choice
                if (!isSelected) background = pressable(Color.TRANSPARENT, accent, 15)
            }
            periods.addView(control, lp(0, -2, 1f))
        }
        block(page, periods, 10)

        val dateRow = row()
        val earlier = currentDay?.earlier ?: trend?.earlier ?: false
        dateRow.addView(iconButton(Glyph.LEFT, "Previous period", earlier) {
            selectDate(dates.first().minusDays(1))
        }, lp(dp(48), dp(48)))
        val dateText = when (period) {
            HistoryPeriod.DAY -> end.format(SHORT_DATE)
            HistoryPeriod.WEEK -> {
                val start = dates.first()
                val startFormat = if (start.year == end.year) MONTH_DAY else SHORT_DATE
                "${start.format(startFormat)} – ${end.format(SHORT_DATE)}"
            }
            HistoryPeriod.YEAR -> "${dates.first().format(MONTH_YEAR)} – ${end.format(MONTH_YEAR)}"
        }
        val date = label(dateText, 14, palette.ink, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "Choose date, $dateText"
            background = pressable(Color.TRANSPARENT, palette.ink, 12)
            isFocusable = true
            setOnClickListener { chooseDate(historyDate, today) }
        }
        dateRow.addView(date, lp(0, -2, 1f))
        dateRow.addView(iconButton(Glyph.RIGHT, "Next period", end < today) {
            selectDate(period.next(end))
        }, lp(dp(48), dp(48)))
        block(page, dateRow, 5)
        showReadIssues(page)

        if (period != HistoryPeriod.DAY) {
            trendPanel(page, metric, period, dates)
            return
        }
        val day = currentDay
        if (metric == null) {
            block(page, sleepPanel(day?.sleep), 8)
            return
        }
        val detail = panel()
        val value = day?.value(metric)
        val spot = day?.spot
        if (value == null && spot == null) {
            note(detail, if (day == null && readIssue == null) "Loading readings…" else "No readings for this day", 4)
        }
        if (value != null) {
            detail.addView(readout(number(value), metric.unit, accent = accent))
            note(detail, if (metric.isTotal) "Daily total" else "Daily average", 3)
            plot(detail, dayPoints(day.buckets(metric)), metric,
                if (metric.isTotal) "3-hour totals" else "3-hour averages", 16, caption = true)
        }
        if (spot != null) {
            note(detail, "Latest spot check · ${displayValue(metric, spot)}", if (value == null) 4 else 14)
        }
        block(page, detail, 8)
    }

    private fun trendPanel(page: LinearLayout, metric: Metric?, period: HistoryPeriod, dates: List<LocalDate>) {
        val panel = panel()
        val series = trend?.buckets
        val total = metric?.isTotal == true
        val value = series?.combined()?.value(total)
        if (value == null) {
            note(panel, if (series == null && readIssue == null) "Loading readings…" else "No readings for this period", 4)
        } else {
            val accent = palette.metric(metric)
            panel.addView(if (metric == null) readout(duration(value.toInt()), accent = accent)
                else readout(number(value), metric.unit, accent = accent))
            note(panel, if (total) "Period total" else "Period average", 3)
            val byMonth = period == HistoryPeriod.YEAR
            val points = dates.mapIndexed { index, date ->
                val axis = when {
                    !byMonth -> number(date.dayOfMonth.toLong())
                    index % 3 == 0 || index == dates.lastIndex -> date.format(MONTH)
                    else -> ""
                }
                ChartPoint(axis, date.format(if (byMonth) MONTH_YEAR else SHORT_DATE), series[index].value(total))
            }
            val caption = (if (byMonth) "Monthly " else "Daily ") + if (total) "totals" else "averages"
            if (byMonth) plot(panel, points, metric, caption, 16, caption = true)
            else plot(panel, points, metric, caption, 16, caption = true) { openHistory(metric, dates[it]) }
        }
        block(page, panel, 8)
    }

    private fun dayPoints(values: List<Long?>) = values.mapIndexed { index, value ->
        val hour = index * DAY_BUCKET_HOURS
        val from = LocalTime.of(hour, 0)
        val until = from.plusHours(DAY_BUCKET_HOURS.toLong())
        ChartPoint(if (index % 2 == 0) from.format(CHART_HOUR) else "", "${TIME.format(from)} to ${TIME.format(until)}", value)
    }

    private fun plot(parent: LinearLayout, points: List<ChartPoint>, metric: Metric?, title: String, top: Int,
                     caption: Boolean = false, accent: Int = palette.metric(metric), openPoint: ((Int) -> Unit)? = null) {
        fun description(point: ChartPoint): String {
            val value = point.value?.let { if (metric == null) duration(it.toInt()) else displayValue(metric, it) }
            return "${point.label}, ${value ?: "no reading"}"
        }
        if (caption) note(parent, title, top)
        val description = if (openPoint == null) "$title. " + points.joinToString(". ", transform = ::description) else null
        val reveal = points.any { it.value != null } && revealedCharts.add(title)
        val chart = BarChart(this, palette, points, description, accent, durationAxis = metric == null, reveal = reveal)
        val frame = FrameLayout(this)
        frame.addView(chart, FrameLayout.LayoutParams(-1, -1))
        if (openPoint != null) {
            val hitTargets = row()
            points.forEachIndexed { index, point ->
                hitTargets.addView(View(this).apply {
                    contentDescription = description(point)
                    isFocusable = true
                    background = pressable(Color.TRANSPARENT, palette.ink, 8)
                    setOnClickListener { openPoint(index) }
                }, lp(0, -1, 1f))
            }
            frame.addView(hitTargets, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = chart.axisWidth() })
        }
        val height = 130 + (resources.configuration.fontScale.coerceAtLeast(1f) * 14).toInt()
        block(parent, frame, if (caption) 8 else top, dp(height))
    }

    private fun sleepPanel(night: SleepNight?, historyLink: LocalDate? = null) = panel(radius = 22).apply {
        background = accentSurface(palette, palette.sleep, 22)
        if (historyLink != null) {
            addView(summaryRow("Sleep", Glyph.MOON, null, showMissing = false) {
                openHistory(null, historyLink)
            }, lp(-1, -2))
        }
        if (night == null) {
            note(this, if (currentDay == null && readIssue == null) "Loading sleep…" else "No sleep recorded", 4)
            return@apply
        }
        addView(readout(duration(night.asleepMinutes), accent = palette.sleep), lp(-1, -2))
        val start = LocalTime.ofSecondOfDay(night.startMinute * 60L)
        note(this, "Sleep began at ${TIME.format(start)}", 5)
        block(this, SleepTimeline(this@MainActivity, palette, night.segments), 18, dp(26))
        for (stage in SleepStage.entries) {
            val minutes = night.segments.filter { it.stage == stage }.sumOf { it.minutes }
            if (minutes == 0) continue
            val line = row().apply { minimumHeight = dp(37) }
            line.addView(View(this@MainActivity).apply { background = shape(palette.sleepColor(stage), 5) }, lp(dp(10), dp(10)))
            line.addView(label(stage.title, 15, palette.ink), lp(0, -2, 1f).apply { marginStart = dp(10) })
            line.addView(label(duration(minutes), 15, palette.muted))
            block(this, line, 0)
        }
    }

    private fun selectDate(day: LocalDate) {
        historyDate = day.coerceAtMost(LocalDate.now(band.zone))
        revealedCharts.clear()
        currentDay = null
        trend = null
        drawScreen(keepScroll = true)
        scroll?.let { animateIn(it, y = dp(12), duration = 260) }
        loadData()
    }

    private fun chooseDate(day: LocalDate, today: LocalDate, openingMonth: YearMonth = YearMonth.from(day),
                           select: (LocalDate) -> Unit = ::selectDate) {
        val latest = YearMonth.from(today)
        val earliest = YearMonth.of(minOf(2000, day.year), 1)
        val locale = Locale.getDefault()
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        val monthFormat = DateTimeFormatter.ofPattern("LLLL uuuu", locale)
        var month = openingMonth
        showPopup("Choose date") { dialog ->
            val calendar = column()
            block(this, calendar, 0)

            fun drawMonth() {
                calendar.removeAllViews()
                val header = row()
                header.addView(iconButton(Glyph.LEFT, "Previous month", month > earliest) {
                    month = month.minusMonths(1)
                    drawMonth()
                }, lp(dp(44), dp(48)))
                header.addView(button(month.format(monthFormat), filled = false) {
                    val years = (latest.year downTo earliest.year).toList()
                    showChoices("Choose year", years.map { Choice(it.toString(), Glyph.CALENDAR, it == month.year) }) {
                        val target = month.withYear(years[it]).coerceAtMost(latest)
                        chooseDate(day, today, target, select)
                    }
                }.apply { contentDescription = "${month.format(monthFormat)}, choose year" }, lp(0, -2, 1f))
                header.addView(iconButton(Glyph.RIGHT, "Next month", month < latest) {
                    month = month.plusMonths(1)
                    drawMonth()
                }, lp(dp(44), dp(48)))
                block(calendar, header, 0)

                val weekdays = row()
                for (offset in 0L..6L) {
                    weekdays.addView(label(firstDay.plus(offset).getDisplayName(TextStyle.NARROW, locale), 13, palette.muted)
                        .apply { gravity = Gravity.CENTER }, lp(0, dp(28), 1f))
                }
                block(calendar, weekdays, 5)
                val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
                val cells = offset + month.lengthOfMonth()
                for (start in 0 until cells step 7) {
                    val week = row()
                    for (cell in start until start + 7) {
                        val dayNumber = cell - offset + 1
                        val date = if (dayNumber in 1..month.lengthOfMonth()) month.atDay(dayNumber) else null
                        val view = if (date == null) View(this@MainActivity) else label(number(dayNumber.toLong()),
                            15, if (date == day) palette.onAccent else if (date > today) palette.muted else palette.ink).apply {
                            gravity = Gravity.CENTER
                            background = pressable(if (date == day) palette.brand else Color.TRANSPARENT, palette.ink, 12)
                            contentDescription = date.format(FULL_DATE)
                            isSelected = date == day
                            isEnabled = date <= today
                            if (isEnabled) {
                                isFocusable = true
                                setOnClickListener {
                                    dialog.dismiss()
                                    select(date)
                                }
                            }
                        }
                        week.addView(view, lp(0, dp(48), 1f))
                    }
                    block(calendar, week, 0)
                }
            }
            drawMonth()
            addButton(this, "Cancel", filled = false, top = 12) { dialog.dismiss() }
        }
    }

    private fun bandPage(page: LinearLayout) {
        val state = band.state
        shownBandState = state
        shownBandStorageIssue = app.storageIssue
        val current = state.connecting ?: state.device
        val connected = state.connection == Connection.CONNECTED
        val idle = state.action == BandAction.IDLE
        val devicePanel = panel(radius = 22)
        val identity = row()
        val words = column()
        words.addView(label(current?.name ?: "Connect a band", 27, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.035f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        if (current != null) {
            val color = when (state.connection) {
                Connection.CONNECTED -> palette.brand
                Connection.CONNECTING -> palette.oxygen
                Connection.DISCONNECTED -> palette.muted
            }
            block(words, label(connectionText(state), 14, palette.legible(color), "sans-serif-medium"), 4)
        }
        identity.addView(words, lp(0, -2, 1f).apply { marginEnd = dp(10) })
        identity.addView(iconBadge(Glyph.BAND, palette.brand, glyphSize = 25),
            lp(dp(48), dp(48)))
        devicePanel.addView(identity, lp(-1, -2))
        state.battery?.let {
            val batteryLine = row()
            batteryLine.addView(Icon(this, Glyph.BATTERY, palette.brand), lp(dp(22), dp(22)))
            batteryLine.addView(label(if (state.charging) "$it% · Charging" else "$it% battery", 16, palette.ink, "sans-serif-medium"),
                lp(-2, -2).apply { marginStart = dp(8) })
            block(devicePanel, batteryLine, 14)
        }
        state.lastSync?.takeIf { idle }?.let { note(devicePanel, "Last sync ${dateTime(it, band.zone)}", 9) }
        if (state.action == BandAction.IMPORTING && state.days > 1) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = state.days
                this.progress = state.progress
                progressTintList = ColorStateList.valueOf(palette.brand)
                progressBackgroundTintList = ColorStateList.valueOf(palette.border)
                contentDescription = "Syncing day ${state.progress} of ${state.days}"
            }
            block(devicePanel, progress, 10, dp(7))
        }
        state.message?.let { note(devicePanel, it, 12) }
        when (state.connection) {
            Connection.DISCONNECTED -> {
                if (state.device == null) {
                    addButton(devicePanel, if (state.scanning) "Stop scan" else "Scan for bands", top = 16) {
                        if (state.scanning) band.stopScan() else bluetooth(PermissionAction.SCAN)
                    }
                } else {
                    addButton(devicePanel, "Reconnect", enabled = !state.scanning, top = 16) {
                        bluetooth(PermissionAction.RECONNECT)
                    }
                }
            }
            Connection.CONNECTING -> addButton(devicePanel, "Cancel", filled = false, top = 16) { band.disconnect() }
            Connection.CONNECTED -> addButton(devicePanel, "Sync now", enabled = idle, top = 16) { band.importHistory(false) }
        }
        block(page, devicePanel, 0)
        app.storageIssue?.let { note(page, it, 12, true) }
        if (state.connection == Connection.DISCONNECTED) nearbyBands(page, state)

        heading(page, "Tools", 24)
        val tools = panel()
        actionRow(tools, "Phone clock", Glyph.CLOCK, true) { go(Tab.CLOCK) }
        if (phoneCameraAvailable()) actionRow(tools, "Camera remote", Glyph.CAMERA, true, action = ::openCamera)
        if (connected) actionRow(tools, "Find band", Glyph.SCAN, idle) { band.findBand() }
        block(page, tools, 9)

        heading(page, "Alerts", 24)
        val alerts = panel()
        actionRow(alerts, "Phone vibration", Glyph.PHONE, true) { go(Tab.VIBRATION) }
        if (state.device != null || app.callAlertsRunning) {
            divider(alerts)
            val toggle = preferenceSwitch(alerts, "Band call alerts", app.callAlertsRunning) {
                toggleCallAlerts()
            }
            if (connected) actionRow(alerts, "Test band call alert", Glyph.PHONE, idle) { band.testCallAlert() }
            val issue = label("", 14, palette.warning).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            block(alerts, issue, 7)
            renderBandAlerts = {
                toggle.isChecked = callAlertTarget ?: app.callAlertsRunning
                toggle.isEnabled = callAlertTarget == null
                val message = app.callAlertsIssue.orEmpty()
                if (issue.text != message) issue.text = message
                issue.visibility = if (app.callAlertsIssue == null) View.GONE else View.VISIBLE
            }
            renderBandAlerts?.invoke()
        }
        block(page, alerts, 9)

        heading(page, "Settings", 25)
        val actions = panel()
        preferenceSwitch(actions, "Auto-sync", band.autoSync, change = band::setAutoSync)
        if (connected) {
            actionRow(actions, "Sync past 7 days", Glyph.CALENDAR, idle) { band.importHistory(true) }
            actionRow(actions, "Set band clock", Glyph.CLOCK, idle) { band.setClock() }
            val logging = state.automaticHeartLogging
            val loggingMode = when (logging) {
                true -> "Every 5 minutes"
                false -> "On demand"
                null -> if (idle) "Couldn’t confirm. Tap to retry." else null
            }
            actionRow(actions, "Heart-rate logging", Glyph.PULSE, idle, loggingMode) {
                showChoices("Heart-rate logging", listOf(
                    Choice("On demand", Glyph.PULSE, logging == false),
                    Choice("Every 5 minutes", Glyph.CLOCK, logging == true)
                )) { band.setLogging(it == 1) }
            }
            if (state.detailSyncAvailable) {
                val title = when {
                    state.detailUsernameSet -> "QWatch username"
                    state.detailSyncEnabled -> "Set QWatch username"
                    else -> "Set up sleep and oxygen"
                }
                actionRow(actions, title, Glyph.MOON, idle) { detailsDialog(state.detailUsernameSet) }
            }
            actionRow(actions, "Disconnect", Glyph.BLUETOOTH, true) { band.disconnect() }
        }
        block(page, actions, 9)
        heading(page, "Data", 24)
        val data = panel()
        actionRow(data, "Privacy", Glyph.CHECK, true) { go(Tab.PRIVACY) }
        actionRow(data, "Backup", Glyph.BACKUP, true) { go(Tab.BACKUP) }
        block(page, data, 9)
        if (state.connection == Connection.DISCONNECTED && state.device != null && !state.scanning) {
            addButton(page, "Forget band", filled = false, top = 18) {
                showPopup("Forget this band?") { dialog ->
                    note(this, "Readings stay on this phone.", 2)
                    popupActions(this, dialog, "Forget") {
                        dialog.dismiss()
                        stopService(Intent(this@MainActivity, CallRelayService::class.java))
                        band.forget()
                    }
                }
            }
        }
    }

    private fun nearbyBands(page: LinearLayout, state: BandState) {
        val nearby = panel()
        if (state.device != null) actionRow(nearby, if (state.scanning) "Stop scan" else "Scan for bands",
            Glyph.SCAN, true) { if (state.scanning) band.stopScan() else bluetooth(PermissionAction.SCAN) }
        if (state.scanning) note(nearby, "Scanning…", if (nearby.childCount == 0) 3 else 9)
        else if (state.searched && state.nearby.isEmpty() && state.message == null) {
            note(nearby, "No bands found. Disconnect the band from other apps and scan again.", 3)
        }
        for (candidate in state.nearby) {
            actionRow(nearby, candidate.name, Glyph.BAND, true, candidate.address) { band.connect(candidate) }
        }
        if (nearby.childCount > 0) {
            heading(page, "Nearby bands", 24)
            block(page, nearby, 9)
        }
    }

    private fun privacyPage(page: LinearLayout) {
        val permissions = panel()
        val hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) "Nearby devices" else "Location for scans"
        fun status(allowed: Boolean) = if (allowed) "Allowed" else "Not allowed"
        detailLine(permissions, bluetooth).text = status(BandClient.hasPermissions(this))
        if (phoneCameraAvailable()) {
            detailLine(permissions, "Camera").text =
                status(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        if (hasTelephony) detailLine(permissions, "Phone").text = status(app.phoneVibration.phoneAccess)
        actionRow(permissions, "Manage app permissions", Glyph.CHECK, true, action = ::openAppSettings)
        block(page, permissions, 12)

        val notifications = panel()
        actionRow(notifications, "Notification access", Glyph.PHONE, true,
            status(app.phoneVibration.notificationAccess), action = ::openNotificationAccess)
        block(page, notifications, 10)
    }

    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun vibrationPage(page: LinearLayout) {
        val vibration = app.phoneVibration
        if (!vibration.available) {
            note(page, "This phone has no vibrator.", 12)
            return
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            val calls = panel()
            preferenceSwitch(calls, "Incoming calls", vibration.callsEnabled) { enabled ->
                if (enabled && !vibration.phoneAccess) {
                    vibrationCallPermission.launch(Manifest.permission.READ_PHONE_STATE)
                } else vibration.setCalls(enabled)
            }
            block(page, calls, 12)
        }
        heading(page, "Apps", 22)
        val apps = panel()
        if (!vibration.notificationAccess) {
            note(apps, "Allow notification access to choose apps.", 5)
            addButton(apps, "Allow notification access", top = 10, action = ::openNotificationAccess)
        } else {
            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = packageManager.queryIntentActivities(launchable, 0)
                .asSequence().map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                .filter { it.packageName != packageName }.map {
                    it to it.loadLabel(packageManager).toString()
                }.toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
            if (installed.isEmpty()) {
                note(apps, "No apps to show.", 5)
            } else {
                fun showApps(query: String) {
                    apps.removeAllViews()
                    val selected = vibration.apps
                    for ((info, name) in installed) {
                        if (!name.contains(query, ignoreCase = true)) continue
                        if (apps.isNotEmpty()) divider(apps)
                        preferenceSwitch(apps, name, info.packageName in selected, info.loadIcon(packageManager)) {
                            vibration.setApp(info.packageName, it)
                        }
                    }
                    if (apps.childCount == 0) note(apps, "No matching apps", 5)
                }
                val search = popupInput("Search apps", InputType.TYPE_CLASS_TEXT).apply {
                    doAfterTextChanged { showApps(it.toString().trim()) }
                }
                block(page, search, 8)
                showApps("")
            }
        }
        block(page, apps, 9)
    }

    private fun preferenceSwitch(
        parent: LinearLayout, title: String, checked: Boolean, icon: Drawable? = null, change: (Boolean) -> Unit
    ): Switch {
        val control = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(palette.ink)
            minHeight = dp(52)
            switchPadding = dp(12)
            if (icon != null) {
                icon.setBounds(0, 0, dp(27), dp(27))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(11)
            }
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(palette.brand, palette.muted))
            trackTintList = ColorStateList(states, intArrayOf(
                ColorUtils.blendARGB(palette.surface, palette.brand, .48f),
                ColorUtils.blendARGB(palette.surface, palette.muted, .56f)
            ))
            isChecked = checked
            setOnClickListener { change(isChecked) }
        }
        block(parent, control, 0)
        return control
    }

    private fun resetCallAlertTarget() {
        callAlertTarget = null
        renderBandAlerts?.invoke()
    }

    private fun toggleCallAlerts() {
        if (callAlertTarget != null) return
        app.callAlertsIssue = null
        val enable = !app.callAlertsRunning
        callAlertTarget = enable
        renderBandAlerts?.invoke()
        if (enable) bluetooth(PermissionAction.CALL_ALERTS)
        else if (!stopService(Intent(this, CallRelayService::class.java))) resetCallAlertTarget()
    }

    private fun requestCallAlertPermissions() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) startCallAlerts()
        else callPermissions.launch(permissions.toTypedArray())
    }

    private fun startCallAlerts() {
        try {
            startForegroundService(Intent(this, CallRelayService::class.java))
        } catch (_: IllegalStateException) {
            app.callAlertsIssue = "Couldn’t start call alerts. Try again."
            resetCallAlertTarget()
        } catch (_: SecurityException) {
            resetCallAlertTarget()
            permissionSettings("Phone access", "Allow phone and Bluetooth access, then turn on call alerts.")
        }
    }

    private fun phoneCameraAvailable() = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun openCamera() {
        if (!phoneCameraAvailable()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) go(Tab.CAMERA)
        else {
            cameraPermissionPending = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))

    private fun permissionSettings(title: String, message: String) {
        showPopup(title) { dialog ->
            note(this, message, 2)
            popupActions(this, dialog, "Open settings") {
                dialog.dismiss()
                openAppSettings()
            }
        }
    }

    private fun cameraScreen() {
        val screen = column().apply { setPadding(dp(18), dp(10), dp(18), dp(12)) }
        pageHeader(screen)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            background = shape(Color.BLACK, 15)
            clipToOutline = true
        }
        val status = label("", 14, palette.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val take = button("Take photo", filled = true, accent = palette.calories) {}
        val retry = button("Retry band shutter", filled = false, accent = palette.calories) {}
        val settings = row()
        val timer = button("Timer off", filled = false, accent = palette.calories) {}
        val lens = button("Switch camera", filled = false, accent = palette.calories) {}
        settings.addView(timer, lp(0, -2, 1f))
        settings.addView(lens, lp(0, -2, 1f).apply { marginStart = dp(8) })
        val controls = if (expanded) column() else screen
        if (expanded) {
            val body = row().apply { gravity = Gravity.CENTER_VERTICAL }
            body.addView(preview, lp(0, -1, 1f))
            body.addView(ScrollView(this).apply { addView(controls) }, lp(dp(290), -2).apply { marginStart = dp(16) })
            screen.addView(body, lp(-1, 0, 1f).apply { topMargin = dp(8) })
        } else {
            screen.addView(preview, lp(-1, 0, 1f).apply { topMargin = dp(8) })
        }
        block(controls, status, 10)
        block(controls, retry, 4)
        block(controls, settings, 8)
        block(controls, take, 8)
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(-1, -1))
        camera = BandCamera(this, band, preview, status, take, retry, timer, lens).also { it.resume(resumed) }
    }

    private fun clockScreen(animateFace: Boolean = false) {
        val digital = displayPrefs.getBoolean("digitalClock", true)
        val screen = column().apply { setPadding(dp(18), dp(10), dp(18), dp(20)) }
        pageHeader(screen)
        val face = panel(radius = 26).apply {
            background = accentSurface(palette, palette.sleep, 26)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(26), dp(12), dp(26))
        }
        val time = TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = if (digital) 56f else 20f
            if (digital) setAutoSizeTextTypeUniformWithConfiguration(20, 56, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "'tnum'"
            letterSpacing = -.035f
            gravity = Gravity.CENTER
            setTextColor(palette.legible(palette.sleep))
        }
        if (!digital) face.addView(AnalogFace(this, palette), lp(dp(200), dp(200)))
        block(face, time, if (digital) 0 else 12)
        face.addView(TextClock(this).apply {
            format12Hour = "EEE, MMM d"
            format24Hour = format12Hour
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }, lp(-1, -2))
        block(screen, face, 12)
        val styles = row()
        for ((name, choice) in listOf("Digital" to true, "Analog" to false)) {
            val selected = digital == choice
            styles.addView(button(name, filled = selected, accent = palette.sleep) {
                if (!selected) {
                    displayPrefs.edit { putBoolean("digitalClock", choice) }
                    clockScreen(animateFace = true)
                }
            }.apply { isSelected = selected }, lp(0, -2, 1f).apply { if (!choice) marginStart = dp(8) })
        }
        block(screen, styles, 9)
        val viewport = ScrollView(this).apply { addView(screen); isFillViewport = true }
        content.removeAllViews()
        content.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        if (animateFace) animateIn(face, y = dp(6), duration = 200)
    }

    private fun detailsDialog(configured: Boolean) {
        showPopup("Sleep and oxygen sync") { dialog ->
            val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            val username = popupInput("QWatch username or email", type).apply { isSaveEnabled = false }
            block(this, username, 2)
            note(this, "Use the QWatch username linked to this band.", 8)
            popupActions(this, dialog, "Save & sync") {
                if (band.setDetailUsername(username.text.toString())) dialog.dismiss()
                else username.error = if (band.state.action == BandAction.IDLE && band.state.detailSyncAvailable)
                    "Enter a valid QWatch username." else "Reconnect to the band and try again."
            }
            if (configured) addButton(this, "Remove username", filled = false, top = 8) {
                dialog.dismiss()
                band.setDetailUsername(null)
            }
        }
    }

    private fun actionRow(parent: LinearLayout, title: String, glyph: Glyph, enabled: Boolean,
                          detail: String? = null, action: () -> Unit) {
        if (parent.childCount > 0) divider(parent)
        val accent = palette.glyph(glyph)
        val line = row().apply {
            minimumHeight = dp(52)
            setPadding(0, dp(8), 0, dp(8))
            background = pressable(Color.TRANSPARENT, accent, 11)
            isEnabled = enabled
            isFocusable = enabled
            contentDescription = if (detail == null) title else "$title, $detail"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (enabled) setOnClickListener { action() }
            addView(iconBadge(glyph, if (enabled) accent else palette.muted), lp(dp(34), dp(34)))
            val words = column().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            words.addView(label(title, 16, if (enabled) palette.ink else palette.muted, "sans-serif-medium"))
            detail?.let { note(words, it, 3) }
            addView(words, lp(0, -2, 1f).apply { marginStart = dp(11) })
            addView(Icon(this@MainActivity, Glyph.RIGHT, palette.muted), lp(dp(15), dp(15)))
        }
        parent.addView(line, lp(-1, -2))
    }

    private fun backupPage(page: LinearLayout) {
        val backup = app.settings.state()
        val status = panel(radius = 24).apply { background = accentSurface(palette, palette.workout, 24) }
        section(status, Glyph.BACKUP, "Home server", palette.workout)
        if (backup.endpoint == null) {
            note(status, "Connect your server to back up and restore your readings.", 9)
            addButton(status, "Set up backup", top = 14) { backupDialog(null) }
        } else {
            note(status, backup.endpoint, 7)
            val whenText = if (backup.successfulAt == 0L) "No completed backup yet"
                else "Last backup ${dateTime(backup.successfulAt, ZoneId.systemDefault())}"
            note(status, whenText, 10)
            val issue = backup.message ?: backupMessage?.takeIf { backup.successfulAt < backupRequestedAt }
            if (issue != null) note(status, issue, 7, backup.message != null)
            addButton(status, "Sync now", top = 14) {
                backupRequestedAt = System.currentTimeMillis()
                backupMessage = if (BackupJob.schedule(this, immediate = true))
                    "Sync requested." else "Couldn’t schedule sync. Try again."
                drawScreen(keepScroll = true)
            }
            addButton(status, "Edit server", filled = false, top = 8) { backupDialog(backup.endpoint) }
        }
        block(page, status, 18)

        val local = panel()
        section(local, Glyph.CHECK, "On this phone", palette.brand)
        note(local, when {
            readIssue != null -> readIssue!!
            pendingCount == 0L -> "No readings waiting to back up"
            pendingCount == 1L -> "1 reading waiting to back up"
            else -> "${number(pendingCount)} readings waiting to back up"
        }, 7)
        block(page, local, 12)
    }

    private fun backupDialog(endpoint: String?) {
        showPopup("Home server", secure = true) { dialog ->
            val address = popupInput("https://backup.example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                .apply {
                    setText(endpoint.orEmpty())
                    contentDescription = "HTTPS server address"
                }
            val key = popupInput("Backup key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .apply {
                    isSaveEnabled = false
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                }
            block(this, address, 2)
            block(this, key, 8)
            if (endpoint != null) note(this, "Leave the key blank to keep it for this server.", 9)
            val error = label("", 13, palette.warning).apply { visibility = View.GONE }
            block(this, error, 6)
            popupActions(this, dialog, "Save & sync") { save ->
                val host = address.text.toString()
                val token = key.text.toString()
                save.isEnabled = false
                app.databaseThread.execute {
                    val result = runCatching { app.settings.save(host, token) }
                    app.main.post {
                        if (isDestroyed) return@post
                        result.onSuccess {
                            key.text.clear()
                            dialog.dismiss()
                            backupRequestedAt = System.currentTimeMillis()
                            backupMessage = if (BackupJob.schedule(this@MainActivity, immediate = true, replaceManual = true))
                                "Sync requested." else "Couldn’t schedule sync. Try again."
                            loadData()
                        }.onFailure {
                            error.text = if (it is IllegalArgumentException) it.message else "Couldn’t store the backup key. Try again."
                            error.visibility = View.VISIBLE
                            save.isEnabled = true
                        }
                    }
                }
            }
            if (endpoint != null) addButton(this, "Turn off backup", filled = false, top = 8) {
                dialog.dismiss()
                disableBackup()
            }
        }
    }

    private fun disableBackup() {
        app.databaseThread.execute {
            val result = runCatching { app.settings.disable() }
            app.main.post {
                if (result.isSuccess) {
                    BackupJob.cancel(this)
                    backupMessage = null
                } else {
                    backupRequestedAt = System.currentTimeMillis()
                    backupMessage = "Couldn’t turn off backup. Try again."
                }
                loadData()
            }
        }
    }

    private data class Choice(val title: String, val icon: Glyph, val selected: Boolean = false)

    private fun showChoices(title: String, choices: List<Choice>, choose: (Int) -> Unit) {
        showPopup(title) { dialog ->
            choices.forEachIndexed { index, choice ->
                val option = row().apply {
                    minimumHeight = dp(52)
                    setPadding(dp(9), dp(7), dp(11), dp(7))
                    background = pressable(if (choice.selected) palette.tint else Color.TRANSPARENT, palette.brand, 13)
                    isSelected = choice.selected
                    isFocusable = true
                    contentDescription = choice.title
                    addView(iconBadge(choice.icon, palette.glyph(choice.icon)), lp(dp(32), dp(32)))
                    addView(label(choice.title, 16, palette.ink, "sans-serif-medium").ignoreAccessibility(),
                        lp(0, -2, 1f).apply { marginStart = dp(11) })
                    if (choice.selected) addView(Icon(this@MainActivity, Glyph.CHECK, palette.brand),
                        lp(dp(19), dp(19)))
                    setOnClickListener {
                        dialog.dismiss()
                        choose(index)
                    }
                }
                block(this, option, if (index == 0) 0 else 3)
            }
            addButton(this, "Cancel", filled = false, top = 12) { dialog.dismiss() }
        }
    }

    @Suppress("DEPRECATION") // Resize the floating window around the keyboard on older Android versions.
    private fun showPopup(title: String, secure: Boolean = false, fill: LinearLayout.(Dialog) -> Unit) {
        popup?.dismiss()
        val dialog = Dialog(this)
        popup = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = column().apply {
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(22), dp(20), dp(18))
            block(this, label(title, 25, palette.ink, "sans-serif-medium").apply {
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, 0)
            val fields = column()
            fields.fill(dialog)
            block(this, fields, 18)
        }
        dialog.setContentView(ScrollView(this).apply {
            background = glassSurface(palette, 26, palette.brand)
            clipToOutline = true
            isVerticalScrollBarEnabled = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        })
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { if (popup === dialog) popup = null }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (palette.night) .64f else .32f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        body.requestFocus()
        dialog.show()
        dialog.window?.setLayout(dp((resources.configuration.screenWidthDp - 24).coerceIn(1, 430)), -2)
        animateIn(body, y = dp(24), duration = 350)
    }

    private fun popupActions(parent: LinearLayout, dialog: Dialog, title: String, confirm: (Button) -> Unit) {
        val actions = row()
        actions.addView(button("Cancel", filled = false) { dialog.dismiss() }, lp(0, -2, 1f))
        val save = button(title, filled = true) {}
        save.setOnClickListener { confirm(save) }
        actions.addView(save, lp(0, -2, 1f).apply { marginStart = dp(8) })
        block(parent, actions, 15)
    }

    private fun popupInput(name: String, type: Int) = EditText(this).apply {
        id = View.generateViewId()
        inputType = type
        hint = name
        setSingleLine()
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(palette.ink)
        setHintTextColor(palette.muted)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(palette.background, 12, palette.brand))
            addState(intArrayOf(), shape(palette.background, 12, palette.border))
        }
    }

    private fun bluetooth(action: PermissionAction) {
        if (BandClient.hasPermissions(this)) {
            startBluetoothAction(action)
        } else {
            permissionAction = action
            bluetoothPermission.launch(BandClient.permissions())
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBluetoothAction(action: PermissionAction) {
        val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        if (manager?.adapter?.isEnabled == false) {
            permissionAction = action
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        when (action) {
            PermissionAction.SCAN -> band.scan()
            PermissionAction.RECONNECT -> band.reconnect()
            PermissionAction.CALL_ALERTS -> requestCallAlertPermissions()
        }
    }

    private fun connectionText(state: BandState) = when (state.connection) {
        Connection.DISCONNECTED -> "Disconnected"
        Connection.CONNECTING -> "Connecting…"
        Connection.CONNECTED -> when (state.action) {
            BandAction.IMPORTING -> "Syncing…"
            BandAction.MEASURING -> "Measuring…"
            BandAction.SETTING -> "Updating band…"
            BandAction.CAMERA -> "Camera shutter active"
            BandAction.IDLE -> "Connected"
        }
    }

    private fun readout(value: String, unit: String = "", size: Int = 43, accent: Int = palette.ink): TextView =
        label(value, size, palette.legible(accent), "sans-serif-medium").apply {
            letterSpacing = -.038f
            text = readoutText(value, unit)
        }

    private fun readoutText(value: String, unit: String): CharSequence {
        if (unit.isEmpty()) return value
        return android.text.SpannableString(if (unit == "%") "$value%" else "$value $unit").apply {
            setSpan(android.text.style.RelativeSizeSpan(.44f), value.length, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(palette.muted), value.length, length, 0)
        }
    }

    private fun pageHeader(page: LinearLayout, trailing: View? = null) {
        val bar = row()
        val parent = backDestination()
        bar.addView(iconButton(Glyph.LEFT, "Back to ${parent.title}", true) { go(parent) }, lp(dp(48), dp(48)))
        bar.addView(label(tab.title, 29, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.033f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(0, -2, 1f).apply { marginStart = dp(8) })
        trailing?.let { bar.addView(it, lp(dp(48), dp(48)).apply { marginStart = dp(8) }) }
        block(page, bar, 0)
    }

    private fun sectionHeader(page: LinearLayout, title: String, action: String, top: Int, open: () -> Unit) {
        val header = row()
        header.addView(label(title, 29, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.025f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(0, -2, 1f))
        header.addView(button(action, filled = false, action = open).apply {
            background = pressable(Color.TRANSPARENT, palette.brand, 15)
            setPadding(dp(12), dp(6), 0, dp(6))
        }, lp(-2, -2))
        block(page, header, top)
    }

    private fun iconBadge(icon: Glyph, accent: Int, glyphSize: Int = 19) =
        FrameLayout(this).apply {
            background = shape(palette.container(accent), if (glyphSize > 24) 18 else 11)
            addView(Icon(this@MainActivity, icon, palette.legible(accent)),
                FrameLayout.LayoutParams(dp(glyphSize), dp(glyphSize), Gravity.CENTER))
        }

    private fun primaryAction(
        title: String, glyph: Glyph, enabled: Boolean = true, accent: Int = palette.brand, action: () -> Unit
    ) =
        row().apply {
            val fill = if (enabled) accent else palette.tint
            val ink = if (enabled) palette.onAccent else palette.muted
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pressable(accentAction(fill, 18), ink, 18)
            this.isEnabled = enabled
            isFocusable = enabled
            contentDescription = title
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (enabled) setOnClickListener { action() }
            addView(label(title, 21, ink, "sans-serif-medium").ignoreAccessibility(),
                lp(0, -2, 1f))
            addView(Icon(this@MainActivity, glyph, ink), lp(dp(24), dp(24)).apply { marginStart = dp(9) })
        }

    private fun accentAction(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(ColorUtils.blendARGB(color, Color.WHITE, .27f), color, color)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), ColorUtils.blendARGB(color, Color.WHITE, .48f))
        }

    private fun stackTiles() = resources.configuration.let {
        it.screenWidthDp < 360 || (it.screenWidthDp < 560 && it.fontScale > 1.2f)
    }

    private fun TextView.ignoreAccessibility() = apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun heading(page: LinearLayout, text: String, top: Int) =
        block(page, label(text, 23, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.025f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, top)

    private fun divider(parent: LinearLayout) =
        parent.addView(View(this).apply { setBackgroundColor(palette.border) }, lp(-1, dp(1)))

    private fun section(panel: LinearLayout, glyph: Glyph, text: String, accent: Int) {
        val header = row()
        header.addView(iconBadge(glyph, accent), lp(dp(34), dp(34)))
        header.addView(label(text, 18, palette.ink, "sans-serif-medium").apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true },
            lp(0, -2, 1f).apply { marginStart = dp(9) })
        panel.addView(header, lp(-1, -2))
    }

    private fun note(parent: LinearLayout, text: String, top: Int, warning: Boolean = false) =
        block(parent, label(text, 14, if (warning) palette.warning else palette.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }, top)

    private fun panel(radius: Int = 18) = column().apply {
        background = glassSurface(palette, radius)
        setPadding(dp(15), dp(10), dp(15), dp(10))
    }

    private fun iconButton(glyph: Glyph, description: String, enabled: Boolean, action: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            background = pressable(if (enabled) palette.tint else Color.TRANSPARENT, palette.ink, 15)
            isEnabled = enabled
            isFocusable = enabled
            contentDescription = description
            addView(Icon(this@MainActivity, glyph, if (enabled) palette.brand else palette.border),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { action() }
        }
    }

    private fun addButton(parent: LinearLayout, text: String, enabled: Boolean = true,
                          filled: Boolean = true, top: Int, action: () -> Unit) {
        block(parent, button(text, filled, enabled, action = action), top)
    }

    private fun button(
        title: String, filled: Boolean, enabled: Boolean = true, accent: Int = palette.brand, action: () -> Unit
    ) = Button(this).apply {
        text = title
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextSize(14f)
        minimumHeight = dp(48)
        minHeight = dp(48)
        minWidth = 0
        gravity = Gravity.CENTER
        stateListAnimator = null
        val bg = if (filled) accent else palette.container(accent)
        val fg = if (filled) palette.onAccent else palette.legible(accent)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(disabled, shape(palette.background, 13, palette.border))
            val surface = if (filled) accentAction(bg, 13) else glassSurface(palette, 13, accent)
            addState(intArrayOf(), pressable(surface, fg, 13))
        }
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(palette.muted, fg)))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isEnabled = enabled
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Int, color: Int, face: String = "sans-serif") = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(face, Typeface.NORMAL)
        fontFeatureSettings = "'tnum'"
        includeFontPadding = false
    }

    private fun block(parent: LinearLayout, child: View, top: Int, height: Int = -2) =
        parent.addView(child, lp(-1, height).apply { topMargin = dp(top) })

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)
    private fun displayValue(metric: Metric, value: Long) = when (metric.unit) {
        "%" -> "${number(value)}%"
        "" -> number(value)
        else -> "${number(value)} ${metric.unit}"
    }
    private fun number(value: Long) = NumberFormat.getIntegerInstance().format(value)
    private fun duration(minutes: Int) = when {
        minutes < 60 -> "${number(minutes.toLong())} min"
        minutes % 60 == 0 -> "${number((minutes / 60).toLong())} hr"
        else -> "${number((minutes / 60).toLong())} hr ${number((minutes % 60).toLong())} min"
    }
    private fun dateTime(time: Long, zone: ZoneId) = DATE_TIME.format(Instant.ofEpochMilli(time).atZone(zone))

    companion object {
        private val DAILY_TABS = setOf(Tab.TODAY, Tab.HEALTH, Tab.DAILY_ACTIVITY)
        private val DATA_TABS = DAILY_TABS + setOf(Tab.ACTIVITY, Tab.HISTORY, Tab.BACKUP)
        private val HEALTH_METRICS = listOf(Metric.HEART, Metric.OXYGEN, Metric.HRV, Metric.STRESS)
        private val SHORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        private val FULL_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        private val FIRST_ACTIVITY_DATE = LocalDate.of(2000, 1, 1)
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        private val MONTH_DAY get() = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(
            java.util.Locale.getDefault(), "MMMd"
        ))
        private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM uuuu")
        private val MONTH = DateTimeFormatter.ofPattern("MMM")
        private val CHART_HOUR = DateTimeFormatter.ofPattern("HH")
        private const val RAIL_SPACE = 108
        private const val WORKOUT_PAGE_SIZE = 15
        private const val BMR_KCAL = "basalKcalPerDay"
    }
}
