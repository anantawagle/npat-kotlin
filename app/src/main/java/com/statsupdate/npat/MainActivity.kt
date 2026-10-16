package com.statsupdate.npat

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("npat", MODE_PRIVATE) }
    private val firebase = FirebaseRooms()
    @Volatile private var offline = false
    private val api: RoomStore get() = if (offline) checkNotNull(BluetoothSession.store) { "Host or join a Bluetooth table first." } else firebase
    private val bluetooth: BluetoothAdapter? get() = getSystemService(BluetoothManager::class.java)?.adapter
    private var bluetoothAction: (() -> Unit)? = null
    private var discoveryRegistered = false
    private var scanning = false
    private val nearby = linkedMapOf<String, BluetoothDevice>()
    private val foundDevice = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!offline) return
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) nearby[device.address] = device
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
            }
            if (game == null) render(true)
        }
    }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playerId: String
    private var roomId = ""
    private var name = ""
    private var code = ""
    private var game: JSONObject? = null
    private var rooms = emptyList<JSONObject>()
    private var categories = Rules.categories.take(4).toMutableList()
    private var publicRoom = true
    private var mode = "create"
    private var busy = false
    private var connected = false
    private var error = ""
    private var draftKey = ""
    private var draft = JSONObject()
    private var draftRevision = 0L
    private var syncedRevision = 0L
    private var lastSignature = ""
    private var stopped = true
    private var lastLobbyPoll = 0L
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private var timerText: TextView? = null
    private var stopButton: Button? = null
    private var scroll: ScrollView? = null
    private val ink = Color.rgb(36, 48, 40)
    private val green = Color.rgb(39, 99, 75)
    private val paper = Color.rgb(244, 239, 223)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (game != null) leave() else finish()
        }
        playerId = prefs.getString("player", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("player", it).apply() }
        offline = BluetoothSession.store != null
        roomId = if (offline) prefs.getString("bluetoothRoom", "") ?: "" else prefs.getString("room", "") ?: ""
        name = prefs.getString("name", "") ?: ""
        code = intent?.data?.getQueryParameter("code")?.uppercase()?.filter { it.isLetterOrDigit() }?.take(5) ?: ""
        if (code.isNotEmpty()) mode = "join"
        draftKey = prefs.getString("draftKey", "") ?: ""
        draft = runCatching { JSONObject(prefs.getString("draft", "{}") ?: "{}") }.getOrDefault(JSONObject())
        render(true)
        worker.scheduleWithFixedDelay({ poll() }, 0, 750, TimeUnit.MILLISECONDS)
    }
    override fun onStart() { super.onStart(); stopped = false }
    @SuppressLint("MissingPermission")
    override fun onStop() {
        stopped = true
        if (scanning) { runCatching { bluetooth?.cancelDiscovery() }; scanning = false }
        super.onStop()
    }
    override fun onDestroy() {
        worker.shutdownNow(); ui.removeCallbacksAndMessages(null)
        if (discoveryRegistered) unregisterReceiver(foundDevice)
        if (isFinishing && offline) BluetoothSession.clear()
        super.onDestroy()
    }
    @Deprecated("Activity back compatibility for API 26+")
    override fun onBackPressed() { if (game != null) leave() else super.onBackPressed() }

    private fun poll() {
        if (stopped) return
        // UI-owned values are captured together before network work.
        val capture = java.util.concurrent.FutureTask { Snapshot(roomId, draftKey, draft.copy(), draftRevision, offline) }
        ui.post(capture)
        val s = try { capture.get(2, TimeUnit.SECONDS) } catch (_: Exception) { return }
        try {
            if (s.offline != offline) return
            val transport = if (s.offline) BluetoothSession.store else firebase
            if (transport == null) {
                ui.post { if (offline) { connected = true; render() } }; return
            }
            val api = transport
            if (s.room.isEmpty()) {
                if (System.currentTimeMillis() - lastLobbyPoll < 5000) return
                val open = api.publicRooms()
                lastLobbyPoll = System.currentTimeMillis()
                ui.post { if (roomId.isEmpty() && offline == s.offline) { rooms = open; connected = true; render() } }
                return
            }
            var current = api.room(s.room)
            if (current != null && current.obj("players").has(playerId)) {
                val g = current
                val round = Rules.key(g)
                if (g.optString("status") == "playing" && s.round == round && s.revision > syncedRevision) {
                    current = api.mutate(s.room) { latest ->
                        requireNotNull(latest) { "Room no longer exists." }
                        if (latest.optString("status") == "playing" && Rules.key(latest) == round && latest.obj("players").has(playerId) && !latest.obj("answers").has(playerId) && api.now() < latest.optLong("roundEndsAt", Long.MAX_VALUE)) latest.obj("players").obj(playerId).put("currentAnswers", s.answers)
                        latest
                    }
                    syncedRevision = s.revision
                }
                val state = current ?: g
                if (state.optString("status") == "playing" && (expired(state) || !state.isNull("busStoppedBy") || Rules.players(state).all { state.obj("answers").has(it) })) {
                    current = api.mutate(s.room) { latest ->
                        requireNotNull(latest)
                        if (Rules.key(latest) == round && latest.optString("status") == "playing" && (expired(latest) || !latest.isNull("busStoppedBy") || Rules.players(latest).all { latest.obj("answers").has(it) })) Rules.requestReview(latest, api.now()) else latest
                    }
                }
                val collecting = current
                if (collecting?.optString("status") == "collecting" && (!collecting.obj("finalAnswersReady").optBoolean(playerId) || api.now() >= collecting.optLong("collectionEndsAt"))) {
                    // Include edits typed while the room fetch was in flight.
                    val finalCapture = java.util.concurrent.FutureTask { Snapshot(roomId, draftKey, draft.copy(), draftRevision, offline) }
                    ui.post(finalCapture)
                    val visible = finalCapture.get(2, TimeUnit.SECONDS)
                    val finalDraft = if (visible.round == round) visible.answers else collecting.obj("players").obj(playerId).obj("currentAnswers")
                    current = api.mutate(s.room) { latest -> requireNotNull(latest); Rules.collect(latest, playerId, round, finalDraft, api.now()) ?: latest }
                }
            }
            val result = current
            ui.post {
                if (roomId != s.room || offline != s.offline) return@post
                connected = true
                if (result == null || !result.obj("players").has(playerId)) {
                    resetRoom(); if (offline) BluetoothSession.clear(); error = "You are no longer in that room. Join or create a table."
                } else accept(result)
                render()
            }
        } catch (e: Exception) { ui.post { if (offline == s.offline) { connected = false; statusText.text = "Reconnecting… ${e.message ?: if (offline) "Stay near the Bluetooth host." else "Check your connection."}"; stopButton?.isEnabled = false } } }
    }
    private data class Snapshot(val room: String, val round: String, val answers: JSONObject, val revision: Long, val offline: Boolean)
    private fun expired(g: JSONObject) = if (g.has("roundEndsAt")) api.now() >= g.optLong("roundEndsAt") else g.optInt("timeLeft") <= 0
    private fun accept(g: JSONObject) {
        game = g
        if (draftKey != Rules.key(g)) {
            draftKey = Rules.key(g); draft = g.obj("players").obj(playerId).obj("currentAnswers").copy()
            draftRevision = 0; syncedRevision = 0; saveDraft()
        }
    }
    private fun saveDraft() { prefs.edit().putString("draftKey", draftKey).putString("draft", draft.toString()).apply() }
    private fun action(block: () -> Unit) {
        if (busy) return
        busy = true; error = ""; render()
        worker.execute {
            try { block() } catch (e: Exception) { ui.post { error = e.message ?: "Please try again." } }
            finally { ui.post { busy = false; render(true) } }
        }
    }
    private fun mutate(change: (JSONObject) -> JSONObject?) {
        val id = roomId
        val result = api.mutate(id) { requireNotNull(it) { "Room not found." }; change(it) }
        ui.post { if (roomId == id && result != null) accept(result) }
    }
    private fun player(now: Long) = JSONObject().put("id", playerId).put("name", name.trim().take(24)).put("joinedAt", now).put("isReady", false)
    private fun enter(id: String, g: JSONObject) { ui.post { roomId = id; code = id; connected = true; accept(g); prefs.edit().putString(if (offline) "bluetoothRoom" else "room", id).putString("name", name.trim()).apply() } }
    private fun create() {
        if (name.isBlank() || categories.isEmpty()) { error = "Choose a name and at least one category."; render(true); return }
        val selected = categories.toList(); val visible = publicRoom
        action {
            if (offline) {
                BluetoothSession.clear()
                BluetoothSession.store = BluetoothRooms.host(checkNotNull(bluetooth) { "Bluetooth is unavailable on this phone." })
            }
            val id = UUID.randomUUID().toString().replace("-", "").take(5).uppercase(); val now = api.now()
            val result = api.mutate(id) { old ->
                check(old == null) { "Room code in use. Please try again." }
                JSONObject().put("id", id).put("status", "waiting").put("players", JSONObject().put(playerId, player(now)))
                    .put("currentLetter", "").put("roundTime", 60).put("timeLeft", 60).put("currentRound", 1).put("totalRounds", 3)
                    .put("selectedCategories", JSONArray(selected)).put("createdAt", now).put("lastActive", now).put("isPublic", visible && !offline).put("maxPlayers", 8)
            }
            enter(id, requireNotNull(result))
        }
    }
    private fun join(requested: String = code) {
        val id = requested.trim().uppercase()
        if (name.isBlank() || !id.matches(Regex("[A-Z0-9]{5}"))) { error = "Enter your name and a five-character room code."; render(true); return }
        action {
            val result = api.mutate(id) { g ->
                requireNotNull(g) { "Room not found. Check the code with your friend." }
                if (!g.obj("players").has(playerId)) {
                    check(g.optString("status") == "waiting" && Rules.players(g).size < g.optInt("maxPlayers", 8)) { "This room has started or is full." }
                    g.put("players", g.obj("players").put(playerId, player(api.now()))).put("lastActive", api.now())
                }
                g
            }; enter(id, requireNotNull(result))
        }
    }
    private fun start() = action { mutate { g ->
        check(Rules.host(g, playerId) && g.optString("status") in listOf("waiting", "finished") && Rules.players(g).size >= 2 && (g.optString("status") == "finished" || Rules.players(g).all { g.obj("players").obj(it).optBoolean("isReady") })) { "Wait for every player to be ready." }
        Rules.beginRound(g, letter(), UUID.randomUUID().toString(), api.now(), true)
    } }
    private fun letter() = ('A'..'Z').random().toString()
    private fun submit() {
        val answers = draft.copy(); val round = draftKey
        action { mutate { g ->
            check(g.optString("status") == "playing" && Rules.key(g) == round && g.obj("players").has(playerId) && !g.obj("answers").has(playerId) && !expired(g)) { "The round has ended." }
            val sheet = Rules.sheet(g, answers); check(Rules.complete(g, sheet)) { "Fill every category with the correct starting letter." }
            g.put("answers", g.obj("answers").put(playerId, sheet.put("_submitted", "yes"))).put("busStoppedBy", g.obj("players").obj(playerId).optString("name"))
            Rules.requestReview(g, api.now())
        } }
    }
    private fun leave() {
        val host = offline && BluetoothSession.store?.hosting == true
        AlertDialog.Builder(this).setTitle(if (host) "End this Bluetooth table?" else "Leave this table?").setMessage(if (host) "You are hosting the Bluetooth connection. Leaving ends this table for everyone." else "Your entries and review duties will be removed. Disconnected players stay in the room until they return or leave.")
            .setNegativeButton("Keep playing", null).setPositiveButton("Leave") { _, _ -> action {
                if (offline) {
                    try { if (!host) mutate { g -> g.obj("players").remove(playerId); g } }
                    finally { BluetoothSession.clear(); ui.post { resetRoom(); connected = true } }
                } else {
                    mutate { g -> g.obj("players").remove(playerId); if (Rules.players(g).isEmpty()) null else g }
                    ui.post { resetRoom() }
                }
            } }.show()
    }
    private fun resetRoom() {
        roomId = ""; game = null; draft = JSONObject(); draftKey = ""
        prefs.edit().remove(if (offline) "bluetoothRoom" else "room").remove("draft").remove("draftKey").apply()
    }

    private fun switchMode(value: Boolean) {
        if (busy || game != null || roomId.isNotEmpty()) return
        BluetoothSession.clear(); offline = value; nearby.clear(); rooms = emptyList(); connected = value
        error = ""; code = ""; lastLobbyPoll = 0; render(true)
    }
    private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    @SuppressLint("MissingPermission")
    private fun withBluetooth(block: () -> Unit) {
        val adapter = bluetooth
        if (adapter == null) { error = "This phone does not support Bluetooth. Online play is still available."; render(true); return }
        bluetoothAction = block
        val missing = bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 42); return }
        if (!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 40); return }
        bluetoothAction = null
        runCatching { block() }.onFailure { error = it.message ?: "Could not use Bluetooth."; render(true) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 42) return
        val next = bluetoothAction; bluetoothAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) { if (next != null) withBluetooth(next) }
        else { error = "Allow Bluetooth permissions to host or find nearby tables. You can also use online play."; render(true) }
    }
    @Deprecated("Activity result compatibility for API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40) {
            val next = bluetoothAction; bluetoothAction = null
            if (resultCode == RESULT_OK && next != null) withBluetooth(next)
            else { error = "Turn on Bluetooth to play nearby."; render(true) }
        } else if (requestCode == 41) {
            if (resultCode > 0) create() else { error = "Allow discoverability so nearby friends can find your phone."; render(true) }
        }
    }
    private fun prepareHost() {
        if (name.isBlank() || categories.isEmpty()) { error = "Choose a name and at least one category."; render(true); return }
        withBluetooth {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 41)
        }
    }
    @SuppressLint("MissingPermission")
    private fun scanNearby() = withBluetooth {
        if (!discoveryRegistered) {
            val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(foundDevice, filter, Context.RECEIVER_EXPORTED) else registerReceiver(foundDevice, filter)
            discoveryRegistered = true
        }
        val adapter = checkNotNull(bluetooth)
        adapter.cancelDiscovery(); nearby.clear(); adapter.bondedDevices.forEach { nearby[it.address] = it }
        scanning = adapter.startDiscovery()
        if (!scanning) error = "Could not scan. On Android 11 or older, turn on Location, or pair the phones in Bluetooth settings."
        render(true)
    }
    @SuppressLint("MissingPermission")
    private fun connectNearby(device: BluetoothDevice) {
        if (name.isBlank()) { error = "Enter your name first."; render(true); return }
        withBluetooth { action {
            bluetooth?.cancelDiscovery()
            BluetoothSession.clear()
            val store = BluetoothRooms.connect(checkNotNull(bluetooth), device.address)
            BluetoothSession.store = store
            try {
                val initial = store.publicRooms().firstOrNull() ?: error("This phone has no NPAT table. Ask your friend to host first.")
                val id = initial.getString("id")
                val joined = store.mutate(id) { g ->
                    requireNotNull(g) { "This table has ended." }
                    if (!g.obj("players").has(playerId)) {
                        check(g.optString("status") == "waiting" && Rules.players(g).size < g.optInt("maxPlayers", 8)) { "This table has started or is full." }
                        g.put("players", g.obj("players").put(playerId, player(store.now()))).put("lastActive", store.now())
                    }
                    g
                }
                enter(id, requireNotNull(joined))
            } catch (e: Exception) { BluetoothSession.clear(); throw e }
        } }
    }
    @SuppressLint("MissingPermission")
    private fun nearbyEntry() {
        text("Play nearby · no internet", 20, true)
        text("One phone hosts. Friends choose that phone below and accept Android's pairing prompt. Keep the host app open and stay within Bluetooth range.", 14)
        row { button("Host a table", enabled = !busy) { mode = "create"; render(true) }; button("Join nearby", enabled = !busy) { mode = "join"; render(true); scanNearby() } }
        if (mode == "join") {
            button(if (scanning) "Scanning… Scan again" else "Find nearby phones", enabled = !busy) { scanNearby() }
            text("Ask the host to allow discoverability. Paired phones are also shown; only phones hosting NPAT can accept a game connection.", 13)
            nearby.values.toList().forEach { device ->
                val label = runCatching { device.name }.getOrNull()?.ifBlank { null } ?: "Bluetooth phone"
                button("$label · ${device.address}", enabled = !busy) { connectNearby(device) }
            }
            button("Open Bluetooth settings", enabled = !busy) { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    private fun render(force: Boolean = false) {
        if (isDestroyed) return
        val g = game
        if (offline && g != null) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val signature = if (g == null) "$roomId|$rooms|$busy|$error|$mode|$connected|$offline" else {
            val clean = g.copy(); Rules.players(clean).forEach { clean.obj("players").obj(it).remove("currentAnswers") }
            "$clean|$busy|$error|$connected"
        }
        if (!force && signature == lastSignature) { updateClock(); return }
        lastSignature = signature
        val oldScroll = scroll?.scrollY ?: 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper); setPadding(0, 0, 0, 0) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(30)) }
        scroll!!.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, -1)); setContentView(root)
        text("N P A T", 34, true); text("Name. Place. Animal. Thing.", 16)
        statusText = text(if (offline) { if (roomId.isEmpty()) "Bluetooth mode · no internet needed" else if (connected) "● Bluetooth table connected" else "Reconnecting to Bluetooth host…" } else if (connected) "● Connected to the table" else "Connecting to Firebase…", 12)
        statusText.setTextColor(green)
        if (error.isNotEmpty()) text(error, 15, true).setTextColor(Color.rgb(160, 45, 35))
        timerText = null; stopButton = null
        if (g == null) { if (roomId.isEmpty()) entry() else {
            text("Finding your seat…", 20)
            button("Back to tables", enabled = !busy) { if (offline) BluetoothSession.clear(); resetRoom(); render(true) }
        } }
        else {
            text("ROOM ${g.optString("id")}  ·  ${if (g.optString("status") == "waiting") "Lobby" else "Round ${g.optInt("currentRound")} of ${g.optInt("totalRounds")}"}", 16, true)
            row { button("Copy code", enabled = true) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NPAT room", g.optString("id"))); Toast.makeText(this@MainActivity, "Room code copied", Toast.LENGTH_SHORT).show() }
                button("Share", enabled = true) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, if (offline) "Join my nearby NPAT table! In the Android app choose Bluetooth, then Join nearby and select my phone. No internet needed." else "Join my NPAT table! Room code: ${g.optString("id")}. Open the NPAT app or play on StatsUpdate."), "Invite friends")) }
                button("Leave", enabled = !busy) { leave() } }
            when (g.optString("status")) {
                "waiting" -> waiting(g)
                "playing" -> playing(g)
                "collecting" -> { text("Pencils down.", 28, true); text("Saving everyone's final answers. This takes up to three seconds.") }
                "voting" -> voting(g)
                "finished" -> finished(g)
            }
            if (offline && BluetoothSession.store?.hosting == true) {
                if (g.optString("status") == "waiting") button("Make phone discoverable again", enabled = !busy) { withBluetooth { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 43) } }
                val guests = Rules.players(g).filter { it != playerId }
                if (guests.isNotEmpty()) button("Remove a player", enabled = !busy) {
                    AlertDialog.Builder(this).setTitle("Remove a player from the table").setItems(guests.map { g.obj("players").obj(it).optString("name") }.toTypedArray()) { _, index ->
                        val target = guests[index]
                        AlertDialog.Builder(this).setMessage("Remove ${g.obj("players").obj(target).optString("name")}? Their review duties will also be removed.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> action { mutate { latest -> latest.obj("players").remove(target); latest } } }.show()
                    }.setNegativeButton("Cancel", null).show()
                }
            }
            text("At the table", 22, true)
            Rules.players(g).sortedByDescending { g.obj("scores").optInt(it) }.forEach { id ->
                val p = g.obj("players").obj(id)
                val detail = if (g.optString("status") == "voting") Rules.progress(g, id).let { "${it.first}/${it.second} reviewed" } else if (Rules.host(g, id)) "Host" else "Player"
                text("${p.optString("name")}${if (id == playerId) " (you)" else ""}   ·   ${g.obj("scores").optInt(id)} pts   ·   $detail", 15)
            }
        }
        button("The house rules", enabled = true) { AlertDialog.Builder(this).setTitle("The house rules").setMessage("Everyone gets the same letter. Fill every category to stop the bus.\n\nA correct, unique answer earns 10 points only when more than half of your opponents approve. Tied votes, shared answers, blanks and wrong letters earn zero.\n\nReview every eligible opponent answer. There is no review timer. Disconnected players must return to review or leave.\n\n" + (if (offline) "The Bluetooth host must keep the app open. Leaving the host ends this table. The host can remove absent players." else "The first player to join is host; if they leave, the next player becomes host.") + " Three rounds per game, then rematch!").setPositiveButton("Got it", null).show() }
        updateClock()
        button("Privacy policy", enabled = true) { showPrivacyPolicy() }
        if (!force) scroll?.post { scroll?.scrollTo(0, oldScroll) }
    }
    private fun showPrivacyPolicy() {
        val policy = resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
        val body = TextView(this).apply { text = policy; textSize = 15f; setTextColor(ink); setPadding(dp(20), dp(12), dp(20), dp(20)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("NPAT privacy policy").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Close", null).show()
    }
    private fun entry() {
        row { button(if (!offline) "✓ Online" else "Online", enabled = !busy) { switchMode(false) }; button(if (offline) "✓ Bluetooth" else "Bluetooth · offline", enabled = !busy) { switchMode(true) } }
        text("Pull up a chair", 25, true)
        input("Your name", name, 24) { name = it; prefs.edit().putString("name", it).apply() }
        if (offline) { nearbyEntry(); if (mode == "join") return }
        else row { button("Make a room", enabled = !busy) { mode = "create"; render(true) }; button("Join friends", enabled = !busy) { mode = "join"; render(true) } }
        if (mode == "create") {
            text("Pick 1–6 categories", 19, true)
            Rules.categories.chunked(2).forEach { pair -> row { pair.forEach { c ->
                val check = CheckBox(this@MainActivity).apply {
                    text = c; isChecked = c in categories; setTextColor(ink); isEnabled = !busy
                    setOnCheckedChangeListener { box, selected -> if (selected && c !in categories) { if (categories.size < 6) categories.add(c) else { box.isChecked = false; Toast.makeText(this@MainActivity, "Choose up to six categories", Toast.LENGTH_SHORT).show() } } else if (!selected) categories.remove(c) }
                }; addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            } } }
            if (!offline) content.addView(CheckBox(this).apply { text = "Open table · let other players find this room"; isChecked = publicRoom; setOnCheckedChangeListener { _, value -> publicRoom = value } })
            button(if (busy) "Making your room…" else if (offline) "Host Bluetooth table" else "Let's play", enabled = !busy && (offline || connected)) { if (offline) prepareHost() else create() }
        } else {
            input("Five-character room code", code, 5) { code = it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' } }
            button("Join the table") { join() }
        }
        if (offline) return
        text("Open tables", 22, true)
        if (rooms.isEmpty()) text("No open tables yet. Make one and invite your friends.")
        rooms.forEach { r -> button("${r.obj("players").obj(Rules.players(r).firstOrNull() ?: "").optString("name", "Someone")}'s table · ${Rules.players(r).size}/${r.optInt("maxPlayers", 8)} · ${r.optString("id")}") {
            code = r.optString("id"); mode = "join"; if (name.isNotBlank()) join(code) else { error = "Enter your name first."; render(true) }
        } }
        text("One letter. A table full of rivals.\n60 seconds · 3 rounds · 2–8 players", 16)
    }
    private fun waiting(g: JSONObject) {
        text("Who's in?", 28, true)
        text("Share the code with friends, then everyone taps ready.")
        Rules.players(g).forEach { id -> val p = g.obj("players").obj(id); text("${p.optString("name")}  ${if (id == playerId) "(you)" else ""}  ·  ${if (p.optBoolean("isReady")) "✓ Ready" else "Getting settled"}", 17, true) }
        text(g.strings("selectedCategories").joinToString(" · "))
        button(if (g.obj("players").obj(playerId).optBoolean("isReady")) "Ready! Undo" else "I'm ready") { action { mutate { latest ->
            check(latest.optString("status") == "waiting" && latest.obj("players").has(playerId)) { "The room changed." }
            val p = latest.obj("players").obj(playerId); p.put("isReady", !p.optBoolean("isReady")); latest
        } } }
        if (Rules.host(g, playerId)) button("Start round 1", connected && !busy && Rules.players(g).size >= 2 && Rules.players(g).all { g.obj("players").obj(it).optBoolean("isReady") }) { start() }
        text(if (Rules.players(g).size < 2) "Bring one more player to start." else "Everyone must be ready before the host can start.", 13)
    }
    private fun playing(g: JSONObject) {
        text("Letter ${g.optString("currentLetter")}. Go!", 32, true)
        timerText = text("", 24, true)
        if (g.obj("answers").has(playerId)) { text("Your answers are in. Waiting for the table."); return }
        g.strings("selectedCategories").forEach { category ->
            text(category, 16, true)
            val field = input("${g.optString("currentLetter")}…", draft.optString(category), 80) { value ->
                draft.put(category, value); draftRevision++; saveDraft(); updateClock()
            }
            field.setOnFocusChangeListener { _, focused -> if (!focused && field.text.isNotBlank() && !field.text.toString().trim().uppercase().startsWith(g.optString("currentLetter"))) field.error = "Start with ${g.optString("currentLetter")}" }
            field.isEnabled = !expired(g) && !busy
        }
        text("A complete sheet stops the bus for everyone.", 13)
        stopButton = button("Stop the bus", false) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(content.windowToken, 0); submit() }
    }
    private fun updateClock() {
        val g = game ?: return
        val seconds = if (g.has("roundEndsAt")) ceil((g.optLong("roundEndsAt") - api.now()) / 1000.0).toInt().coerceAtLeast(0) else g.optInt("timeLeft", 60)
        timerText?.text = "$seconds seconds remaining"
        stopButton?.isEnabled = connected && !busy && seconds > 0 && Rules.complete(g, draft)
        if (seconds <= 0 && g.optString("status") == "playing") {
            for (i in 0 until content.childCount) (content.getChildAt(i) as? EditText)?.isEnabled = false
        }
    }
    private fun voting(g: JSONObject) {
        val round = Rules.key(g)
        text("Would you count it?", 28, true)
        text("Letter ${g.optString("currentLetter")} · ${if (g.isNull("busStoppedBy")) "Time to compare notes." else g.optString("busStoppedBy") + " stopped the bus."}")
        val p = Rules.progress(g, playerId); text("${p.first} of ${p.second} reviewed", 18, true)
        text("Empty, shared and wrong-letter answers score zero automatically. You may change a vote until the host moves on.", 13)
        Rules.players(g).filter { it != playerId }.forEach { target ->
            text(g.obj("players").obj(target).optString("name"), 23, true)
            g.strings("selectedCategories").forEach { c ->
                val issue = Rules.issue(g, target, c)
                text("$c · ${g.obj("answers").obj(target).optString(c).ifBlank { "No answer" }}", 17, true)
                if (issue != null) text(issue, 13) else {
                    val decision = g.obj("votes").obj(target).obj(c).opt(playerId)
                    row { listOf(true, false).forEach { approved -> button("${if (decision == approved) "✓ " else ""}${if (approved) "Approve" else "Disapprove"}") { action { mutate { latest -> Rules.vote(latest, playerId, target, c, approved, round) ?: error("This review has ended.") } } } } }
                }
            }
        }
        button("View your answers", enabled = true) { AlertDialog.Builder(this).setTitle("Your answers").setMessage(g.strings("selectedCategories").joinToString("\n\n") { "$it: ${g.obj("answers").obj(playerId).optString(it).ifBlank { "No answer" }}" }).setPositiveButton("Done", null).show() }
        val pending = Rules.players(g).filter { Rules.progress(g, it).let { p2 -> p2.first < p2.second } }
        text(if (pending.isEmpty()) "All reviews are in. Ready to score!" else "Waiting for ${pending.joinToString { g.obj("players").obj(it).optString("name") }}.", 15, true)
        if (Rules.host(g, playerId)) button(if (g.optInt("currentRound") >= g.optInt("totalRounds")) "Show final scores" else "Score & next round", !busy && connected && Rules.reviewed(g)) { action { mutate { latest -> Rules.advance(latest, playerId, round, letter(), UUID.randomUUID().toString(), api.now()) ?: error("Every review must be complete before continuing.") } } }
        else text("The host can continue when everyone finishes reviewing.", 13)
    }
    private fun finished(g: JSONObject) {
        val ordered = Rules.players(g).sortedByDescending { g.obj("scores").optInt(it) }
        val top = ordered.firstOrNull()?.let { g.obj("scores").optInt(it) } ?: 0
        val winners = ordered.filter { g.obj("scores").optInt(it) == top }
        text(if (winners.size > 1) "A shared victory!" else "${g.obj("players").obj(winners.firstOrNull() ?: "").optString("name")} takes the win!", 28, true)
        ordered.forEachIndexed { index, id -> text("${index + 1}. ${g.obj("players").obj(id).optString("name")}   ${g.obj("scores").optInt(id)} points", 21, true) }
        text("Last round", 17, true)
        ordered.forEach { id -> text("${g.obj("players").obj(id).optString("name")}: +${g.obj("lastRoundScores").optInt(id)}") }
        if (Rules.host(g, playerId)) button("One more game?", connected && !busy && ordered.size >= 2) { start() } else text("Waiting for the host to start a rematch.")
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int = 15, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink); setPadding(0, dp(8), 0, dp(8)); if (bold) setTypeface(typeface, Typeface.BOLD)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun input(hintText: String, value: String, max: Int, changed: (String) -> Unit): EditText = EditText(this).apply {
        hint = hintText; textSize = 18f; setTextColor(ink); setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(max)); setText(value)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(52)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.rgb(190, 190, 171)) }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }; override fun afterTextChanged(s: Editable?) {} })
    }
    private var rowTarget: LinearLayout? = null
    private fun row(block: LinearLayout.() -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(layout, LinearLayout.LayoutParams(-1, -2)); rowTarget = layout; layout.block(); rowTarget = null
    }
    private fun button(label: String, enabled: Boolean = connected && !busy, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48); isEnabled = enabled; setOnClickListener { click() }
        val parent = rowTarget ?: content
        parent.addView(this, if (rowTarget != null) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
    }
}
