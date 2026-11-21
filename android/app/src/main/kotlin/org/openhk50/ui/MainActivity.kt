package org.openhk50.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import org.openhk50.OpenHkApp
import org.openhk50.band.BandAction
import org.openhk50.band.BandClient
import org.openhk50.band.BandState
import org.openhk50.band.Connection
import org.openhk50.data.HealthStore
import org.openhk50.data.Metric
import org.openhk50.data.Origin
import org.openhk50.data.Reading
import org.openhk50.sync.BackupJob
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : Activity() {
    private enum class Tab(val title: String, val icon: Glyph) {
        TODAY("Today", Glyph.PULSE), HISTORY("History", Glyph.CALENDAR),
        BAND("Band", Glyph.BAND), BACKUP("Backup", Glyph.BACKUP)
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
    private var historyMetric = Metric.HEART
    private var historyDate = LocalDate.now()
    private var shownRecords = 24
    private var currentDay: HealthStore.Day? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        palette = Palette.of(night)
        expanded = resources.configuration.screenWidthDp >= 680
        tab = savedInstanceState?.getString("tab")?.let(Tab::valueOf) ?: Tab.TODAY
        historyDate = savedInstanceState?.getString("day")?.let(LocalDate::parse) ?: LocalDate.now(band.zone)
        historyMetric = savedInstanceState?.getString("metric")?.let(Metric::valueOf) ?: Metric.HEART
        permissionAction = savedInstanceState?.getString("permission")?.let(PermissionAction::valueOf)
        buildShell()
        setupWindow()
        drawScreen()
    }

    override fun onStart() {
        super.onStart()
        active = true
        app.changed = { loadData() }
        band.changed = {
            val device = band.state.device?.address
            if (lastDevice != device) {
                lastDevice = device
                currentDay = null
                loadData()
            }
            if (tab == Tab.TODAY && lastShownDate != LocalDate.now(band.zone)) {
                currentDay = null
                loadData()
            }
            if (tab == Tab.TODAY || tab == Tab.BAND) drawScreen(keepScroll = true)
        }
        band.foreground(true)
        if (app.settings.state().endpoint != null) BackupJob.schedule(this)
        loadData()
    }

    override fun onStop() {
        super.onStop()
        active = false
        app.changed = null
        band.changed = null
        if (!isChangingConfigurations && permissionAction == null) band.foreground(false)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab.name)
        outState.putString("day", historyDate.toString())
        outState.putString("metric", historyMetric.name)
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
                (if (palette.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
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

        val bar = row().apply { setPadding(dp(24), 0, dp(24), 0) }
        val mark = FrameLayout(this).apply { background = shape(palette.brand, 10) }
        mark.addView(Icon(this, Glyph.PULSE, palette.onBrand), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        bar.addView(mark, lp(dp(34), dp(34)))
        bar.addView(label("OpenHK50", 19, palette.ink, "sans-serif-medium"), lp(-2, -2).apply { marginStart = dp(11) })
        root.addView(bar, lp(-1, dp(61)))

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
        navigation.setPadding(dp(8), dp(5), dp(8), dp(5))
        for (item in Tab.entries) {
            val selected = item == tab
            val color = if (selected) palette.brand else palette.muted
            val cell = column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(if (expanded) 74 else 61)
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
            cell.addView(pill, lp(dp(54), dp(30)))
            cell.addView(label(item.title, 12, color, if (selected) "sans-serif-medium" else "sans-serif")
                .apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(-2, -2))
            navigation.addView(cell, if (expanded) lp(-1, -2) else lp(0, -2, 1f))
        }
    }

    private fun go(next: Tab) {
        if (tab == next) return
        tab = next
        queryGeneration++
        backupMessage = null
        if (next == Tab.HISTORY) historyDate = LocalDate.now(band.zone)
        if (next == Tab.HISTORY || next == Tab.TODAY) currentDay = null
        drawNavigation()
        updateBack()
        drawScreen()
        loadData()
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
            if (tab != Tab.TODAY) {
                val callback = OnBackInvokedCallback { go(Tab.TODAY) }
                backCallback = callback
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Required on Android 12 and earlier")
    override fun onBackPressed() {
        if (tab == Tab.TODAY) super.onBackPressed() else go(Tab.TODAY)
    }

    private fun loadData() {
        if (!active || tab == Tab.BAND) return
        val generation = ++queryGeneration
        val address = band.state.device?.address
        val zone = band.zone
        val isBackup = tab == Tab.BACKUP
        val date = if (tab == Tab.HISTORY) historyDate else LocalDate.now(zone)
        app.databaseThread.execute {
            val result = runCatching {
                if (isBackup) null to app.health.pendingCount() else app.health.day(address, date, zone) to 0L
            }
            app.main.post {
                if (active && generation == queryGeneration) {
                    result.onSuccess { (day, count) ->
                        currentDay = day
                        pendingCount = count
                        readIssue = null
                    }.onFailure { readIssue = "Couldn’t open your records. Reopen the app to try again." }
                    drawScreen(keepScroll = true)
                }
            }
        }
    }

    private fun drawScreen(keepScroll: Boolean = false) {
        val previous = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val page = column().apply { setPadding(dp(22), dp(9), dp(22), dp(22)) }
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
            Tab.HISTORY -> history(page)
            Tab.BAND -> bandPage(page)
            Tab.BACKUP -> backupPage(page)
        }
        if (previous > 0) viewport.post { viewport.scrollTo(0, previous) }
    }

    private fun today(page: LinearLayout) {
        val state = band.state
        val zone = band.zone
        val today = LocalDate.now(zone)
        lastShownDate = today
        title(page, "Today", today.format(LONG_DATE))
        block(page, connectionStrip(state), top = 20)
        app.storageIssue?.let { note(page, it, 12, true) }
        readIssue?.let { note(page, it, 12, true) }

        val day = currentDay
        val samples = day?.heart.orEmpty()
        val steps = day?.steps.orEmpty()
        val last = day?.latestHeart
        val heart = metricPanel(
            Metric.HEART, samples, today, zone,
            state.liveHeart?.takeIf { state.action == BandAction.MEASURING }?.toLong() ?: samples.lastOrNull()?.value?.toLong()
        )
        if (samples.isEmpty() && last != null && state.action != BandAction.MEASURING) {
            note(heart, "Last reading ${last.value} bpm · ${dateTime(last.time, zone)}", 2)
        }
        val measuring = state.action == BandAction.MEASURING
        addButton(heart, if (measuring) "Stop measurement" else "Measure heart rate",
            enabled = measuring || state.connection == Connection.CONNECTED && state.action == BandAction.IDLE,
            top = 12, filled = false) {
            if (measuring) band.stopMeasurement() else band.measure()
        }
        if (state.action != BandAction.IMPORTING) state.message?.let { note(heart, it, 7) }
        block(page, heart, 14)

        block(page, metricPanel(Metric.STEPS, steps, today, zone,
            day?.stepTotal?.takeIf { steps.isNotEmpty() }), 12)
    }

    private fun history(page: LinearLayout) {
        title(page, "History")
        val zone = band.zone
        val day = currentDay
        val today = LocalDate.now(zone)
        val dateRow = row().apply { background = shape(palette.surface, 15, palette.border); setPadding(dp(5), 0, dp(5), 0) }
        dateRow.addView(iconButton(Glyph.LEFT, "Previous day", day?.earlier == true) { selectDate(historyDate.minusDays(1)) }, lp(dp(48), dp(52)))
        val date = label(historyDate.format(SHORT_DATE), 15, palette.ink, "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "Choose date, ${historyDate.format(LONG_DATE)}"
            background = pressable(Color.TRANSPARENT, palette.ink, 12)
            isFocusable = true
            setOnClickListener {
                DatePickerDialog(this@MainActivity, { _, y, m, d -> selectDate(LocalDate.of(y, m + 1, d)) },
                    historyDate.year, historyDate.monthValue - 1, historyDate.dayOfMonth
                ).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    show()
                }
            }
        }
        dateRow.addView(date, lp(0, -2, 1f))
        dateRow.addView(iconButton(Glyph.RIGHT, "Next day", historyDate < today) { selectDate(historyDate.plusDays(1)) }, lp(dp(48), dp(52)))
        block(page, dateRow, 18)

        val selectors = row()
        for (metric in Metric.entries) {
            val current = metric == historyMetric
            val button = button(metricTitle(metric), filled = current) {
                historyMetric = metric
                shownRecords = 24
                drawScreen()
            }
            button.isSelected = current
            selectors.addView(button, lp(0, -2, 1f).apply { if (metric == Metric.STEPS) marginStart = dp(8) })
        }
        block(page, selectors, 12)
        readIssue?.let { note(page, it, 12, true) }

        val values = if (historyMetric == Metric.HEART) day?.heart.orEmpty() else day?.steps.orEmpty()
        val value = if (historyMetric == Metric.HEART) values.lastOrNull()?.value?.toLong()
            else day?.stepTotal?.takeIf { values.isNotEmpty() }
        block(page, metricPanel(historyMetric, values, historyDate, zone, value), 14)
        if (values.isEmpty()) return

        val header = label("Readings", 21, palette.ink, "sans-serif-medium").apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        block(page, header, 23)
        val records = panel()
        for (record in values.asReversed().take(shownRecords)) {
            if (records.childCount > 0) records.addView(View(this).apply { setBackgroundColor(palette.border) }, lp(-1, dp(1)))
            val line = row().apply { minimumHeight = dp(46) }
            val timestamp = TIME.format(Instant.ofEpochMilli(record.time).atZone(zone))
            val detail = if (record.origin == Origin.LIVE) "$timestamp · Spot check" else timestamp
            line.addView(label(detail, 14, palette.muted), lp(0, -2, 1f))
            val unit = if (record.metric == Metric.HEART) "bpm" else "steps"
            line.addView(label("${number(record.value.toLong())} $unit", 15, palette.ink, "sans-serif-medium"))
            records.addView(line, lp(-1, -2))
        }
        block(page, records, 10)
        if (shownRecords < values.size) addButton(page, "Show more readings", filled = false, top = 12) {
            shownRecords += 24
            drawScreen(keepScroll = true)
        }
    }

    private fun selectDate(day: LocalDate) {
        historyDate = day.coerceAtMost(LocalDate.now(band.zone))
        shownRecords = 24
        currentDay = null
        drawScreen()
        loadData()
    }

    private fun connectionStrip(state: BandState): View {
        val line = row().apply {
            minimumHeight = dp(68)
            setPadding(dp(12), dp(9), dp(13), dp(9))
            background = pressable(palette.tint, palette.ink, 15)
            isFocusable = true
            setOnClickListener { go(Tab.BAND) }
        }
        val mark = FrameLayout(this).apply { background = shape(palette.surface, 11) }
        mark.addView(Icon(this, Glyph.BAND, palette.brand), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        line.addView(mark, lp(dp(40), dp(40)))
        val words = column()
        words.addView(label(state.device?.name ?: "Pair your band", 15, palette.ink, "sans-serif-medium"))
        words.addView(label(if (state.device == null) "Bluetooth setup" else connectionText(state), 13, palette.muted))
        line.addView(words, lp(0, -2, 1f).apply { marginStart = dp(11) })
        if (state.battery != null) line.addView(label("${state.battery}%", 15, palette.brand, "sans-serif-medium")
            .apply { contentDescription = "${state.battery} percent battery" })
        else line.addView(Icon(this, Glyph.RIGHT, palette.brand), lp(dp(20), dp(20)))
        return line
    }

    private fun bandPage(page: LinearLayout) {
        val state = band.state
        title(page, "Band")
        val devicePanel = panel()
        section(devicePanel, Glyph.BLUETOOTH, state.device?.name ?: "Connect your HK50", palette.brand)
        note(devicePanel, if (state.device == null) "Keep your band nearby and scan for it." else connectionText(state), 7)
        state.battery?.let {
            devicePanel.addView(row().apply {
                addView(Icon(this@MainActivity, Glyph.BATTERY, palette.brand), lp(dp(22), dp(22)))
                addView(label(if (state.charging) "$it% · Charging" else "$it% battery", 16, palette.ink, "sans-serif-medium"),
                    lp(-2, -2).apply { marginStart = dp(9) })
            }, lp(-1, -2).apply { topMargin = dp(10) })
        }
        if (state.device != null && state.connection == Connection.DISCONNECTED) {
            addButton(devicePanel, "Reconnect", top = 12) { bluetooth(PermissionAction.RECONNECT) }
        } else if (state.connection == Connection.CONNECTING) {
            addButton(devicePanel, "Cancel connection", filled = false, top = 12) { band.disconnect() }
        }
        if (state.action == BandAction.IMPORTING) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = state.days.coerceAtLeast(1)
                this.progress = state.progress
                progressTintList = ColorStateList.valueOf(palette.brand)
                progressBackgroundTintList = ColorStateList.valueOf(palette.border)
                contentDescription = "Importing day ${state.progress} of ${state.days}"
            }
            block(devicePanel, progress, 8, dp(7))
        }
        state.message?.let { note(devicePanel, it, 9) }
        block(page, devicePanel, 18)

        if (state.connection == Connection.CONNECTED) {
            val actions = panel()
            section(actions, Glyph.CLOCK, "Band controls", palette.brand)
            val enabled = state.action == BandAction.IDLE
            addButton(actions, "Import recent history", enabled, top = 12) { band.importHistory(true) }
            addButton(actions, "Set band clock", enabled, filled = false, top = 8) { band.setClock() }
            addButton(actions, "Heart-rate logging", enabled, filled = false, top = 8) {
                AlertDialog.Builder(this).setTitle("Heart-rate logging")
                    .setItems(arrayOf("Record every 5 minutes", "Pause logging")) { _, which -> band.setLogging(which == 0) }
                    .setNegativeButton("Cancel", null).show()
            }
            addButton(actions, "Disconnect", enabled, filled = false, top = 8) { band.disconnect() }
            block(page, actions, 12)
        }

        val nearby = panel()
        section(nearby, Glyph.BAND, "Nearby bands", palette.brand)
        val scanAllowed = state.action == BandAction.IDLE && state.connection != Connection.CONNECTING
        addButton(nearby, if (state.scanning) "Scanning…" else "Scan for bands",
            enabled = scanAllowed && !state.scanning, filled = state.device == null, top = 12) {
            bluetooth(PermissionAction.SCAN)
        }
        if (state.searched && !state.scanning && state.nearby.isEmpty() && state.message == null) {
            note(nearby, "No bands found. Close any other band app and scan again.", 8)
        }
        for (candidate in state.nearby) {
            if (candidate.address == state.device?.address && state.connection == Connection.CONNECTED) continue
            val item = button(candidate.name, filled = false, enabled = scanAllowed) { band.connect(candidate) }.apply {
                contentDescription = "${candidate.name}, address ${candidate.address}"
            }
            block(nearby, item, 8)
        }
        block(page, nearby, 12)
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
            requestPermissions(BandClient.permissions(), BLUETOOTH_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLUETOOTH_REQUEST) return
        val action = permissionAction
        permissionAction = null
        if (action != null && BandClient.hasPermissions(this)) {
            startBluetoothAction(action)
        } else {
            AlertDialog.Builder(this).setTitle("Bluetooth access")
                .setMessage("Allow Bluetooth access in Android settings to find and connect to your band.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }.setNegativeButton("Cancel", null).show()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBluetoothAction(action: PermissionAction) {
        val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        if (manager?.adapter?.isEnabled == false) {
            permissionAction = action
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH_REQUEST)
            return
        }
        if (action == PermissionAction.SCAN) band.scan() else band.reconnect()
    }

    @Deprecated("Activity result for the platform Bluetooth enable prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ENABLE_BLUETOOTH_REQUEST) return
        val action = permissionAction
        permissionAction = null
        if (resultCode == RESULT_OK && action != null && BandClient.hasPermissions(this)) startBluetoothAction(action)
    }

    private fun connectionText(state: BandState) = when (state.connection) {
        Connection.DISCONNECTED -> "Disconnected"
        Connection.CONNECTING -> "Connecting…"
        Connection.CONNECTED -> when (state.action) {
            BandAction.IMPORTING -> "Importing from band…"
            BandAction.MEASURING -> "Measuring heart rate…"
            BandAction.SETTING -> "Updating band…"
            BandAction.IDLE -> "Connected"
        }
    }

    private fun metricPanel(metric: Metric, readings: List<Reading>, date: LocalDate, zone: ZoneId, value: Long?): LinearLayout {
        val heart = metric == Metric.HEART
        val accent = if (heart) palette.heart else palette.steps
        return panel().apply {
            section(this, if (heart) Glyph.PULSE else Glyph.WALK, metricTitle(metric), accent)
            if (value == null) {
                note(this, if (currentDay == null && readIssue == null) "Loading readings…" else
                    if (heart) "No heart-rate readings" else "No step readings", 12)
            } else {
                val numberRow = row().apply {
                    gravity = Gravity.BOTTOM
                    addView(label(number(value), 39, palette.ink, "sans-serif-light").apply { letterSpacing = -.025f })
                    addView(label(if (heart) "bpm" else "steps", 15, palette.muted), lp(-2, -2).apply {
                        marginStart = dp(7)
                        bottomMargin = dp(7)
                    })
                }
                block(this, numberRow, 8)
            }
            if (readings.isNotEmpty()) {
                block(this, SeriesChart(this@MainActivity, palette, metric, date, zone, readings), 6, dp(114))
            }
        }
    }

    private fun title(page: LinearLayout, text: String, detail: String? = null) {
        page.addView(label(text, 40, palette.ink, "sans-serif-medium").apply {
            letterSpacing = -.034f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        detail?.let { note(page, it, 2) }
    }

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
        background = shape(palette.surface, 16, palette.border)
        setPadding(dp(15), dp(14), dp(15), dp(14))
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
        includeFontPadding = false
    }

    private fun block(parent: LinearLayout, child: View, top: Int, height: Int = -2) =
        parent.addView(child, lp(-1, height).apply { topMargin = dp(top) })

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)
    private fun metricTitle(metric: Metric) = if (metric == Metric.HEART) "Heart rate" else "Steps"
    private fun number(value: Long) = NumberFormat.getIntegerInstance().format(value)
    private fun dateTime(time: Long, zone: ZoneId) = DATE_TIME.format(Instant.ofEpochMilli(time).atZone(zone))

    companion object {
        private const val BLUETOOTH_REQUEST = 31
        private const val ENABLE_BLUETOOTH_REQUEST = 32
        private val LONG_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        private val SHORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}
