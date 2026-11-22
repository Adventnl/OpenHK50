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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import org.openhk50.OpenHkApp
import org.openhk50.band.BandAction
import org.openhk50.band.BandClient
import org.openhk50.band.BandState
import org.openhk50.band.CallRelayService
import org.openhk50.band.Connection
import org.openhk50.data.HealthStore
import org.openhk50.data.Metric
import org.openhk50.data.Origin
import org.openhk50.data.Reading
import org.openhk50.data.SleepNight
import org.openhk50.data.SleepStage
import org.openhk50.data.Workout
import org.openhk50.data.WorkoutLog
import org.openhk50.data.WorkoutType
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
        TODAY("Today", Glyph.TODAY), HEALTH("Health", Glyph.PULSE), ACTIVITY("Activity", Glyph.WALK),
        HISTORY("History", Glyph.CALENDAR), BAND("Band", Glyph.BAND),
        BACKUP("Backup", Glyph.BACKUP, false), CAMERA("Wrist shutter", Glyph.CAMERA, false)
    }
    private enum class PermissionAction { SCAN, RECONNECT }

    private val app get() = application as OpenHkApp
    private val band get() = app.band
    private lateinit var palette: Palette
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var navigation: LinearLayout
    private var scroll: ScrollView? = null
    private var tab = Tab.TODAY
    private var historyMetric: Metric? = Metric.HEART
    private var historyDate = LocalDate.now()
    private var historyWeek = false
    private var shownRecords = 24
    private var currentDay: HealthStore.Day? = null
    private var week: List<Pair<LocalDate, HealthStore.Day>> = emptyList()
    private var workouts: WorkoutLog? = null
    private var workoutBusy = false
    private var workoutLimit = 30
    private var workoutTimer: TextView? = null
    private var camera: BandCamera? = null
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
        if (granted) go(Tab.CAMERA)
        else permissionSettings("Camera access", "Allow camera access to use the wrist shutter.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        palette = Palette.of(night)
        expanded = resources.configuration.screenWidthDp >= 680
        tab = savedInstanceState?.getString("tab")?.let(Tab::valueOf) ?: Tab.TODAY
        if (tab == Tab.CAMERA && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            tab = Tab.BAND
        }
        historyDate = savedInstanceState?.getString("day")?.let(LocalDate::parse) ?: LocalDate.now(band.zone)
        historyWeek = savedInstanceState?.getBoolean("historyWeek") == true
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
                loadData()
            }
            if (tab in DAILY_TABS && lastShownDate != LocalDate.now(band.zone)) {
                currentDay = null
                loadData()
            }
            if (tab == Tab.CAMERA) camera?.update()
            else if (tab != Tab.BACKUP) drawScreen(keepScroll = true)
        }
        band.foreground(true)
        if (app.settings.state().endpoint != null) BackupJob.schedule(this)
        loadData()
        app.main.postDelayed(checkDay, 30_000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        camera?.resume(true)
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
        if (!isChangingConfigurations && permissionAction == null) band.foreground(false)
    }

    override fun onDestroy() {
        camera?.close()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab.name)
        outState.putString("day", historyDate.toString())
        outState.putBoolean("historyWeek", historyWeek)
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
        root = column().apply { setBackgroundColor(palette.background) }
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
        navigation = if (expanded) column() else row()
        if (expanded) {
            val body = row().apply { gravity = Gravity.TOP }
            body.addView(navigation, lp(dp(104), -1))
            body.addView(content, lp(0, -1, 1f))
            root.addView(body, lp(-1, 0, 1f))
        } else {
            root.addView(content, lp(-1, 0, 1f))
            root.addView(View(this).apply { setBackgroundColor(palette.border) }, lp(-1, dp(1)))
            root.addView(navigation, lp(-1, -2))
        }
        drawNavigation()
        updateBack()
    }

    private fun drawNavigation() {
        navigation.removeAllViews()
        navigation.setPadding(dp(8), dp(6), dp(8), dp(6))
        navigation.setBackgroundColor(if (expanded) palette.background else palette.surface)
        for (item in Tab.entries.filter { it.inNavigation }) {
            val selected = item == tab || (item == Tab.BAND && backDestination() == Tab.BAND)
            val color = if (selected) palette.brand else palette.muted
            val cell = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(if (expanded) 74 else 54)
                contentDescription = item.title
                isSelected = selected
                isFocusable = true
                background = pressable(Color.TRANSPARENT, palette.ink, 13)
                setOnClickListener { go(item) }
            }
            val pill = FrameLayout(this).apply {
                if (selected) background = shape(palette.tint, 18)
            }
            pill.addView(Icon(this, item.icon, color), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            cell.addView(pill, lp(dp(48), dp(28)))
            cell.addView(label(item.title, 12, color, if (selected) "sans-serif-medium" else "sans-serif")
                .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(-2, -2))
            navigation.addView(cell, if (expanded) lp(-1, -2) else lp(0, -2, 1f))
        }
    }

    private fun go(next: Tab) {
        if (tab == next) return
        camera?.close()
        camera = null
        tab = next
        queryGeneration++
        backupMessage = null
        if (next == Tab.HISTORY || next in DAILY_TABS) currentDay = null
        if (next == Tab.HISTORY) week = emptyList()
        drawNavigation()
        updateBack()
        drawScreen()
        loadData()
    }

    private fun backDestination() = if (tab == Tab.BACKUP || tab == Tab.CAMERA) Tab.BAND else Tab.TODAY

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
        if (!active || tab == Tab.BAND || tab == Tab.CAMERA) return
        val generation = ++queryGeneration
        val address = band.state.device?.address
        val zone = band.zone
        val destination = tab
        val trends = destination == Tab.HISTORY && historyWeek
        val limit = workoutLimit
        val date = if (destination == Tab.HISTORY) historyDate else LocalDate.now(zone)
        app.databaseThread.execute {
            val result = runCatching {
                DataSnapshot(
                    day = if (destination == Tab.BACKUP || trends) null else app.health.day(address, date, zone),
                    week = if (trends) app.health.week(address, date, zone) else emptyList(),
                    workouts = when (destination) {
                        Tab.TODAY -> app.health.workouts(30)
                        Tab.ACTIVITY -> app.health.workouts(limit)
                        else -> null
                    },
                    pending = if (destination == Tab.BACKUP) app.health.pendingCount() else 0L
                )
            }
            app.main.post {
                if (active && generation == queryGeneration) {
                    result.onSuccess { (day, trend, log, count) ->
                        currentDay = day
                        week = trend
                        if (log != null) workouts = log
                        pendingCount = count
                        readIssue = null
                    }.onFailure { readIssue = "Couldn’t open your records. Reopen the app to try again." }
                    drawScreen(keepScroll = true)
                }
            }
        }
    }

    private data class DataSnapshot(
        val day: HealthStore.Day?, val week: List<Pair<LocalDate, HealthStore.Day>>,
        val workouts: WorkoutLog?, val pending: Long
    )

    private fun drawScreen(keepScroll: Boolean = false) {
        app.main.removeCallbacks(tickWorkout)
        workoutTimer = null
        if (tab == Tab.CAMERA) {
            if (camera == null) cameraScreen()
            return
        }
        val previous = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val page = column().apply { setPadding(dp(22), dp(18), dp(22), dp(26)) }
        val viewport = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val center = FrameLayout(this)
        val pageWidth = if (expanded) (resources.configuration.screenWidthDp - 104).coerceAtMost(696) else -1
        center.addView(page, FrameLayout.LayoutParams(if (pageWidth == -1) -1 else dp(pageWidth), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        viewport.addView(center, FrameLayout.LayoutParams(-1, -2))
        scroll = viewport
        content.removeAllViews()
        content.addView(viewport, FrameLayout.LayoutParams(-1, -1))

        when (tab) {
            Tab.TODAY -> today(page)
            Tab.HEALTH -> health(page)
            Tab.ACTIVITY -> activity(page)
            Tab.HISTORY -> history(page)
            Tab.BAND -> bandPage(page)
            Tab.BACKUP -> backupPage(page)
            Tab.CAMERA -> Unit
        }
        if (previous > 0) viewport.post { viewport.scrollTo(0, previous) }
    }

    private fun today(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        title(page, "Today", today.format(LONG_DATE))
        block(page, connectionStrip(band.state), 16)
        showReadIssues(page)

        val day = currentDay
        val summaries = row().apply { gravity = Gravity.TOP }
        summaries.addView(dailyTile("Steps", Metric.STEPS, day?.value(Metric.STEPS), today), lp(0, -1, 1f))
        summaries.addView(dailyTile("Average heart rate", Metric.HEART, day?.averageHeartRate(), today),
            lp(0, -1, 1f).apply { marginStart = dp(10) })
        block(page, summaries, 20)

        heading(page, "Steps by time", 22)
        val steps = day?.readings(Metric.STEPS).orEmpty()
        val chart = panel()
        if (steps.isEmpty()) note(chart, if (day == null && readIssue == null) "Loading steps…" else "No steps recorded today", 4)
        else block(chart, SeriesChart(this, palette, Metric.STEPS, today, band.zone, steps), 3, dp(132))
        block(page, chart, 10)

        heading(page, "Your day", 23)
        val daily = panel()
        daily.addView(summaryRow("Sleep", Glyph.MOON, palette.brand, day?.sleep?.let { duration(it.asleepMinutes) }) {
            openHistory(null, today)
        }, lp(-1, -2))
        divider(daily)
        daily.addView(summaryRow("Health monitor", Glyph.PULSE, palette.heart, null, showMissing = false) {
            go(Tab.HEALTH)
        }, lp(-1, -2))
        divider(daily)
        val activeWorkout = workouts?.active
        val workoutValue = activeWorkout?.type?.title ?: workouts?.recent?.count {
            Instant.ofEpochMilli(it.start).atZone(band.zone).toLocalDate() == today
        }?.takeIf { it > 0 }?.toString()
        daily.addView(summaryRow("Workouts", Glyph.WALK, palette.steps, workoutValue, showMissing = false) {
            go(Tab.ACTIVITY)
        }, lp(-1, -2))
        block(page, daily, 9)
    }

    private fun dailyTile(title: String, metric: Metric, value: Long?, date: LocalDate) = panel().apply {
        setPadding(dp(14), dp(13), dp(14), dp(15))
        background = pressable(palette.surface, palette.accent(metric), 16)
        isFocusable = true
        contentDescription = "$title, ${value?.let { displayValue(metric, it) } ?: "no reading"}"
        setOnClickListener { openHistory(metric, date) }
        addView(Icon(this@MainActivity, metric.icon, palette.accent(metric)), lp(dp(23), dp(23)))
        val reading = value?.let { if (metric == Metric.STEPS) number(it) else displayValue(metric, it) }
        block(this, label(reading ?: "—", 27, palette.accent(metric), "sans-serif-medium"), 7)
        block(this, label(title, 14, palette.ink, "sans-serif-medium"), 2)
    }

    private fun showReadIssues(page: LinearLayout) {
        app.storageIssue?.let { note(page, it, 12, true) }
        readIssue?.let { note(page, it, 12, true) }
    }

    private fun health(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        title(page, "Health monitor")
        showReadIssues(page)
        val day = currentDay
        val metrics = panel()
        for (metric in HEALTH_METRICS) {
            if (metrics.childCount > 0) divider(metrics)
            val live = band.state.liveValue?.toLong()?.takeIf { band.state.measurement == metric }
            metrics.addView(summaryRow(metric, live ?: day?.value(metric)) { openHistory(metric, today) }, lp(-1, -2))
        }
        block(page, metrics, 18)
        heading(page, "Sleep", 22)
        block(page, sleepPanel(day?.sleep), 9)
        addButton(page, "Sleep history", filled = false, top = 10) { openHistory(null, today) }
    }

    private fun activity(page: LinearLayout) {
        val today = LocalDate.now(band.zone)
        lastShownDate = today
        title(page, "Activity")
        showReadIssues(page)
        val day = currentDay
        val daily = panel()
        for (metric in ACTIVITY_METRICS) {
            if (daily.childCount > 0) divider(daily)
            daily.addView(summaryRow(metric, day?.value(metric)) { openHistory(metric, today) }, lp(-1, -2))
        }
        block(page, daily, 16)

        heading(page, "Workout recorder", 24)
        val log = workouts
        val recorder = panel()
        val workout = log?.active
        if (workout == null) {
            addButton(recorder, "Start workout", enabled = !workoutBusy && log != null, top = 0) {
                AlertDialog.Builder(this).setTitle("Workout type")
                    .setItems(WorkoutType.entries.map { it.title }.toTypedArray()) { _, index ->
                        val address = band.state.device?.address
                        val start = System.currentTimeMillis()
                        changeWorkout { app.health.startWorkout(WorkoutType.entries[index], start, address) }
                    }.setNegativeButton("Cancel", null).show()
            }
        } else {
            recorder.addView(label(workout.type.title, 17, palette.ink, "sans-serif-medium"))
            workoutTimer = label("", 36, palette.brand, "sans-serif-medium")
            block(recorder, requireNotNull(workoutTimer), 5)
            updateWorkoutTimer(workout)
            if (active) app.main.postDelayed(tickWorkout, 1_000)
            addButton(recorder, "End workout", enabled = !workoutBusy, top = 9) {
                changeWorkout { app.health.endWorkout(workout.start, System.currentTimeMillis()) }
            }
        }
        block(page, recorder, 9)

        heading(page, "Workout history", 24)
        val records = panel()
        for (record in log?.recent.orEmpty()) {
            if (records.childCount > 0) divider(records)
            val line = row().apply { setPadding(0, dp(9), 0, dp(9)); minimumHeight = dp(52) }
            val identity = column()
            identity.addView(label(record.type.title, 16, palette.ink, "sans-serif-medium"))
            note(identity, dateTime(record.start, band.zone), 3)
            record.averageHeartRate?.let { note(identity, "Average heart rate · ${displayValue(Metric.HEART, it.toLong())}", 3) }
            line.addView(identity, lp(0, -2, 1f))
            line.addView(label(workoutDuration(record), 15, palette.ink), lp(-2, -2).apply { marginStart = dp(8) })
            records.addView(line, lp(-1, -2))
        }
        if (records.childCount == 0) note(records, if (log == null && readIssue == null) "Loading workouts…" else "No workouts recorded", 2)
        block(page, records, 9)
        if (log?.hasMore == true) addButton(page, "Show more workouts", filled = false, top = 9) {
            workoutLimit += 30
            loadData()
        }
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

    private fun openHistory(metric: Metric?, day: LocalDate) {
        historyMetric = metric
        historyWeek = false
        historyDate = day
        shownRecords = 24
        go(Tab.HISTORY)
    }

    private fun summaryRow(metric: Metric, value: Long?, action: () -> Unit) =
        summaryRow(metric.title, metric.icon, palette.accent(metric), value?.let { displayValue(metric, it) }, action = action)

    private fun summaryRow(title: String, icon: Glyph, accent: Int, value: String?, showMissing: Boolean = true, action: () -> Unit) = row().apply {
        minimumHeight = dp(55)
        setPadding(0, dp(7), 0, dp(7))
        background = pressable(Color.TRANSPARENT, palette.ink, 11)
        isFocusable = true
        contentDescription = if (value != null || showMissing) "$title, ${value ?: "no reading"}" else title
        setOnClickListener { action() }
        addView(Icon(this@MainActivity, icon, accent), lp(dp(23), dp(23)))
        addView(label(title, 16, palette.ink, "sans-serif-medium"), lp(0, -2, 1f).apply { marginStart = dp(12) })
        if (value != null || showMissing) addView(label(value ?: "—", 15, palette.muted),
            lp(-2, -2).apply { marginStart = dp(8) })
        addView(Icon(this@MainActivity, Glyph.RIGHT, palette.muted), lp(dp(15), dp(15)).apply { marginStart = dp(8) })
    }

    private fun history(page: LinearLayout) {
        title(page, "History")
        val zone = band.zone
        val day = currentDay
        val today = LocalDate.now(zone)
        val hasEarlier = if (historyWeek) week.firstOrNull()?.second?.earlier == true else day?.earlier == true
        val dateStep = if (historyWeek) 7L else 1L
        val dateRow = row().apply { background = shape(palette.surface, 15, palette.border); setPadding(dp(5), 0, dp(5), 0) }
        dateRow.addView(iconButton(Glyph.LEFT, "Previous period", hasEarlier) {
            selectDate(historyDate.minusDays(dateStep))
        }, lp(dp(48), dp(52)))
        val dateText = if (historyWeek) {
            "${historyDate.minusDays(6).format(MONTH_DAY)} – ${historyDate.format(MONTH_DAY)}"
        } else historyDate.format(SHORT_DATE)
        val date = label(dateText, 15, palette.ink, "sans-serif-medium").apply {
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
        dateRow.addView(iconButton(Glyph.RIGHT, "Next period", historyDate < today) {
            selectDate(historyDate.plusDays(dateStep))
        }, lp(dp(48), dp(52)))
        block(page, dateRow, 18)
        val periods = row()
        listOf(false to "Day", true to "7 days").forEach { (isWeek, name) ->
            periods.addView(button(name, filled = historyWeek == isWeek) {
                if (historyWeek != isWeek) {
                    historyWeek = isWeek
                    selectDate(historyDate)
                }
            }.apply { isSelected = historyWeek == isWeek }, lp(0, -2, 1f).apply {
                if (isWeek) marginStart = dp(8)
            })
        }
        block(page, periods, 8)

        val metric = historyMetric
        val metricName = metric?.title ?: "Sleep"
        val picker = row().apply {
            minimumHeight = dp(52)
            setPadding(dp(15), dp(6), dp(15), dp(6))
            background = pressable(palette.surface, palette.brand, 15)
            contentDescription = "$metricName, choose metric"
            isFocusable = true
            addView(Icon(this@MainActivity, metric?.icon ?: Glyph.MOON, metric?.let(palette::accent) ?: palette.brand),
                lp(dp(21), dp(21)))
            addView(label(metricName, 16, palette.ink, "sans-serif-medium"), lp(0, -2, 1f).apply { marginStart = dp(11) })
            addView(Icon(this@MainActivity, Glyph.DOWN, palette.brand), lp(dp(19), dp(19)))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Health metric")
                    .setSingleChoiceItems((Metric.entries.map { it.title } + "Sleep").toTypedArray(),
                        metric?.ordinal ?: Metric.entries.size) { dialog, which ->
                        historyMetric = Metric.entries.getOrNull(which)
                        shownRecords = 24
                        dialog.dismiss()
                        drawScreen(keepScroll = true)
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        block(page, picker, 10)
        readIssue?.let { note(page, it, 12, true) }

        if (historyWeek) {
            weeklyHistory(page, metric)
            return
        }
        if (metric == null) {
            block(page, sleepPanel(day?.sleep), 16)
            return
        }
        val values = day?.readings(metric).orEmpty()
        val state = band.state
        val currentMeasurement = state.measurement == metric && historyDate == today
        val live = state.liveValue?.takeIf { currentMeasurement }?.toLong()
        val detail = metricPanel(metric, values, historyDate, zone, live ?: day?.value(metric), live != null)
        if (metric == Metric.HEART) day?.averageHeartRate()?.let {
            note(detail, "Average heart rate · ${displayValue(metric, it)}", 7)
        }
        if (historyDate == today && metric.liveType != null) {
            val measuring = currentMeasurement && state.action == BandAction.MEASURING
            if (state.connection == Connection.CONNECTED) {
                addButton(detail, if (measuring) "Stop measurement" else "Measure",
                    enabled = measuring || state.action == BandAction.IDLE, top = 12) {
                    if (measuring) band.stopMeasurement() else band.measure(metric)
                }
                if (measuring && state.liveValue == null) note(detail, "Keep your wrist still.", 8)
                if (currentMeasurement) state.measurementMessage?.let { note(detail, it, 8, true) }
            } else {
                addButton(detail, "Connect band", filled = false, top = 12) { go(Tab.BAND) }
            }
        }
        block(page, detail, 16)
        if (values.isEmpty()) return

        heading(page, "Readings", 23)
        val records = panel()
        for (record in values.asReversed().take(shownRecords)) {
            if (records.childCount > 0) divider(records)
            val line = row().apply { minimumHeight = dp(46) }
            val timestamp = TIME.format(Instant.ofEpochMilli(record.time).atZone(zone))
            val detail = if (record.origin == Origin.LIVE) "$timestamp · Spot check" else timestamp
            line.addView(label(detail, 14, palette.muted), lp(0, -2, 1f))
            line.addView(label(displayValue(record.metric, record.value.toLong()), 15, palette.ink, "sans-serif-medium"))
            records.addView(line, lp(-1, -2))
        }
        block(page, records, 10)
        if (shownRecords < values.size) addButton(page, "Show more readings", filled = false, top = 12) {
            shownRecords += 24
            drawScreen(keepScroll = true)
        }
    }

    private fun weeklyHistory(page: LinearLayout, metric: Metric?) {
        val values = week.map { (_, day) ->
            when (metric) {
                null -> day.sleep?.asleepMinutes?.toLong()
                Metric.HEART -> day.averageHeartRate()
                else -> day.value(metric)
            }
        }
        val plot = panel()
        if (values.none { it != null }) {
            note(plot, if (week.isEmpty() && readIssue == null) "Loading readings…" else "No readings for this period", 4)
        } else {
            if (metric == Metric.HEART) section(plot, Glyph.PULSE, "Daily average heart rate", palette.heart)
            block(plot, WeekChart(this, palette, metric?.let(palette::accent) ?: palette.brand,
                week.map { it.first.format(WEEKDAY) }, values, metric == null || metric.isTotal), 5, dp(150))
            for ((index, entry) in week.withIndex().reversed()) {
                divider(plot)
                val value = values[index]?.let {
                    if (metric == null) duration(it.toInt()) else displayValue(metric, it)
                }
                plot.addView(summaryRow(entry.first.format(SHORT_DATE), Glyph.CALENDAR, palette.muted, value) {
                    historyWeek = false
                    selectDate(entry.first)
                }, lp(-1, -2))
            }
        }
        block(page, plot, 13)
    }

    private fun sleepPanel(night: SleepNight?) = panel().apply {
        if (night == null) {
            note(this, if (currentDay == null && readIssue == null) "Loading readings…" else "No sleep recorded", 3)
            val state = band.state
            if (state.detailSyncAvailable && !state.detailSyncEnabled) {
                addButton(this, "Set up sleep sync", enabled = state.action == BandAction.IDLE, top = 12) {
                    detailsDialog(false)
                }
            }
            return@apply
        }
        addView(label(duration(night.asleepMinutes), 43, palette.brand, "sans-serif-medium").apply {
            letterSpacing = -.035f
        }, lp(-1, -2))
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
        shownRecords = 24
        currentDay = null
        week = emptyList()
        drawScreen()
        loadData()
    }

    private fun connectionStrip(state: BandState): View {
        val device = state.connecting ?: state.device
        val line = row().apply {
            minimumHeight = dp(76)
            setPadding(dp(14), dp(12), dp(15), dp(12))
            background = pressable(palette.tint, palette.brand, 17)
            isFocusable = true
            setOnClickListener { go(Tab.BAND) }
        }
        line.addView(Icon(this, Glyph.BAND, palette.brand), lp(dp(32), dp(32)))
        val words = column()
        words.addView(label(device?.name ?: "Connect a band", 17, palette.ink, "sans-serif-medium"))
        if (device != null) words.addView(label(connectionText(state), 13, palette.muted),
            lp(-1, -2).apply { topMargin = dp(3) })
        line.addView(words, lp(0, -2, 1f).apply { marginStart = dp(12) })
        val battery = state.battery
        if (battery != null) line.addView(label("$battery%", 17, palette.brand, "sans-serif-medium")
            .apply { contentDescription = "$battery percent battery" })
        line.addView(Icon(this, Glyph.RIGHT, palette.brand), lp(dp(16), dp(16)).apply { marginStart = dp(7) })
        return line
    }

    private fun bandPage(page: LinearLayout) {
        val state = band.state
        val current = state.connecting ?: state.device
        val connected = state.connection == Connection.CONNECTED
        val idle = state.action == BandAction.IDLE
        title(page, "Band")

        val devicePanel = panel().apply {
            background = shape(palette.tint, 18)
            setPadding(dp(17), dp(17), dp(17), dp(17))
        }
        val identity = row()
        identity.addView(Icon(this, Glyph.BAND, palette.brand), lp(dp(48), dp(48)))
        val words = column()
        words.addView(label(current?.name ?: "Connect your band", 23, palette.ink, "sans-serif-medium"))
        note(words, if (current == null) "Keep your HK50 or H59 nearby." else connectionText(state), 4)
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
        block(page, devicePanel, 20)

        heading(page, "Band tools", 24)
        val tools = panel()
        actionRow(tools, "Find band", Glyph.SCAN, connected && idle) { band.findBand() }
        actionRow(tools, "Wrist shutter", Glyph.CAMERA, true, action = ::openCamera)
        actionRow(tools, if (app.callAlertsRunning) "Turn off call alerts" else "Turn on call alerts",
            Glyph.PHONE, app.callAlertsRunning || state.device != null) { toggleCallAlerts() }
        if (connected) actionRow(tools, "Test call alert", Glyph.PHONE, idle) { band.testCallAlert() }
        app.callAlertsIssue?.let { note(tools, it, 7, true) }
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

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) go(Tab.CAMERA)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun permissionSettings(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun cameraScreen() {
        val screen = column().apply { setPadding(dp(20), dp(13), dp(20), dp(12)) }
        val bar = row()
        bar.addView(iconButton(Glyph.LEFT, "Back to band", true) { go(Tab.BAND) }, lp(dp(48), dp(48)))
        bar.addView(label("Wrist shutter", 24, palette.ink, "sans-serif-medium"), lp(0, -2, 1f))
        block(screen, bar, 0)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            background = shape(Color.BLACK, 15)
            clipToOutline = true
        }
        screen.addView(preview, lp(-1, 0, 1f).apply { topMargin = dp(8) })
        val status = label("", 14, palette.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val take = button("Take photo", filled = true) {}
        val retry = button("Retry wrist shutter", filled = false) {}
        block(screen, status, 10)
        block(screen, retry, 4)
        block(screen, take, 8)
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(-1, -1))
        camera = BandCamera(this, band, preview, status, take, retry).also { it.resume(resumed) }
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
        title(page, "Backup")
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
            BandAction.CAMERA -> "Wrist shutter"
            BandAction.IDLE -> "Connected"
        }
    }

    private fun metricPanel(metric: Metric, readings: List<Reading>, date: LocalDate, zone: ZoneId,
                            value: Long?, live: Boolean): LinearLayout {
        return panel().apply {
            val accent = palette.accent(metric)
            if (value == null) {
                note(this, if (currentDay == null && readIssue == null) "Loading readings…" else "No readings for this day", 3)
            } else {
                val numberRow = row().apply {
                    gravity = Gravity.BOTTOM
                    addView(label(number(value), 43, accent, "sans-serif-medium").apply { letterSpacing = -.035f })
                    if (metric.unit.isNotEmpty()) {
                        addView(label(metric.unit, 15, palette.muted), lp(-2, -2).apply {
                            marginStart = dp(6)
                            bottomMargin = dp(6)
                        })
                    }
                }
                addView(numberRow, lp(-1, -2))
                if (live) {
                    note(this, "Live reading", 4)
                } else if (!metric.isTotal && readings.isNotEmpty()) {
                    note(this, "Latest · ${TIME.format(Instant.ofEpochMilli(readings.last().time).atZone(zone))}", 4)
                }
            }
            if (readings.isNotEmpty()) {
                block(this, SeriesChart(this@MainActivity, palette, metric, date, zone, readings), 11, dp(142))
            }
        }
    }

    private fun title(page: LinearLayout, text: String, detail: String? = null) {
        page.addView(label(text, 44, palette.ink, "sans-serif-black").apply {
            letterSpacing = -.038f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        detail?.let { note(page, it, 4) }
    }

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
        background = shape(palette.surface, 16)
        setPadding(dp(15), dp(11), dp(15), dp(11))
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
        background = pressable(bg, fg, 12, if (!filled || !enabled) palette.border else null)
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
        private val DAILY_TABS = setOf(Tab.TODAY, Tab.HEALTH, Tab.ACTIVITY)
        private val HEALTH_METRICS = listOf(Metric.HEART, Metric.OXYGEN, Metric.HRV, Metric.STRESS)
        private val ACTIVITY_METRICS = listOf(Metric.STEPS, Metric.CALORIES, Metric.DISTANCE)
        private val LONG_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        private val SHORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        private val MONTH_DAY get() = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(
            java.util.Locale.getDefault(), "MMMd"
        ))
        private val WEEKDAY = DateTimeFormatter.ofPattern("EEEEE")
    }
}
