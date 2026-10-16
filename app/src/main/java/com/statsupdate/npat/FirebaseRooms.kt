package com.statsupdate.npat

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Firebase's REST API uses the existing database, without creating a second backend.
 * If-Match prevents stale drafts, retries or simultaneous players overwriting a room. */
internal class FirebaseRooms : RoomStore {
    private val base = "https://npat-5d26e-default-rtdb.asia-southeast1.firebasedatabase.app"
    @Volatile var offset = 0L
    override fun now() = System.currentTimeMillis() + offset
    private data class Response(val code: Int, val body: String, val etag: String?)
    private fun request(path: String, method: String = "GET", body: String? = null, tag: String? = null, etag: Boolean = false): Response {
        val connection = URL("$base/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.useCaches = false
            if (etag) connection.setRequestProperty("X-Firebase-ETag", "true")
            if (tag != null) connection.setRequestProperty("if-match", tag)
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val start = System.currentTimeMillis(); val code = connection.responseCode
            if (connection.date > 0) offset = connection.date - (start + System.currentTimeMillis()) / 2
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "", connection.getHeaderField("ETag"))
        } finally { connection.disconnect() }
    }
    private fun Response.check(): Response {
        check(code in 200..299) { if (code == 401 || code == 403) "Firebase denied access. Check the existing database rules." else "Could not sync with Firebase (HTTP $code). Please retry." }
        return this
    }
    override fun room(id: String): JSONObject? = request("games/$id.json").check().body.let { if (it == "null") null else JSONObject(it) }
    override fun publicRooms(): List<JSONObject> {
        val indexed = request("games.json?orderBy=%22createdAt%22&limitToLast=50")
        // Match the web SDK's local filtering when production has no index.
        val response = (if (indexed.code == 400 && indexed.body.contains("Index not defined")) request("games.json") else indexed).check()
        val rooms = if (response.body == "null") JSONObject() else JSONObject(response.body)
        return rooms.keysList().mapNotNull { rooms.optJSONObject(it) }.sortedByDescending { it.optLong("createdAt") }.take(50).filter {
            it.optBoolean("isPublic") && it.optString("status") == "waiting" && it.strings("selectedCategories").isNotEmpty() && now() - it.optLong("lastActive", it.optLong("createdAt")) < 600000 && Rules.players(it).size < it.optInt("maxPlayers", 8)
        }.sortedByDescending { it.optLong("createdAt") }
    }
    override fun mutate(id: String, change: (JSONObject?) -> JSONObject?): JSONObject? {
        repeat(15) {
            val read = request("games/$id.json", etag = true).check()
            val current = if (read.body == "null") null else JSONObject(read.body)
            val next = change(current)
            val written = request("games/$id.json", "PUT", next?.toString() ?: "null", read.etag ?: error("Firebase did not return a transaction token."))
            if (written.code != 412) { written.check(); return next }
        }
        error("The table is busy. Please try again.")
    }
}
