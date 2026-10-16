package com.statsupdate.npat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalRoomStateTest {
    @Test fun stalePeerCannotOverwriteNewerDraft() {
        val state = LocalRoomState { 5000L }
        val first = state.read(); val second = state.read()
        assertFalse(state.replace(first.getLong("revision"), JSONObject().put("answer", "Alice")).getBoolean("conflict"))
        assertTrue(state.replace(second.getLong("revision"), JSONObject().put("answer", "Old draft")).getBoolean("conflict"))
        assertEquals("Alice", state.read().getJSONObject("room").getString("answer"))
        assertEquals(5000L, state.read().getLong("now"))
    }
    @Test fun snapshotsAndWritesDoNotShareMutableState() {
        val state = LocalRoomState()
        val original = JSONObject().put("score", 10)
        state.replace(0, original); original.put("score", 999)
        val read = state.read(); read.getJSONObject("room").put("score", 1000)
        assertEquals(10, state.read().getJSONObject("room").getInt("score"))
    }
    @Test fun concurrentPeersRetryWithoutDroppingUpdates() {
        val state = LocalRoomState()
        state.replace(0, JSONObject().put("count", 0))
        val pool = Executors.newFixedThreadPool(4); val done = CountDownLatch(4)
        repeat(4) { pool.execute {
            try { repeat(30) {
                while (true) {
                    val current = state.read(); val room = current.getJSONObject("room")
                    room.put("count", room.getInt("count") + 1)
                    if (!state.replace(current.getLong("revision"), room).getBoolean("conflict")) break
                }
            } } finally { done.countDown() }
        } }
        try { assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals(120, state.read().getJSONObject("room").getInt("count")) }
        finally { pool.shutdownNow() }
    }
    @Test fun deletionInvalidatesEarlierSnapshots() {
        val state = LocalRoomState()
        state.replace(0, JSONObject().put("id", "ABCDE"))
        assertFalse(state.replace(1, null).getBoolean("conflict"))
        assertTrue(state.read().isNull("room"))
        assertTrue(state.replace(1, JSONObject()).getBoolean("conflict"))
    }
}
