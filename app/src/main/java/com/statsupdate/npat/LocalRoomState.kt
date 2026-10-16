package com.statsupdate.npat

import org.json.JSONObject

/** Host-side atomic state. Copies isolate callers; revision guards concurrent edits. */
internal class LocalRoomState(private val clock: () -> Long = System::currentTimeMillis) {
    private var room: JSONObject? = null
    private var revision = 0L
    @Synchronized fun read(): JSONObject = JSONObject().put("revision", revision)
        .put("room", room?.copy() ?: JSONObject.NULL).put("now", clock())
    @Synchronized fun replace(expected: Long, next: JSONObject?): JSONObject {
        if (expected != revision) return read().put("conflict", true)
        room = next?.copy(); revision++
        return read().put("conflict", false)
    }
}
