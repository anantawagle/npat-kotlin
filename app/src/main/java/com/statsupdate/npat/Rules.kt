package com.statsupdate.npat

import org.json.JSONObject
import java.util.Locale

internal fun JSONObject.obj(key: String) = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.keysList(): List<String> = keys().asSequence().toList()
internal fun JSONObject.copy() = JSONObject(toString())
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

/** Wire-compatible port of statsupdate/lib/npat.ts. All mutations run under ETag CAS. */
internal object Rules {
    val categories = listOf("Name", "Place", "Animal", "Thing", "Food", "Color", "Profession", "Brand", "Movie", "Sport", "Celebrity", "Country", "Activity", "Cause of death")
    fun players(g: JSONObject) = g.obj("players").keysList().sortedWith(compareBy<String> { g.obj("players").obj(it).optLong("joinedAt") }.thenBy { it })
    fun host(g: JSONObject, id: String) = players(g).firstOrNull() == id
    fun key(g: JSONObject) = g.optString("roundId").ifEmpty { "${g.optLong("createdAt")}:${g.optInt("currentRound")}" }
    private fun norm(s: String) = s.trim().uppercase(Locale.US)
    fun complete(g: JSONObject, a: JSONObject) = g.strings("selectedCategories").all { norm(a.optString(it)).startsWith(norm(g.optString("currentLetter"))) }
    fun issue(g: JSONObject, target: String, category: String): String? {
        val answer = norm(g.obj("answers").obj(target).optString(category))
        if (answer.isEmpty()) return "No answer · 0 points"
        if (!answer.startsWith(norm(g.optString("currentLetter")))) return "Wrong starting letter · 0 points"
        if (players(g).any { it != target && norm(g.obj("answers").obj(it).optString(category)) == answer }) return "Shared answer · 0 points"
        return null
    }
    fun progress(g: JSONObject, reviewer: String): Pair<Int, Int> {
        var done = 0; var total = 0
        if (!g.obj("players").has(reviewer)) return 0 to 0
        players(g).filter { it != reviewer }.forEach { target -> g.strings("selectedCategories").forEach { c ->
            if (issue(g, target, c) == null) {
                total++
                if (g.obj("votes").obj(target).obj(c).opt(reviewer) is Boolean) done++
            }
        } }
        return done to total
    }
    fun reviewed(g: JSONObject) = players(g).isNotEmpty() && players(g).all { progress(g, it).let { p -> p.first == p.second } }
    fun beginReview(g: JSONObject): JSONObject {
        val answers = JSONObject()
        players(g).forEach { id -> answers.put(id, (g.obj("answers").optJSONObject(id) ?: g.obj("players").obj(id).obj("currentAnswers")).copy().put("_submitted", "yes")) }
        return g.put("status", "voting").put("timeLeft", 0).put("answers", answers).put("votes", JSONObject())
    }
    fun requestReview(g: JSONObject, now: Long): JSONObject {
        if (g.optString("status") != "playing") return g
        if (players(g).all { g.obj("answers").has(it) }) return beginReview(g)
        val ready = JSONObject()
        players(g).filter { g.obj("answers").has(it) }.forEach { ready.put(it, true) }
        return g.put("status", "collecting").put("timeLeft", 0).put("collectionEndsAt", now + 3000).put("finalAnswersReady", ready)
    }
    fun collect(g: JSONObject, actor: String, round: String, draft: JSONObject, now: Long): JSONObject? {
        if (g.optString("status") != "collecting" || key(g) != round || !g.obj("players").has(actor)) return null
        if (now >= g.optLong("collectionEndsAt")) return beginReview(g)
        if (!g.obj("finalAnswersReady").optBoolean(actor)) {
            val frozen = g.obj("answers").optJSONObject(actor) ?: sheet(g, draft)
            g.put("answers", g.obj("answers").put(actor, frozen.put("_submitted", "yes")))
            g.put("finalAnswersReady", g.obj("finalAnswersReady").put(actor, true))
        }
        return if (players(g).all { g.obj("finalAnswersReady").optBoolean(it) }) beginReview(g) else g
    }
    fun sheet(g: JSONObject, draft: JSONObject) = JSONObject().also { a -> g.strings("selectedCategories").forEach { a.put(it, draft.optString(it).trim()) } }
    fun beginRound(g: JSONObject, letter: String, token: String, now: Long, restart: Boolean): JSONObject {
        players(g).forEach { g.obj("players").obj(it).put("currentAnswers", JSONObject()) }
        g.put("status", "playing").put("currentLetter", letter).put("roundId", token)
            .put("currentRound", if (restart) 1 else g.optInt("currentRound") + 1)
            .put("roundEndsAt", now + g.optLong("roundTime") * 1000).put("timeLeft", g.optInt("roundTime"))
            .put("answers", JSONObject()).put("votes", JSONObject()).put("validation", JSONObject())
            .put("busStoppedBy", JSONObject.NULL).put("lastActive", now)
        g.remove("collectionEndsAt"); g.remove("finalAnswersReady")
        if (restart) g.put("scores", JSONObject()).put("lastRoundScores", JSONObject())
        return g
    }
    fun vote(g: JSONObject, actor: String, target: String, category: String, decision: Boolean, round: String): JSONObject? {
        if (g.optString("status") != "voting" || key(g) != round || actor == target || !g.obj("players").has(actor) || !g.obj("players").has(target) || category !in g.strings("selectedCategories") || issue(g, target, category) != null) return null
        val votes = g.obj("votes"); val targetVotes = votes.obj(target)
        targetVotes.put(category, targetVotes.obj(category).put(actor, decision)); votes.put(target, targetVotes)
        return g.put("votes", votes)
    }
    fun advance(g: JSONObject, actor: String, round: String, letter: String, token: String, now: Long): JSONObject? {
        if (g.optString("status") != "voting" || key(g) != round || !host(g, actor) || !reviewed(g)) return null
        val validation = JSONObject(); val roundScores = JSONObject(); val scores = g.obj("scores")
        players(g).forEach { id ->
            val opponents = players(g).filter { it != id }; val valid = JSONObject(); var points = 0
            g.strings("selectedCategories").forEach { c ->
                val approvals = opponents.count { g.obj("votes").obj(id).obj(c).opt(it) == true }
                val accepted = issue(g, id, c) == null && (opponents.isEmpty() || approvals > opponents.size / 2.0)
                valid.put(c, accepted); if (accepted) points += 10
            }
            validation.put(id, valid); roundScores.put(id, points); scores.put(id, scores.optInt(id) + points)
        }
        g.put("scores", scores).put("validation", validation).put("lastRoundScores", roundScores).put("lastActive", now)
        return if (g.optInt("currentRound") >= g.optInt("totalRounds")) g.put("status", "finished") else beginRound(g, letter, token, now, false)
    }
}
