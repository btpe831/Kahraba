package com.kahraba.power

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Settings, in the same card style as the main screen. Built in code to keep it in one place. */
class SettingsActivity : ThemedActivity() {

    private val dp get() = resources.displayMetrics.density
    private lateinit var body: LinearLayout
    /** null for Settings itself; [PAGE_ADVANCED] or [PAGE_BACKUP] for those screens. */
    private var page: String? = null
    private lateinit var scroll: ScrollView
    /** A scroll position waiting to be restored after a theme change (kept if the screen is rebuilt again first). */
    private var pendingScrollY = 0

    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export(this).toByteArray()) } }
                .onSuccess { toast("Backup saved") }
                .onFailure { toast("Couldn't save the backup") }
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val json = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            val summary = json?.let { Backup.summary(it) }
            if (json == null || summary == null) {
                toast("That file isn't a Kahraba or Not backup")
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Use this backup?")
                    .setMessage("It has $summary.\n\n" +
                        "Combine: adds its switch log, checks, rooms and notes to what's here. Nothing here is removed, " +
                        "and your settings stay.\n\n" +
                        "Replace: everything in this app is replaced by the backup.")
                    .setPositiveButton("Combine") { _, _ -> combine(json) }
                    .setNeutralButton("Replace") { _, _ ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Replace everything?")
                            .setMessage("Everything currently in this app will be replaced by the backup.")
                            .setPositiveButton("Replace") { _, _ -> restore(json) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun combine(json: String) {
        if (!Backup.combine(this, json)) {
            toast("Couldn't combine that file")
            return
        }
        toast("Combined")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun restore(json: String) {
        if (!Backup.restore(this, json)) {
            toast("Couldn't restore that file")
            return
        }
        toast("Restored")
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
        // Start fresh so every screen shows the restored data.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Your area: a district from the list, then an optional town. */
    private fun pickArea() {
        val current = Area.district(this)
        val items = Area.DISTRICTS.map { if (it == current) "$it  ✓" else it } +
            (if (current.isNotEmpty()) listOf("Clear my area") else emptyList())
        MaterialAlertDialogBuilder(this)
            .setTitle("Your district")
            .setItems(items.toTypedArray()) { _, i ->
                if (i >= Area.DISTRICTS.size) {
                    Area.clear(this)
                    build()
                } else {
                    askTown(Area.DISTRICTS[i])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askTown(district: String) {
        val input = EditText(this).apply {
            hint = "Town or neighbourhood (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(Area.TOWN_MAX))
            if (district == Area.district(this@SettingsActivity)) setText(Area.town(this@SettingsActivity))
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(district)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Area.set(this, district, input.text.toString())
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getStringExtra(PAGE)
        supportActionBar?.hide()
        scroll = ScrollView(this).apply { setBackgroundColor(color(R.attr.appBg)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(40))
        }
        scroll.addView(body)
        setContentView(scroll)
        build()
        // Picking a theme rebuilds this screen in the new colours: stay where you were.
        pendingScrollY = savedInstanceState?.getInt(SCROLL_Y, 0) ?: 0
        if (pendingScrollY > 0) scroll.post {
            scroll.scrollTo(0, pendingScrollY)
            pendingScrollY = 0
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SCROLL_Y, if (pendingScrollY > 0) pendingScrollY else scroll.scrollY)
    }

    private fun build() {
        body.removeAllViews()

        // Header: title and Close.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = when (page) { PAGE_ADVANCED -> "Advanced"; PAGE_BACKUP -> "Backup"; PAGE_REMOTE -> "Other phones"; else -> "Settings" }
        header.addView(text(title, 32f, R.attr.appText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            cornerRadius = px(24)
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.attr.appBorder))
            setTextColor(color(R.attr.appText))
            setOnClickListener { finish() }
        })
        body.addView(header)
        // The version, right at the top so it's easy to find.
        if (page == null) {
            body.addView(text("Kahraba or Not ${Updates.version(this)}", 15f, R.attr.appMuted).apply { setPadding(px(2), 0, 0, 0) })
        }

        // The Advanced and Backup screens: just their own card.
        if (page == PAGE_ADVANCED) {
            body.addView(card {
                addView(text("You don't need to change these. The defaults work for most homes.", 14f, R.attr.appMuted))
                addView(numberRow("Flicker sensitivity", "%", Prefs.FLICKER_THRESHOLD, Prefs.DEFAULT_THRESHOLD,
                    "How much the light must flicker to count as moteur. A lower number catches moteur more easily but " +
                        "may give false alarms. It's set for you when you use “It's kahraba” / “It's moteur”."))
                addView(divider())
                addView(numberRow("Wait before saying kahraba", "min", Prefs.STEADY_MINUTES, 2.0,
                    "When kahraba comes back, the light gets brighter and steadier. The app waits this long to be " +
                        "sure before switching to kahraba."))
                addView(divider())
                addView(numberRow("Minimum brightness", "lux", Prefs.MIN_LUX, 100.0,
                    "If the light is dimmer than this, the app won't guess. Move the phone closer to a halogen light."))
                addView(divider())
                addView(toggle("Detect moteur without a power cut",
                    "Normally the app switches to moteur only when the power cuts out. Turn this on to also switch " +
                        "when the light keeps flickering. Can give false alarms, so it's off by default.",
                    Prefs.MOTEUR_ON_FLICKER, false))
            }, lp(top = 16))
            return
        }
        if (page == PAGE_REMOTE) {
            buildRemote()
            return
        }
        if (page == PAGE_BACKUP) {
            body.addView(card {
                addView(link("Back up to a file", "Saves your checks, rooms, notes, Always-on mode log and settings in one file.") {
                    launchSafely { saveBackup.launch("kahraba-or-not-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json") }
                })
                addView(divider())
                addView(link("Restore from a file", "Combine a backup with what's here, or replace everything with it. You choose after picking the file.") {
                    launchSafely { openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
                })
            })
            return
        }

        // SOUND
        section("Sound")
        body.addView(card {
            addView(text("Beep volume", 18f, R.attr.appText, bold = true))
            val now = Prefs.sp(this@SettingsActivity).getString(Prefs.BEEP_VOLUME, "full") ?: "full"
            addView(segmented(listOf("Low" to "low", "Medium" to "medium", "Full" to "full"), now) {
                Prefs.sp(this@SettingsActivity).edit().putString(Prefs.BEEP_VOLUME, it).apply()
            }, lp(top = 12))
            addView(text("Beeps play on the alarm channel, so they sound even when the phone is on silent.", 14f, R.attr.appMuted), lp(top = 10))
        })

        // QUICK CHECK
        section("Quick check")
        body.addView(card {
            addView(toggle("Check when the app opens", "Start a check straight after opening, so you only need to put the phone down.",
                Prefs.AUTO_CHECK, false))
        })

        // CARDS
        section("Cards")
        body.addView(card {
            addView(link("Arrange cards", "Show or hide cards. To move one, press and hold it on the main screen and drag.") {
                startActivity(Intent(this@SettingsActivity, CardsActivity::class.java))
            })
        })

        // ROOMS
        section("Rooms")
        body.addView(card {
            addView(link("Manage rooms", "Add, rename or delete rooms (e.g. Kitchen, Bathroom).") {
                RoomPicker.pick(this@SettingsActivity, Prefs.currentSpot(this@SettingsActivity)) { room ->
                    Prefs.setCurrentSpot(this@SettingsActivity, room)
                }
            })
        })

        // AREA
        section("Your area")
        body.addView(card {
            val area = Area.label(this@SettingsActivity)
            addView(link(area.ifEmpty { "Choose your area" },
                if (area.isEmpty()) "Optional. Pick your district, and a town if you like. Never uses GPS."
                else "Tap to change. Stays on this phone; added to feedback emails.") { pickArea() })
        })

        // MONITOR
        section("Always-on mode")
        body.addView(card {
            addView(toggle("No beeps at night", "Switches are still logged and shown, just without sound.", Prefs.QUIET_ENABLED, false))
            addView(divider())
            addView(timeRow("Quiet from", Prefs.QUIET_START, "23:00"))
            addView(divider())
            addView(timeRow("Quiet until", Prefs.QUIET_END, "07:00"))
        })
        body.addView(card {
            addView(text("Keep recordings for", 18f, R.attr.appText, bold = true))
            addView(segmented(listOf("1 day" to "1", "3 days" to "3", "7 days" to "7", "30 days" to "30"),
                Prefs.keepDays(this@SettingsActivity).toString()) {
                Prefs.sp(this@SettingsActivity).edit().putString(Prefs.KEEP_DAYS, it).apply()
                DataLogger.prune(this@SettingsActivity)
            }, lp(top = 12))
            addView(text("Always-on mode saves its light readings, about 5 MB a day, for the graph and for sharing. " +
                "Older ones are deleted. The switch log and kahraba pattern are kept either way.", 14f, R.attr.appMuted), lp(top = 10))
        }, lp(top = 12))
        body.addView(card {
            addView(link("Advanced", "Flicker sensitivity, waiting time and minimum brightness. The defaults work for most homes.") {
                startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra(PAGE, PAGE_ADVANCED))
            })
        }, lp(top = 12))

        // ALERTS ON OTHER PHONES
        section("Alerts on other phones")
        body.addView(card {
            val getting = RemoteAlerts.getPairing(this@SettingsActivity) != null
            addView(link("Alerts on other phones",
                if (getting) "This phone gets alerts from your other phone." else "Get Always-on mode's alerts on your other Android phones.") {
                openRemote(this@SettingsActivity)
            })
        })

        // BACKUP
        section("Backup")
        body.addView(card {
            addView(link("Back up or restore", "Keep a copy of your checks, rooms and settings, or move them to another phone.") {
                startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra(PAGE, PAGE_BACKUP))
            })
        })

        // TOOLS
        section("Tools")
        body.addView(card {
            addView(link("Always-on mode", "For a spare phone left under a light: tracks every switch.") {
                startActivity(Intent(this@SettingsActivity, MonitorActivity::class.java))
            })
            addView(divider())
            addView(link("Amps calculator", "Watts to amps, and what fits in your moteur subscription.") {
                startActivity(Intent(this@SettingsActivity, AmpsActivity::class.java))
            })
            addView(divider())
            addView(link("Light meter", "Live lux, a chart, and recordings you can save as CSV.") {
                startActivity(Intent(this@SettingsActivity, LightMeterActivity::class.java))
            })
        })

        // ABOUT
        section("About")
        body.addView(card {
            addView(link("Check for updates", "You have version ${Updates.version(this@SettingsActivity)}. Opens the download page in your browser.") {
                Updates.open(this@SettingsActivity)
            })
            addView(divider())
            addView(link("How to use the app", "Which bulbs work, distance, rooms, solar and UPS, and the disclaimer.") {
                startActivity(Intent(this@SettingsActivity, HelpActivity::class.java))
            })
            addView(divider())
            addView(link("Getting started guide", "See the welcome screens and main-screen tips again.") {
                Tour.replay(this@SettingsActivity)
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            })
            if (Feedback.enabled) {
                addView(divider())
                addView(link("Send feedback", "Tell us what worked and what didn't. Opens your email app.") {
                    Feedback.open(this@SettingsActivity)
                })
            }
            addView(divider())
            addView(text("Privacy", 17f, R.attr.appText, bold = true), lp(top = 12))
            addView(text("Everything stays on this phone, except the alerts you send to your other phones: they go encrypted, " +
                "through the free ntfy.sh service. Nothing else uses the internet; data only leaves if you tap Share or Send feedback.",
                14f, R.attr.appMuted), lp(top = 4))
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            addView(text("Version $version · Created by Aubrey Arenas\n© 2026 Aubrey Arenas. All rights reserved.", 14f, R.attr.appMuted), lp(top = 14))
        })

        // APPEARANCE AND THEME
        section("Appearance")
        body.addView(card {
            addView(segmented(listOf("Auto" to "auto", "Light" to "light", "Dark" to "dark"), Prefs.appearance(this@SettingsActivity)) { value ->
                if (value != Prefs.appearance(this@SettingsActivity)) {
                    Prefs.setAppearance(this@SettingsActivity, value)
                    AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this@SettingsActivity))
                    recreate()
                }
            })
            addView(text("Auto follows your phone's dark mode. Every theme has a light and a dark version.",
                14f, R.attr.appMuted), lp(top = 10))
        })

        section("Text size")
        body.addView(card {
            addView(segmented(listOf("Smaller" to "small", "Default" to "default", "Larger" to "large"), Prefs.textSize(this@SettingsActivity)) { value ->
                if (value != Prefs.textSize(this@SettingsActivity)) {
                    Prefs.setTextSize(this@SettingsActivity, value)
                    recreate()
                }
            })
            addView(text("Changes the text in this app only. Your phone's own text size (Settings › Display) still applies.",
                14f, R.attr.appMuted), lp(top = 10))
        })

        section("Theme")
        val current = Prefs.colorTheme(this)
        for (t in Themes.all) body.addView(themeRow(t, t.key == current), lp(top = 10))
        body.addView(text(
            "Your theme colours the whole app. Green always means kahraba and amber always means moteur, in every theme.",
            14f, R.attr.appMuted,
        ).apply { setPadding(px(4), px(14), px(4), 0) })
    }

    // ---- Alerts on other phones ----

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun buildRemote() {
        val ctx = this
        val getting = RemoteAlerts.getPairing(ctx)

        // Getting alerts here.
        section("Get alerts on this phone")
        body.addView(card {
            if (getting == null) {
                addView(text("Get the alerts from a phone running Always-on mode, with the same beeps. " +
                    "On that phone, open this page and tap Add a phone, then:", 15f, R.attr.appMuted))
                addView(link("Scan QR code", "Point this phone's camera at the code on the other phone.") { scanCode() }, lp(top = 8))
                addView(divider())
                addView(link("Paste code", "If the code was sent to you in a message: copy it, then paste it here.") { pasteCode() })
            } else {
                addView(text(RemoteAlerts.statusLine(ctx), 16f, R.attr.appText, bold = true))
                addView(remoteSwitch("Beep on this phone", "Off: notifications only, no beeps. Quiet hours apply too.",
                    RemoteAlerts.beepHere(ctx)) { RemoteAlerts.setBeepHere(ctx, it) }, lp(top = 8))
                if (!ignoringBattery()) {
                    addView(divider())
                    addView(link("Allow alerts in the background", "Needed so alerts arrive with the screen off.") { askBattery() })
                }
                addView(divider())
                addView(link("Stop getting alerts", "This phone stops listening. Scan the code again to start.") {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Stop getting alerts?")
                        .setPositiveButton("Stop") { _, _ ->
                            RemoteAlerts.setGetPairing(ctx, null)
                            AlertsService.stop(ctx)
                            build()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }
        }, lp(top = 4))

        // Sending from here.
        section("Send from this phone")
        body.addView(card {
            addView(remoteSwitch("Send alerts to my other phones",
                "While Always-on mode runs here, every alert also goes to the phones you add, encrypted.",
                RemoteAlerts.sendOn(ctx)) { RemoteAlerts.setSendOn(ctx, it) })
            addView(divider())
            addView(link("Name of this phone: ${RemoteAlerts.deviceName(ctx)}",
                "What your other phones call it, e.g. “From ${RemoteAlerts.deviceName(ctx)} · 2 min ago”. Tap to change.") { nameThisPhone() })
            addView(divider())
            addView(link("Add a phone", "Shows a code to scan with the other phone. Every phone you add uses the same code.") { showCode() })
            if (RemoteAlerts.sendPairing(ctx) != null) {
                addView(divider())
                addView(link("Remove all phones", "Makes a new code. Phones added before stop getting alerts until they scan it.") {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Remove all phones?")
                        .setMessage("Phones that scanned the old code stop getting alerts. You can add them again with the new code.")
                        .setPositiveButton("Remove") { _, _ ->
                            RemoteAlerts.newSendPairing(ctx)
                            toast("New code ready")
                            build()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }
            if (RemoteAlerts.waiting()) addView(text("Some alerts are waiting for the internet. They go as soon as it's back.",
                14f, R.attr.appMuted), lp(top = 8))
        })
        body.addView(text("Alerts travel through ntfy.sh, a free service, encrypted with a key that's only in your code: " +
            "ntfy.sh can't read them. Android phones only for now.", 14f, R.attr.appMuted).apply {
            setPadding(px(4), px(14), px(4), 0)
        })
    }

    private fun nameThisPhone() {
        val input = EditText(this).apply {
            hint = RemoteAlerts.systemName(this@SettingsActivity)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(RemoteAlerts.NAME_MAX))
            setText(RemoteAlerts.deviceName(this@SettingsActivity))
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Name of this phone")
            .setMessage("For example Kitchen phone or Bathroom spare.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> renamed(input.text.toString()) }
            .setNeutralButton("Use the phone's name") { _, _ -> renamed("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Saves the name and, while Always-on mode runs, tells the other phones straight away. */
    private fun renamed(name: String) {
        RemoteAlerts.setCustomName(this, if (name.trim() == RemoteAlerts.systemName(this)) "" else name)
        if (MonitorState.running) {
            RemoteAlerts.send(this, RemoteCodec.Message(RemoteCodec.Kind.ALIVE, MonitorState.source, MonitorState.sinceMs,
                System.currentTimeMillis(), RemoteAlerts.deviceName(this), Prefs.monitorRoom(this)))
        }
        build()
    }

    private fun showCode() {
        val p = RemoteAlerts.sendPairingOrNew(this)
        // The new phone gets this phone's whole switch log straight away (then the last 7 days every 6 hours).
        RemoteAlerts.sendHistory(this, all = true)
        val size = px(240)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(8), px(20), 0)
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(qr(p.code, size))
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(px(8), px(8), px(8), px(8))
        }, LinearLayout.LayoutParams(size + px(16), size + px(16)))
        box.addView(text("On the other phone: open the app › Menu › Alerts on other phones › Scan QR code.",
            15f, R.attr.appMuted).apply { gravity = Gravity.CENTER }, lp(top = 12))
        MaterialAlertDialogBuilder(this)
            .setTitle("Add a phone")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> build() }
            .setNeutralButton("Send the code") { _, _ ->
                val msg = "To get my power alerts on your phone: install Kahraba or Not, then open Menu › Alerts on other phones › " +
                    "Paste code. Copy this whole message first.\n\n${p.code}"
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, msg), "Send the code"))
                }
                build()
            }
            .show()
    }

    private fun qr(text: String, size: Int): android.graphics.Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i ->
            if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun scanCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        runCatching {
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { pairWith(it.rawValue ?: "") }
                .addOnFailureListener { toast("Couldn't open the scanner. Try Paste code instead.") }
        }.onFailure { toast("Couldn't open the scanner. Try Paste code instead.") }
    }

    private fun pasteCode() {
        val clip = runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: ""
        val input = EditText(this).apply {
            hint = "Paste the code or the whole message"
            if (RemoteCodec.parse(clip) != null) setText(clip)
        }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter code")
            .setView(box)
            .setPositiveButton("Connect") { _, _ -> pairWith(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairWith(text: String) {
        val p = RemoteCodec.parse(text)
        when {
            p == null -> toast("That isn't an alerts code. On the other phone, tap Add a phone.")
            p.topic == RemoteAlerts.sendPairing(this)?.topic -> toast("That's this phone's own code. Scan it with your other phone.")
            else -> {
                RemoteAlerts.setGetPairing(this, p)
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                AlertsService.start(this, reconnect = true)
                toast("Connected. News from the other phone arrives within 15 minutes.")
                if (!ignoringBattery()) askBattery()
                build()
            }
        }
    }

    private fun ignoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun remoteSwitch(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.attr.appText, bold = true))
        texts.addView(text(sub, 14f, R.attr.appMuted), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12) })
        row.addView(MaterialSwitch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    // ---- Theme rows ----

    private fun themeRow(t: Themes.Theme, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(16), px(18), px(16))
            background = rounded(color(if (selected) R.attr.appSurfaceAlt else R.attr.appSurface),
                color(if (selected) R.attr.appAccent else R.attr.appBorder), 22f)
            setOnClickListener {
                if (!selected) {
                    Prefs.setColorTheme(this@SettingsActivity, t.key)
                    AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this@SettingsActivity))
                    recreate()
                }
            }
        }
        // Swatch: the light and dark versions side by side, each its background with its accent dot.
        val swatch = LinearLayout(this).apply {
            background = rounded(color(R.attr.appSurface), color(R.attr.appBorder), 14f)
            clipToOutline = true
        }
        for (v in listOf(t.light, t.dark)) {
            val half = FrameLayout(this).apply { setBackgroundColor(v.bg) }
            half.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(v.dot) }
            }, FrameLayout.LayoutParams(px(12), px(12), Gravity.CENTER))
            swatch.addView(half, LinearLayout.LayoutParams(0, -1, 1f))
        }
        row.addView(swatch, LinearLayout.LayoutParams(px(54), px(54)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(t.name, 20f, R.attr.appText, bold = true))
        texts.addView(text(t.description, 15f, R.attr.appMuted))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(16) })

        // Radio: an empty ring, or a filled accent circle with a tick.
        val radio = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            if (selected) {
                text = "✓"
                setTextColor(color(R.attr.appOnAccent))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.attr.appAccent)) }
            } else {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(2), color(R.attr.appMuted)) }
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(px(28), px(28)))
        return row
    }

    // ---- Building blocks ----

    private fun section(title: String) {
        body.addView(text(title.uppercase(), 15f, R.attr.appAccent, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(px(4), px(30), 0, px(4))
        })
    }

    private fun card(fill: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(18), px(20), px(18))
        background = rounded(color(R.attr.appSurface), color(R.attr.appBorder), 22f)
        layoutParams = lp(top = 10)
        fill()
    }

    /** Pill-shaped choice, like "Low | Medium | Full". */
    private fun segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val bar = LinearLayout(this).apply {
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(color(R.attr.appSurfaceAlt), color(R.attr.appBorder), 28f)
        }
        fun paint() {
            val chosen = bar.tag as String
            for (i in 0 until bar.childCount) {
                val tv = bar.getChildAt(i) as TextView
                val on = options[i].second == chosen
                tv.setTextColor(color(if (on) R.attr.appOnAccent else R.attr.appMuted))
                tv.background = if (on) rounded(color(R.attr.appAccent), color(R.attr.appAccent), 24f) else null
            }
        }
        bar.tag = selected
        for ((label, value) in options) {
            bar.addView(text(label, 17f, R.attr.appText, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(12), 0, px(12))
                setOnClickListener {
                    bar.tag = value
                    paint()
                    onPick(value)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        return bar
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.attr.appText, bold = true))
        texts.addView(text(sub, 14f, R.attr.appMuted), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12) })
        row.addView(MaterialSwitch(this).apply {
            isChecked = Prefs.sp(this@SettingsActivity).getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> Prefs.sp(this@SettingsActivity).edit().putBoolean(key, on).apply() }
        })
        return row
    }

        private fun link(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(6))
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.attr.appText, bold = true))
        texts.addView(text(sub, 14f, R.attr.appMuted), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(color(R.attr.appAccent))
        }, LinearLayout.LayoutParams(px(24), px(24)))
        return row
    }

    private fun timeRow(title: String, key: String, def: String): View {
        val value = text(Prefs.sp(this).getString(key, def) ?: def, 18f, R.attr.appAccent, bold = true)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(12))
            setOnClickListener {
                val (h, m) = (Prefs.sp(this@SettingsActivity).getString(key, def) ?: def).split(":").map { it.toIntOrNull() ?: 0 }
                TimePickerDialog(this@SettingsActivity, { _, hh, mm ->
                    val v = "%02d:%02d".format(hh, mm)
                    Prefs.sp(this@SettingsActivity).edit().putString(key, v).apply()
                    value.text = v
                }, h, m, true).show()
            }
        }
        row.addView(text(title, 17f, R.attr.appText), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        return row
    }

    private fun numberRow(title: String, unit: String, key: String, def: Double, help: String): View {
        fun current() = Prefs.sp(this).getString(key, null)?.replace(',', '.')?.toDoubleOrNull() ?: def
        fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)
        val value = text("${fmt(current())} $unit", 17f, R.attr.appAccent, bold = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12), 0, px(12))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 17f, R.attr.appText), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(value)
        row.addView(top)
        row.addView(text(help, 13f, R.attr.appMuted), lp(top = 2))
        row.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(current()))
                setSelection(text.length)
            }
            val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                    if (v != null && v > 0) {
                        Prefs.sp(this).edit().putString(key, v.toString()).apply()
                        value.text = "${fmt(v)} $unit"
                    }
                }
                .setNeutralButton("Default") { _, _ ->
                    Prefs.sp(this).edit().remove(key).apply()
                    value.text = "${fmt(def)} $unit"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return row
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.attr.appOutline))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1))
    }

    private fun text(s: String, size: Float, colorAttr: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        // Settings text runs 2 points smaller than the sizes given, to keep this screen compact.
        textSize = size - 2f
        setTextColor(color(colorAttr))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        setStroke(px(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = px(top) }

    private fun px(v: Int) = (v * dp).toInt()

    private fun color(attr: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(attr, v, true)
        return v.data
    }

    companion object {
        private const val SCROLL_Y = "scroll_y"
        private const val PAGE = "page"
        private const val PAGE_ADVANCED = "advanced"
        private const val PAGE_BACKUP = "backup"
        private const val PAGE_REMOTE = "remote"

        fun openRemote(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java).putExtra(PAGE, PAGE_REMOTE))
        }
    }
}
