package com.statsupdate.npat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Retained across Activity recreation; never contains an Activity reference. */
internal object BluetoothSession {
    @Volatile var store: BluetoothRooms? = null
    fun clear() { store?.close(); store = null }
}

/** Secure, paired RFCOMM star network. All state lives on the host, never Firebase. */
@SuppressLint("MissingPermission") // Entry points are guarded by Activity runtime checks.
internal class BluetoothRooms private constructor(
    private val adapter: BluetoothAdapter,
    val hosting: Boolean,
    val hostAddress: String? = null
) : RoomStore, Closeable {
    private val state = LocalRoomState()
    private val io = Executors.newCachedThreadPool()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val peers = ConcurrentHashMap.newKeySet<BluetoothSocket>()
    private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    @Volatile private var offset = 0L
    private val rpcLock = Any()
    override fun now() = System.currentTimeMillis() + offset

    companion object {
        private val SERVICE = UUID.fromString("43c80352-2a4f-4f2a-a77d-f40b2d50ce41")
        private const val MAX_FRAME = 262144
        fun host(adapter: BluetoothAdapter): BluetoothRooms = BluetoothRooms(adapter, true).apply {
            try { startServer() } catch (e: Exception) { close(); throw e }
        }
        fun connect(adapter: BluetoothAdapter, address: String): BluetoothRooms = BluetoothRooms(adapter, false, address).apply {
            try { snapshot() } catch (e: Exception) { close(); throw e }
        }
        private fun readFrame(socket: BluetoothSocket): JSONObject {
            val stream = DataInputStream(socket.inputStream)
            val length = stream.readInt()
            require(length in 1..MAX_FRAME) { "Invalid Bluetooth message size." }
            val bytes = ByteArray(length); stream.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
        private fun writeFrame(socket: BluetoothSocket, value: JSONObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FRAME) { "Room is too large to sync over Bluetooth." }
            DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
        }
    }
    private fun startServer() {
        adapter.cancelDiscovery()
        server = adapter.listenUsingRfcommWithServiceRecord("NPAT Offline", SERVICE)
        io.execute {
            while (!closed) {
                val peer = try { server?.accept() ?: break } catch (_: Exception) { break }
                if (peers.size >= 7) { runCatching { peer.close() }; continue }
                peers.add(peer)
                io.execute {
                    try {
                        while (!closed) {
                            val request = readFrame(peer)
                            val response = when (request.optString("op")) {
                                "read" -> state.read()
                                "replace" -> state.replace(request.getLong("revision"), request.optJSONObject("room"))
                                else -> error("Unknown Bluetooth request.")
                            }
                            val timeout = deadlines.schedule({ runCatching { peer.close() } }, 10, TimeUnit.SECONDS)
                            try { writeFrame(peer, response) } finally { timeout.cancel(false) }
                        }
                    } catch (_: Exception) { /* Keep the seat so this player can reconnect. */ }
                    finally { peers.remove(peer); runCatching { peer.close() } }
                }
            }
        }
    }
    private fun exchange(request: JSONObject): JSONObject = synchronized(rpcLock) {
        check(!closed) { "Bluetooth table is closed." }
        var connection = socket
        if (connection == null) {
            adapter.cancelDiscovery()
            connection = adapter.getRemoteDevice(hostAddress).createRfcommSocketToServiceRecord(SERVICE)
            socket = connection
        }
        val active = connection
        // First connection may require confirmation on both phones.
        val timeout = deadlines.schedule({ runCatching { active.close() } }, if (active.isConnected) 12 else 45, TimeUnit.SECONDS)
        try {
            if (!active.isConnected) active.connect()
            val started = System.currentTimeMillis()
            writeFrame(active, request)
            readFrame(active).also { offset = it.getLong("now") - (started + System.currentTimeMillis()) / 2 }
        } catch (e: Exception) {
            runCatching { active.close() }; socket = null
            throw IllegalStateException("Bluetooth host unavailable. Stay nearby, keep the host app open, and retry.", e)
        } finally { timeout.cancel(false) }
    }
    private fun snapshot() = if (hosting) state.read() else exchange(JSONObject().put("op", "read"))
    override fun room(id: String): JSONObject? = snapshot().optJSONObject("room")?.also { check(it.optString("id") == id) { "This Bluetooth host has a different table." } }
    override fun publicRooms() = listOfNotNull(snapshot().optJSONObject("room"))
    override fun mutate(id: String, change: (JSONObject?) -> JSONObject?): JSONObject? {
        repeat(20) {
            val before = snapshot(); val current = before.optJSONObject("room")
            check(current == null || current.optString("id") == id) { "This Bluetooth host has a different table." }
            val next = change(current?.copy())
            val result = if (hosting) state.replace(before.getLong("revision"), next) else exchange(JSONObject().put("op", "replace").put("revision", before.getLong("revision")).put("room", next ?: JSONObject.NULL))
            if (!result.optBoolean("conflict")) return result.optJSONObject("room")
        }
        error("The Bluetooth table is busy. Please try again.")
    }
    override fun close() {
        closed = true; runCatching { server?.close() }; runCatching { socket?.close() }
        peers.forEach { runCatching { it.close() } }; peers.clear()
        io.shutdownNow(); deadlines.shutdownNow()
    }
}
