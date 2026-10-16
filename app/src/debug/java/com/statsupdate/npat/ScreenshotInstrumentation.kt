package com.statsupdate.npat

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ScheduledExecutorService

/** Debug-only fixtures rendered by the actual app views; no Firebase writes or fake UI. */
class ScreenshotInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        super.callActivityOnCreate(activity, icicle)
        // Stop networking before onStart can enable the scheduled poller.
        if (activity is MainActivity) (field("worker").get(activity) as ScheduledExecutorService).shutdownNow()
    }
    override fun onStart() {
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {
                field("stopped").set(activity, true)
                (field("worker").get(activity) as ScheduledExecutorService).shutdownNow()
                field("playerId").set(activity, "maya")
                field("name").set(activity, "Maya")
                field("connected").set(activity, true)
                field("busy").set(activity, false)
                field("error").set(activity, "")
            }
            capture(activity, "01-play", game("playing"))
            capture(activity, "02-bluetooth", null, offline = true)
            capture(activity, "03-room", game("waiting").put("scores", JSONObject()))
            capture(activity, "04-categories", null)
            capture(activity, "05-review", game("voting"), scrollDp = 180)
            capture(activity, "06-results", game("finished"))
            runOnMainSync { activity.finish() }
            finish(0, Bundle().apply { putString("result", "Captured six native screens in files/store-captures") })
        } catch (e: Exception) {
            finish(1, Bundle().apply { putString("error", e.stackTraceToString()) })
        }
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun capture(activity: MainActivity, name: String, game: JSONObject?, offline: Boolean = false, scrollDp: Int = 0) {
        runOnMainSync {
            field("connected").set(activity, true)
            field("offline").set(activity, offline)
            field("game").set(activity, game)
            field("roomId").set(activity, game?.getString("id") ?: "")
            field("rooms").set(activity, emptyList<JSONObject>())
            field("mode").set(activity, "create")
            field("draftKey").set(activity, game?.let { Rules.key(it) } ?: "")
            field("draft").set(activity, answers("Amelia", "Athens", "Alpaca", "Anchor"))
            MainActivity::class.java.getDeclaredMethod("render", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
        }
        waitForIdleSync()
        if (scrollDp > 0) runOnMainSync { (field("scroll").get(activity) as ScrollView).scrollTo(0, (scrollDp * activity.resources.displayMetrics.density).toInt()) }
        Thread.sleep(700)
        val screenshot = checkNotNull(uiAutomation.takeScreenshot()) { "Screenshot capture failed" }
        val directory = File(targetContext.filesDir, "store-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
    private fun answers(name: String, place: String, animal: String, thing: String) = JSONObject()
        .put("Name", name).put("Place", place).put("Animal", animal).put("Thing", thing)
    private fun game(status: String): JSONObject {
        val players = JSONObject()
        listOf("maya" to "Maya", "arjun" to "Arjun", "sam" to "Sam").forEachIndexed { index, (id, name) ->
            players.put(id, JSONObject().put("id", id).put("name", name).put("joinedAt", 1000 + index).put("isReady", true))
        }
        val answers = JSONObject().put("maya", answers("Amelia", "Athens", "Alpaca", "Anchor"))
            .put("arjun", answers("Arjun", "Accra", "Antelope", "Alarm"))
            .put("sam", answers("Alice", "Ankara", "Alligator", "Apron"))
        val votes = JSONObject()
        if (status == "voting") votes.put("arjun", JSONObject().put("Name", JSONObject().put("maya", true)).put("Place", JSONObject().put("maya", true)))
        return JSONObject().put("id", "Q7K2M").put("status", status).put("players", players)
            .put("currentLetter", "A").put("roundId", "store-demo").put("currentRound", if (status == "waiting") 1 else 3)
            .put("totalRounds", 3).put("roundTime", 60).put("timeLeft", 42).put("roundEndsAt", System.currentTimeMillis() + 42000)
            .put("selectedCategories", JSONArray(listOf("Name", "Place", "Animal", "Thing")))
            .put("answers", if (status == "playing" || status == "waiting") JSONObject() else answers)
            .put("votes", votes).put("busStoppedBy", if (status == "voting") "Maya" else JSONObject.NULL)
            .put("scores", JSONObject().put("maya", if (status == "finished") 100 else 60).put("arjun", if (status == "finished") 90 else 50).put("sam", if (status == "finished") 80 else 40))
            .put("lastRoundScores", JSONObject().put("maya", 40).put("arjun", 40).put("sam", 40))
            .put("createdAt", 1000).put("isPublic", false).put("maxPlayers", 8)
    }
}
