package org.openhk50.ui

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import org.openhk50.OpenHkApp
import org.openhk50.band.BandAction
import org.openhk50.band.BandClient
import org.openhk50.band.BandState
import org.openhk50.band.CallRelayService
import org.openhk50.band.Connection
import org.openhk50.data.HealthStore
import org.openhk50.data.Metric
import org.openhk50.data.DAY_BUCKET_HOURS
import org.openhk50.data.HistoryPeriod
import org.openhk50.data.SleepNight
import org.openhk50.data.SleepStage
import org.openhk50.data.Workout
import org.openhk50.data.WorkoutLog
import org.openhk50.data.WorkoutType
import org.openhk50.data.combined
import org.openhk50.sync.BackupJob
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private enum class Tab(val title: String, val icon: Glyph, val inNavigation: Boolean = true) {
        TODAY("Today", Glyph.TODAY), ACTIVITY("Workouts", Glyph.WALK), HEALTH("Health", Glyph.PULSE),
        BAND("Band", Glyph.BAND), HISTORY("History", Glyph.CALENDAR, false),
        WORKOUT_TYPES("Choose workout", Glyph.WALK, false), BACKUP("Backup", Glyph.BACKUP, false),
        CAMERA("Camera remote", Glyph.CAMERA, false), CLOCK("Phone clock", Glyph.CLOCK, false)
    }
    private enum class PermissionAction { SCAN, RECONNECT }
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
    private lateinit var navigation: LinearLayout
    private var scroll: ScrollView? = null
    private var tab = Tab.TODAY
    private var detailParent = Tab.TODAY
    private var historyMetric: Metric? = Metric.HEART
    private var historyDate = LocalDate.now()
    private var historyPeriod = HistoryPeriod.DAY
    private var currentDay: HealthStore.Day? = null
    private var trend: HealthStore.Trend? = null
    private var summary: SummaryData? = null
    private var workouts: WorkoutLog? = null
    private var workoutBusy = false
    private var workoutPage = 0
    private var workoutTimer: TextView? = null
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
                summary = null
                drawScreen(keepScroll = true)
                loadData()
            }
            app.main.postDelayed(this, 30_000)
        }
    }
    private var pendingCount = 0L
    private var queryGeneration = 0
    private var readIssue: String? = null
    private var backupMessage: String? = null
    private var backupRequestedAt = 0L
    private var permissionAction: PermissionAction? = null
    private var backCallback: OnBackInvokedCallback? = null
    private var expanded = false
    private var active = false
    private var lastDevice: String? = null
    private var lastShownDate: LocalDate? = null
    private val displayPrefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = permissionAction
        permissionAction = null
        if (action != null) {
            if (BandClient.hasPermissions(this)) startBluetoothAction(action)
            else permissionSettings("Bluetooth access", "Allow Bluetooth access to find and connect to your band.")
        }
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = permissionAction
        permissionAction = null
        if (result.resultCode == RESULT_OK && action != null && BandClient.hasPermissions(this)) {
            startBluetoothAction(action)
        }
    }
    private val callPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) startCallAlerts()
        else permissionSettings("Phone access", "Allow phone access to relay incoming calls to the band.")
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
        historyPeriod = savedInstanceState?.getString("period")?.let(HistoryPeriod::valueOf) ?: HistoryPeriod.DAY
        workoutPage = savedInstanceState?.getInt("workoutPage") ?: 0
        historyMetric = when (val metric = savedInstanceState?.getString("metric")) {
            null -> Metric.HEART
            "SLEEP" -> null
            else -> Metric.valueOf(metric)
        }
        permissionAction = savedInstanceState?.getString("permission")?.let(PermissionAction::valueOf)
        buildShell()
        setupWindow()
        drawScreen()
    }

    override fun onStart() {
        super.onStart()
        active = true
        app.changed = {
            if (tab == Tab.BAND) drawScreen(keepScroll = true) else loadData()
        }
        band.changed = {
            val device = band.state.device?.address
            if (lastDevice != device) {
                lastDevice = device
                currentDay = null
                trend = null
                summary = null
                loadData()
            }
            if (tab in DAILY_TABS && lastShownDate != LocalDate.now(band.zone)) {
                currentDay = null
                summary = null
                loadData()
            }
            if (tab == Tab.CAMERA) {
                camera?.update()
            } else if (tab == Tab.BAND) drawScreen(keepScroll = true)
        }
        band.foreground(true)
        if (app.settings.state().endpoint != null) BackupJob.schedule(this)
        loadData()
        app.main.postDelayed(checkDay, 30_000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (tab == Tab.CAMERA && !phoneCameraAvailable()) go(Tab.BAND)
        else camera?.resume(true)
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
        if (!isChangingConfigurations && permissionAction == null && !cameraPermissionPending) band.foreground(false)
    }

    override fun onDestroy() {
        camera?.close()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab.name)
        outState.putString("detailParent", detailParent.name)
        outState.putString("day", historyDate.toString())
        outState.putString("period", historyPeriod.name)
        outState.putInt("workoutPage", workoutPage)
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
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        navigation = (if (expanded) column() else row()).apply {
            background = navigationGlass(palette, if (expanded) 24 else 30)
            elevation = dp(6).toFloat()
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) updateScrollInsets()
            }
        }
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
        val side = if (expanded) dp(RAIL_SPACE) else 0
        val bottom = if (expanded) 0 else maxOf(navigation.height, dp(60)) + dp(9)
        viewport.setPaddingRelative(side, 0, 0, bottom)
    }

    private fun drawNavigation() {
        navigation.removeAllViews()
        navigation.visibility = if (tab == Tab.CAMERA || tab == Tab.CLOCK) View.GONE else View.VISIBLE
        for (item in Tab.entries.filter { it.inNavigation }) {
            val selected = item == tab || (!tab.inNavigation && item == backDestination())
            val color = if (selected) palette.brand else palette.muted
            val cell = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(if (expanded) 58 else 52)
                setPadding(dp(2), dp(5), dp(2), dp(5))
                contentDescription = item.title
                isSelected = selected
                isFocusable = true
                if (selected) background = navigationGlass(palette, 24, selected = true)
                foreground = pressable(Color.TRANSPARENT, palette.ink, 24)
                setOnClickListener { go(item) }
            }
            cell.addView(Icon(this, item.icon, color), lp(dp(21), dp(21)))
            cell.addView(label(item.title, 12, color, if (selected) "sans-serif-medium" else "sans-serif")
                .apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lp(-1, -2).apply { topMargin = dp(4) })
            navigation.addView(cell, if (expanded) lp(-1, -2) else lp(0, -2, 1f))
        }
    }

    private fun go(next: Tab) {
        if (tab == next) return
        val previousCamera = camera
        camera = null
        if (next == Tab.HISTORY || next == Tab.WORKOUT_TYPES) detailParent = tab
        tab = next
        previousCamera?.close()
        queryGeneration++
        backupMessage = null
        if (next == Tab.HISTORY || next in DAILY_TABS) currentDay = null
        if (next == Tab.TODAY) summary = null
        if (next == Tab.HISTORY) trend = null
        drawNavigation()
        updateBack()
        drawScreen()
        loadData()
    }

    private fun backDestination() = when (tab) {
        Tab.BACKUP, Tab.CAMERA, Tab.CLOCK -> Tab.BAND
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

    private fun loadData() {
        if (!active || tab in setOf(Tab.BAND, Tab.CAMERA, Tab.CLOCK, Tab.WORKOUT_TYPES)) return
        val generation = ++queryGeneration
        val address = band.state.device?.address
        val zone = band.zone
        val destination = tab
        val history = destination == Tab.HISTORY
        val period = historyPeriod
        val metric = historyMetric
        val page = workoutPage
        val today = LocalDate.now(zone)
        val date = if (history) period.endDate(historyDate, today) else today
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
                    summary = if (destination == Tab.TODAY) loadSummary(address, date, zone) else null
                )
            }
            app.main.post {
                if (active && generation == queryGeneration) {
                    result.onSuccess { (day, series, log, count, overview) ->
                        currentDay = day
                        trend = series
                        if (log != null) workouts = log
                        pendingCount = count
                        summary = overview
                        readIssue = null
                    }.onFailure { readIssue = "Couldn’t open your records. Reopen the app to try again." }
                    drawScreen(keepScroll = true)
                }
            }
        }
    }

    private data class DataSnapshot(
        val day: HealthStore.Day?, val trend: HealthStore.Trend?,
        val workouts: WorkoutLog?, val pending: Long, val summary: SummaryData?
    )

    private data class SummaryData(
        val previousSteps: Long?, val workoutMinutes: Long, val averageSteps: Long?, val averageSleep: Long?
    )

    private fun loadSummary(address: String?, date: LocalDate, zone: ZoneId): SummaryData {
        val days = HistoryPeriod.WEEK.dates(date.minusDays(1))
        val steps = app.health.trend(address, days, days.last(), zone, Metric.STEPS).buckets.map { it.value(true) }
        val sleep = app.health.trend(address, days, days.last(), zone, null).buckets
        val recordedSteps = steps.filterNotNull()
        val averageSteps = recordedSteps.takeIf { it.isNotEmpty() }?.let { (it.sum() + it.size / 2) / it.size }
        return SummaryData(steps.last(), app.health.workoutMinutes(date, zone), averageSteps, sleep.combined().value(false))
    }

    private fun drawScreen(keepScroll: Boolean = false) {
        app.main.removeCallbacks(tickWorkout)
        workoutTimer = null
        if (tab == Tab.CAMERA) {
            scroll = null
            if (camera == null) cameraScreen()
            return
        }
        if (tab == Tab.CLOCK) {
            scroll = null
            clockScreen()
            return
        }
        val previous = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val page = column().apply { setPadding(dp(18), dp(12), dp(18), dp(24)) }
        val viewport = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val center = FrameLayout(this)
        val pageWidth = if (expanded && resources.configuration.screenWidthDp - RAIL_SPACE > 680) dp(680) else -1
        center.addView(page, FrameLayout.LayoutParams(pageWidth, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        viewport.addView(center, FrameLayout.LayoutParams(-1, -2))
        scroll = viewport
        updateScrollInsets()
        content.removeAllViews()
        content.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        if (!tab.inNavigation) pageHeader(page)
        when (tab) {
            Tab.TODAY -> today(page)
            Tab.HEALTH -> health(page)
            Tab.ACTIVITY -> activity(page)
            Tab.HISTORY -> history(page)
            Tab.BAND -> bandPage(page)
            Tab.BACKUP -> backupPage(page)
            Tab.WORKOUT_TYPES -> workoutTypes(page)
            Tab.CAMERA, Tab.CLOCK -> Unit
        }
        if (previous > 0) viewport.post { viewport.scrollTo(0, previous) }
    }

    private fun today(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        val day = currentDay
        val calendar = iconButton(Glyph.CALENDAR, "Activity history", true) {
            openHistory(Metric.STEPS, today)
        }
        showReadIssues(page)
        sectionHeader(page, "Activity", "Goals", 0, trailing = calendar, open = ::editGoals)
        val activity = panel()
        val values = listOf(day?.value(Metric.CALORIES), summary?.workoutMinutes, day?.value(Metric.STEPS))
        val goals = Goal.entries.mapIndexed { index, goal ->
            GoalProgress(values[index], displayPrefs.getInt(goal.name, goal.target), goalColor(goal))
        }
        val stack = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.2f
        val layout = if (stack) column() else row()
        val progress = column()
        for ((index, goal) in Goal.entries.withIndex()) {
            val reading = goals[index]
            val item = column().apply {
                minimumHeight = dp(50)
                setPadding(0, dp(3), 0, dp(3))
                background = pressable(Color.TRANSPARENT, palette.ink, 10)
                val value = reading.value?.let(::number) ?: "—"
                val target = number(reading.target.toLong())
                contentDescription = "${goal.title}, ${reading.value?.let { "$value of $target ${goal.unit}" } ?: "no reading, goal $target ${goal.unit}"}"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isFocusable = true
                setOnClickListener {
                    val metric = goal.metric
                    if (metric == null) go(Tab.ACTIVITY) else openHistory(metric, today)
                }
                addView(label(goal.title, 13, palette.muted, "sans-serif-medium").ignoreAccessibility())
                val suffix = if (goal == Goal.STEPS) " / $target" else " / $target ${goal.unit}"
                addView(label(value + suffix, 22, reading.color, "sans-serif-medium").apply {
                    letterSpacing = -.025f
                    val styled = android.text.SpannableString(text)
                    styled.setSpan(android.text.style.RelativeSizeSpan(.57f), value.length, text.length, 0)
                    styled.setSpan(android.text.style.ForegroundColorSpan(palette.muted), value.length, text.length, 0)
                    text = styled
                }.ignoreAccessibility(), lp(-1, -2).apply { topMargin = dp(2) })
            }
            progress.addView(item, lp(-1, -2))
        }
        val chart = ActivityDial(this, palette, goals)
        if (stack) {
            layout.addView(chart, lp(dp(146), dp(146)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            layout.addView(progress, lp(-1, -2))
        } else {
            layout.addView(progress, lp(0, -2, 1f))
            layout.addView(chart, lp(dp(152), dp(152)).apply { marginStart = dp(6) })
        }
        activity.addView(layout, lp(-1, -2))
        divider(activity)
        activity.addView(summaryRow(Metric.DISTANCE, day?.value(Metric.DISTANCE)) {
            openHistory(Metric.DISTANCE, today)
        }, lp(-1, -2))
        block(page, activity, 0)

        val snapshots = if (stack) column() else row().apply { gravity = Gravity.TOP }
        val steps = snapshot("Yesterday’s steps", summary?.previousSteps?.let(::number), Glyph.STEPS, palette.steps) {
            openHistory(Metric.STEPS, today.minusDays(1))
        }
        val sleep = snapshot("Last night’s sleep", day?.sleep?.let { duration(it.asleepMinutes) }, Glyph.MOON, palette.sleep) {
            openHistory(null, today)
        }
        if (stack) {
            block(snapshots, steps, 0)
            block(snapshots, sleep, 8)
        } else {
            snapshots.addView(steps, lp(0, -2, 1f))
            snapshots.addView(sleep, lp(0, -2, 1f).apply { marginStart = dp(8) })
        }
        block(page, snapshots, 8)

        sectionHeader(page, "Workouts", "View all", 17) { go(Tab.ACTIVITY) }
        val log = workouts
        val activeWorkout = log?.active
        when {
            activeWorkout != null -> workoutControl(page, 0)
            log?.recent.isNullOrEmpty() -> {
                val empty = panel()
                if (log == null) note(empty, if (readIssue == null) "Loading workouts…" else "Workouts unavailable", 3)
                else actionRow(empty, "Start workout", Glyph.WALK, !workoutBusy) { go(Tab.WORKOUT_TYPES) }
                block(page, empty, 0)
            }
            else -> workoutRecords(page, log.recent)
        }

        sectionHeader(page, "7-day trends", "History", 17) {
            openHistory(Metric.STEPS, today.minusDays(1), HistoryPeriod.WEEK)
        }
        val trends = panel()
        trends.addView(summaryRow("Average daily steps", Glyph.STEPS, summary?.averageSteps?.let(::number)) {
            openHistory(Metric.STEPS, today.minusDays(1), HistoryPeriod.WEEK)
        }, lp(-1, -2))
        divider(trends)
        trends.addView(summaryRow("Average sleep", Glyph.MOON, summary?.averageSleep?.let { duration(it.toInt()) }) {
            openHistory(null, today.minusDays(1), HistoryPeriod.WEEK)
        }, lp(-1, -2))
        block(page, trends, 0)
    }

    private fun snapshot(title: String, value: String?, glyph: Glyph, accent: Int, open: () -> Unit) = column().apply {
        setPadding(dp(13), dp(12), dp(13), dp(13))
        background = pressable(palette.surface, palette.ink, 15)
        isFocusable = true
        contentDescription = "$title, ${value ?: "no reading"}"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { open() }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Icon(this@MainActivity, glyph, accent), lp(dp(17), dp(17)))
        header.addView(View(this@MainActivity), lp(0, 1, 1f))
        header.addView(Icon(this@MainActivity, Glyph.RIGHT, palette.muted), lp(dp(14), dp(14)))
        addView(header, lp(-1, -2))
        block(this, label(title, 13, palette.muted, "sans-serif-medium").ignoreAccessibility(), 7)
        block(this, label(value ?: "—", 23, palette.ink, "sans-serif-medium").apply { letterSpacing = -.025f }
            .ignoreAccessibility(), 3)
    }

    private fun goalColor(goal: Goal) = when (goal) {
        Goal.CALORIES -> palette.calories
        Goal.WORKOUT -> palette.workout
        Goal.STEPS -> palette.steps
    }

    private fun editGoals() {
        val fields = column().apply { setPadding(dp(24), dp(4), dp(24), dp(10)) }
        val digits = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        val inputs = Goal.entries.associateWith { goal ->
            val name = if (goal == Goal.STEPS) goal.title else "${goal.title} (${goal.unit})"
            val field = EditText(this).apply {
                id = View.generateViewId()
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = name
                setSingleLine()
                setText(digits.format(displayPrefs.getInt(goal.name, goal.target)))
                setSelectAllOnFocus(true)
            }
            fields.addView(label(name, 14, palette.muted).apply { labelFor = field.id }, lp(-1, -2).apply { topMargin = dp(10) })
            fields.addView(field, lp(-1, -2))
            field
        }
        val dialog = AlertDialog.Builder(this).setTitle("Daily goals").setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = mutableMapOf<Goal, Int>()
                for ((goal, input) in inputs) {
                    val value = input.text.toString().toIntOrNull()
                    if (value == null || value !in 1..goal.max) {
                        input.error = "Enter a number from 1 to ${number(goal.max.toLong())}."
                        input.requestFocus()
                        return@setOnClickListener
                    }
                    values[goal] = value
                }
                displayPrefs.edit { values.forEach { (goal, value) -> putInt(goal.name, value) } }
                dialog.dismiss()
                drawScreen(keepScroll = true)
            }
        }
        dialog.show()
    }

    private fun showReadIssues(page: LinearLayout) {
        app.storageIssue?.let { note(page, it, 12, true) }
        readIssue?.let { note(page, it, 12, true) }
    }

    private fun health(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        heading(page, "Daily averages", 15)
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
            pages.addView(button("Newer", filled = false, enabled = workoutPage > 0) {
                changeWorkoutPage(-1)
            }, lp(0, -2, 1f))
            pages.addView(button("Older", filled = false, enabled = log?.hasMore == true) {
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
            line.addView(iconBadge(Glyph.WALK, palette.workout), lp(dp(34), dp(34)))
            val identity = column()
            identity.addView(label(record.type.title, 15, palette.ink, "sans-serif-medium"))
            val date = Instant.ofEpochMilli(record.start).atZone(band.zone).toLocalDate()
            val heart = record.averageHeartRate?.let { " · $it bpm avg" }.orEmpty()
            note(identity, date.format(MONTH_DAY) + heart, 3)
            line.addView(identity, lp(0, -2, 1f).apply { marginStart = dp(10) })
            line.addView(label(sessionDuration(record), 15, palette.workout, "sans-serif-medium"),
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
            for (type in types) actionRow(choices, type.title, Glyph.WALK, !workoutBusy) {
                val address = band.state.device?.address
                val start = System.currentTimeMillis()
                workoutPage = 0
                changeWorkout { app.health.startWorkout(type, start, address) }
                go(backDestination())
            }
            block(page, choices, if (types.size > 1) 7 else 13)
        }
    }

    private fun changeWorkoutPage(offset: Int) {
        workoutPage += offset
        workouts = workouts?.copy(recent = emptyList(), hasMore = false)
        drawScreen(keepScroll = true)
        loadData()
    }

    private fun workoutControl(page: LinearLayout, top: Int) {
        val log = workouts
        val workout = log?.active
        if (workout == null) {
            addButton(page, "Start workout", enabled = !workoutBusy && log != null, top = top) {
                go(Tab.WORKOUT_TYPES)
            }
            return
        }
        val paused = workout.pausedAt != null
        val recorder = panel()
        val header = row()
        header.addView(iconBadge(Glyph.WALK, palette.workout), lp(dp(34), dp(34)))
        header.addView(label(workout.type.title, 17, palette.ink, "sans-serif-medium"),
            lp(0, -2, 1f).apply { marginStart = dp(10) })
        header.addView(label(if (paused) "Paused" else "In progress", 13, palette.workout))
        block(recorder, header, 0)
        workoutTimer = readout("", size = 38)
        block(recorder, requireNotNull(workoutTimer), 8)
        updateWorkoutTimer(workout)
        if (active && !paused) app.main.postDelayed(tickWorkout, 1_000)
        val controls = row()
        controls.addView(button(if (paused) "Resume" else "Pause", filled = true, enabled = !workoutBusy) {
            val now = System.currentTimeMillis()
            changeWorkout {
                if (paused) app.health.resumeWorkout(workout.start, now) else app.health.pauseWorkout(workout.start, now)
            }
        }, lp(0, -2, 1f))
        controls.addView(button("End workout", filled = false, enabled = !workoutBusy) {
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
        workoutTimer?.text = workoutDuration(workout)
    }

    private fun workoutDuration(workout: Workout): String {
        val elapsed = workout.seconds(System.currentTimeMillis())
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
        summaryRow(metric.title, metric.icon, value?.let { displayValue(metric, it) }, action = action)

    private fun summaryRow(title: String, icon: Glyph, value: String?, showMissing: Boolean = true, action: () -> Unit) = row().apply {
        minimumHeight = dp(55)
        setPadding(0, dp(7), 0, dp(7))
        background = pressable(Color.TRANSPARENT, palette.ink, 11)
        isFocusable = true
        contentDescription = if (value != null || showMissing) "$title, ${value ?: "no reading"}" else title
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { action() }
        addView(Icon(this@MainActivity, icon, palette.ink), lp(dp(22), dp(22)))
        addView(label(title, 16, palette.ink, "sans-serif-medium")
            .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(0, -2, 1f).apply { marginStart = dp(12) })
        if (value != null || showMissing) {
            val reading = label(value ?: "—", 15, palette.muted).apply {
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
        val metricName = metric?.title ?: "Sleep"
        val picker = row().apply {
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pressable(palette.surface, palette.ink, 20)
            contentDescription = "$metricName, choose metric"
            isFocusable = true
            addView(Icon(this@MainActivity, metric?.icon ?: Glyph.MOON, palette.ink), lp(dp(22), dp(22)))
            addView(label(metricName, 18, palette.ink, "sans-serif-medium"),
                lp(0, -2, 1f).apply { marginStart = dp(11) })
            addView(Icon(this@MainActivity, Glyph.DOWN, palette.ink), lp(dp(19), dp(19)))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Metric")
                    .setSingleChoiceItems((Metric.entries.map { it.title } + "Sleep").toTypedArray(),
                        metric?.ordinal ?: Metric.entries.size) { dialog, which ->
                        historyMetric = Metric.entries.getOrNull(which)
                        dialog.dismiss()
                        selectDate(historyDate)
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        block(page, picker, 12)
        val periods = row().apply {
            background = shape(palette.tint, 19)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (choice in HistoryPeriod.entries) {
            val control = button(choice.title, filled = false) {
                if (historyPeriod != choice) {
                    historyPeriod = choice
                    selectDate(historyDate)
                }
            }.apply {
                isSelected = period == choice
                background = pressable(if (isSelected) palette.surface else Color.TRANSPARENT, palette.ink, 15)
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
            setOnClickListener {
                DatePickerDialog(this@MainActivity, { _, y, m, d -> selectDate(LocalDate.of(y, m + 1, d)) },
                    historyDate.year, historyDate.monthValue - 1, historyDate.dayOfMonth
                ).apply {
                    datePicker.maxDate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
                    show()
                }
            }
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
            detail.addView(readout(number(value), metric.unit))
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
            panel.addView(if (metric == null) readout(duration(value.toInt())) else readout(number(value), metric.unit))
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
                     caption: Boolean = false, openPoint: ((Int) -> Unit)? = null) {
        fun description(point: ChartPoint): String {
            val value = point.value?.let { if (metric == null) duration(it.toInt()) else displayValue(metric, it) }
            return "${point.label}, ${value ?: "no reading"}"
        }
        if (caption) note(parent, title, top)
        val description = if (openPoint == null) "$title. " + points.joinToString(". ", transform = ::description) else null
        val chart = BarChart(this, palette, points, description, durationAxis = metric == null)
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

    private fun sleepPanel(night: SleepNight?, historyLink: LocalDate? = null) = panel().apply {
        if (historyLink != null) {
            addView(summaryRow("Sleep", Glyph.MOON, null, showMissing = false) {
                openHistory(null, historyLink)
            }, lp(-1, -2))
        }
        if (night == null) {
            note(this, if (currentDay == null && readIssue == null) "Loading sleep…" else "No sleep recorded", 4)
            return@apply
        }
        addView(readout(duration(night.asleepMinutes)), lp(-1, -2))
        val start = LocalTime.ofSecondOfDay(night.startMinute * 60L)
        note(this, "Sleep began at ${TIME.format(start)}", 5)
        block(this, SleepTimeline(this@MainActivity, palette, night.segments), 18, dp(20))
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
        currentDay = null
        trend = null
        drawScreen()
        loadData()
    }

    private fun bandPage(page: LinearLayout) {
        val state = band.state
        val current = state.connecting ?: state.device
        val connected = state.connection == Connection.CONNECTED
        val idle = state.action == BandAction.IDLE
        val devicePanel = panel()
        val identity = row()
        identity.addView(Icon(this, Glyph.BAND, palette.brand), lp(dp(48), dp(48)))
        val words = column()
        words.addView(label(current?.name ?: "Connect a band", 23, palette.ink, "sans-serif-medium"))
        if (current != null) note(words, connectionText(state), 4)
        identity.addView(words, lp(0, -2, 1f).apply { marginStart = dp(13) })
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
        block(page, devicePanel, 10)
        app.storageIssue?.let { note(page, it, 12, true) }

        heading(page, "Tools", 24)
        val tools = panel()
        actionRow(tools, "Phone clock", Glyph.CLOCK, true) { go(Tab.CLOCK) }
        if (phoneCameraAvailable()) actionRow(tools, "Camera remote", Glyph.CAMERA, true, action = ::openCamera)
        if (connected) {
            actionRow(tools, "Find band", Glyph.SCAN, idle) { band.findBand() }
            spotCheck(tools, state)
        }
        if (state.device != null || app.callAlertsRunning) {
            actionRow(tools, if (app.callAlertsRunning) "Turn off call alerts" else "Turn on call alerts",
                Glyph.PHONE, true) { toggleCallAlerts() }
            if (connected) actionRow(tools, "Test call alert", Glyph.PHONE, idle) { band.testCallAlert() }
            app.callAlertsIssue?.let { note(tools, it, 7, true) }
        }
        block(page, tools, 9)

        if (connected) {
            heading(page, "Settings", 25)
            val actions = panel()
            actionRow(actions, "Sync past 7 days", Glyph.CALENDAR, idle) { band.importHistory(true) }
            actionRow(actions, "Set band clock", Glyph.CLOCK, idle) { band.setClock() }
            actionRow(actions, "Heart-rate logging", Glyph.PULSE, idle) {
                AlertDialog.Builder(this).setTitle("Heart-rate logging")
                    .setItems(arrayOf("Record every 5 minutes", "Pause logging")) { _, which -> band.setLogging(which == 0) }
                    .setNegativeButton("Cancel", null).show()
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
            block(page, actions, 9)
        } else if (state.connection == Connection.DISCONNECTED) {
            if (state.device != null || state.scanning || state.searched || state.nearby.isNotEmpty()) {
                heading(page, "Nearby bands", 25)
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
                if (nearby.childCount > 0) block(page, nearby, 9)
            }
            if (state.device != null && !state.scanning) {
                addButton(page, "Forget band", filled = false, top = 18) {
                    AlertDialog.Builder(this).setTitle("Forget this band?")
                        .setMessage("Readings stay on this phone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Forget") { _, _ ->
                            stopService(Intent(this, CallRelayService::class.java))
                            band.forget()
                        }.show()
                }
            }
        }
        heading(page, "Data", 24)
        val data = panel()
        actionRow(data, "Backup", Glyph.BACKUP, true) { go(Tab.BACKUP) }
        block(page, data, 9)
    }

    private fun spotCheck(tools: LinearLayout, state: BandState) {
        val measuring = state.action == BandAction.MEASURING
        actionRow(tools, if (measuring) "Stop measurement" else "Spot check", Glyph.PULSE,
            measuring || state.action == BandAction.IDLE) {
            if (measuring) band.stopMeasurement() else {
                val metrics = HEALTH_METRICS.filter { it.liveType != null }
                AlertDialog.Builder(this).setTitle("Spot check")
                    .setItems(metrics.map { it.title }.toTypedArray()) { _, index -> band.measure(metrics[index]) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        val metric = state.measurement ?: return
        val value = state.liveValue?.toLong()
        if (measuring) note(tools, value?.let { "${metric.title} · ${displayValue(metric, it)}" } ?: "Keep your wrist still.", 3)
        else if (value != null && state.measurementAt != null) {
            val date = state.measurementAt.atZone(band.zone).toLocalDate()
            divider(tools)
            tools.addView(summaryRow(metric, value) { openHistory(metric, date) }, lp(-1, -2))
        }
        state.measurementMessage?.let { note(tools, it, 3, true) }
    }

    private fun toggleCallAlerts() {
        val service = Intent(this, CallRelayService::class.java)
        if (app.callAlertsRunning) {
            stopService(service)
            return
        }
        if (!BandClient.hasPermissions(this)) {
            bluetooth(PermissionAction.RECONNECT)
            return
        }
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
            app.callAlertsIssue = "Couldn’t start call alerts. Open Band and try again."
            drawScreen(keepScroll = true)
        } catch (_: SecurityException) {
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

    private fun permissionSettings(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }.setNegativeButton("Cancel", null).show()
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
        val take = button("Take photo", filled = true) {}
        val retry = button("Retry band shutter", filled = false) {}
        val settings = row()
        val timer = button("Timer off", filled = false) {}
        val lens = button("Switch camera", filled = false) {}
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

    private fun clockScreen() {
        val digital = displayPrefs.getBoolean("digitalClock", true)
        val screen = column().apply { setPadding(dp(18), dp(10), dp(18), dp(20)) }
        pageHeader(screen)
        val face = panel().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(26), dp(12), dp(26)) }
        val time = TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = if (digital) 56f else 20f
            if (digital) setAutoSizeTextTypeUniformWithConfiguration(20, 56, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "'tnum'"
            letterSpacing = -.035f
            gravity = Gravity.CENTER
            setTextColor(palette.brand)
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
            styles.addView(button(name, filled = selected) {
                displayPrefs.edit { putBoolean("digitalClock", choice) }
                clockScreen()
            }.apply { isSelected = selected }, lp(0, -2, 1f).apply { if (!choice) marginStart = dp(8) })
        }
        block(screen, styles, 9)
        scroll = ScrollView(this).apply { addView(screen); isFillViewport = true }
        content.removeAllViews()
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun detailsDialog(configured: Boolean) {
        val fields = column().apply { setPadding(dp(22), dp(3), dp(22), 0) }
        val username = EditText(this).apply {
            hint = "QWatch username or email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
            isSaveEnabled = false
        }
        fields.addView(username, lp(-1, -2))
        note(fields, "Use the QWatch username already linked to this band.", 6)
        val builder = AlertDialog.Builder(this).setTitle("Sleep and oxygen sync").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save & sync", null)
        if (configured) builder.setNeutralButton("Remove username") { _, _ -> band.setDetailUsername(null) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (band.setDetailUsername(username.text.toString())) dialog.dismiss()
                else username.error = if (band.state.action == BandAction.IDLE && band.state.detailSyncAvailable)
                    "Enter a valid QWatch username." else "Reconnect to the band and try again."
            }
        }
        dialog.show()
    }

    private fun actionRow(parent: LinearLayout, title: String, glyph: Glyph, enabled: Boolean,
                          detail: String? = null, action: () -> Unit) {
        if (parent.childCount > 0) divider(parent)
        val line = row().apply {
            minimumHeight = dp(52)
            setPadding(0, dp(8), 0, dp(8))
            background = pressable(Color.TRANSPARENT, palette.brand, 10)
            isEnabled = enabled
            isFocusable = enabled
            contentDescription = if (detail == null) title else "$title, $detail"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (enabled) setOnClickListener { action() }
            addView(Icon(this@MainActivity, glyph, if (enabled) palette.brand else palette.muted), lp(dp(22), dp(22)))
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
        val status = panel()
        section(status, Glyph.BACKUP, "Home server", palette.brand)
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
        val fields = column().apply { setPadding(dp(22), dp(3), dp(22), 0) }
        val address = EditText(this).apply {
            hint = "https://backup.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(endpoint.orEmpty())
            contentDescription = "HTTPS server address"
        }
        val key = EditText(this).apply {
            hint = "Backup key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        fields.addView(address, lp(-1, -2))
        fields.addView(key, lp(-1, -2).apply { topMargin = dp(8) })
        if (endpoint != null) note(fields, "Leave the key blank to keep it for this server.", 9)
        val error = label("", 13, palette.warning).apply { visibility = View.GONE }
        block(fields, error, 6)
        val builder = AlertDialog.Builder(this).setTitle("Home server").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save & sync", null)
        if (endpoint != null) builder.setNeutralButton("Turn off backup") { _, _ -> disableBackup() }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = address.text.toString()
                val token = key.text.toString()
                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                app.databaseThread.execute {
                    val result = runCatching { app.settings.save(host, token) }
                    app.main.post {
                        if (isDestroyed) return@post
                        result.onSuccess {
                            key.text.clear()
                            dialog.dismiss()
                            backupRequestedAt = System.currentTimeMillis()
                            backupMessage = if (BackupJob.schedule(this, immediate = true, replaceManual = true))
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
        }
        dialog.show()
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
        if (action == PermissionAction.SCAN) band.scan() else band.reconnect()
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

    private fun readout(value: String, unit: String = "", size: Int = 43): TextView {
        val text = if (unit.isEmpty()) value else if (unit == "%") "$value%" else "$value $unit"
        return label(text, size, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.038f
            if (unit.isNotEmpty()) {
                val styled = android.text.SpannableString(text)
                styled.setSpan(android.text.style.RelativeSizeSpan(.44f), value.length, text.length, 0)
                styled.setSpan(android.text.style.ForegroundColorSpan(palette.muted), value.length, text.length, 0)
                this.text = styled
            }
        }
    }

    private fun pageHeader(page: LinearLayout) {
        val bar = row()
        val parent = backDestination()
        bar.addView(iconButton(Glyph.LEFT, "Back to ${parent.title}", true) { go(parent) }, lp(dp(48), dp(48)))
        bar.addView(label(tab.title, 24, palette.ink, "sans-serif-medium").apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(0, -2, 1f))
        block(page, bar, 0)
    }

    private fun sectionHeader(page: LinearLayout, title: String, action: String, top: Int, trailing: View? = null, open: () -> Unit) {
        val header = row()
        header.addView(label(title, 22, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.025f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(0, -2, 1f))
        header.addView(button(action, filled = false, action = open).apply {
            background = pressable(Color.TRANSPARENT, palette.brand, 12)
            setPadding(dp(11), dp(6), 0, dp(6))
        }, lp(-2, -2))
        trailing?.let { header.addView(it, lp(dp(48), dp(48)).apply { marginStart = dp(8) }) }
        block(page, header, top)
    }

    private fun iconBadge(icon: Glyph, accent: Int) = FrameLayout(this).apply {
        background = shape(ColorUtils.blendARGB(palette.surface, accent, .17f), 12)
        addView(Icon(this@MainActivity, icon, accent), FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
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
        header.addView(Icon(this, glyph, accent), lp(dp(22), dp(22)))
        header.addView(label(text, 18, palette.ink, "sans-serif-medium").apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true },
            lp(0, -2, 1f).apply { marginStart = dp(9) })
        panel.addView(header, lp(-1, -2))
    }

    private fun note(parent: LinearLayout, text: String, top: Int, warning: Boolean = false) =
        block(parent, label(text, 14, if (warning) palette.warning else palette.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }, top)

    private fun panel() = column().apply {
        background = shape(palette.surface, 15)
        setPadding(dp(15), dp(9), dp(15), dp(9))
    }

    private fun iconButton(glyph: Glyph, description: String, enabled: Boolean, action: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            background = pressable(Color.TRANSPARENT, palette.ink, 12)
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
        block(parent, button(text, filled, enabled, action), top)
    }

    private fun button(title: String, filled: Boolean, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextSize(14f)
        minimumHeight = dp(48)
        minHeight = dp(48)
        minWidth = 0
        gravity = Gravity.CENTER
        stateListAnimator = null
        val bg = if (!enabled) palette.background else if (filled) palette.brand else palette.surface
        val fg = if (!enabled) palette.muted else if (filled) palette.onBrand else palette.brand
        background = pressable(bg, fg, 13, if (!filled || !enabled) palette.border else null)
        setTextColor(fg)
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
        private val DAILY_TABS = setOf(Tab.TODAY, Tab.HEALTH)
        private val HEALTH_METRICS = listOf(Metric.HEART, Metric.OXYGEN, Metric.HRV, Metric.STRESS)
        private val SHORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
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
    }
}
