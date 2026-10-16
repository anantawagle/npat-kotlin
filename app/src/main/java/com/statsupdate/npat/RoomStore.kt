package com.statsupdate.npat

import org.json.JSONObject

internal interface RoomStore {
    fun now(): Long
    fun room(id: String): JSONObject?
    fun publicRooms(): List<JSONObject>
    fun mutate(id: String, change: (JSONObject?) -> JSONObject?): JSONObject?
}
