package com.statsupdate.npat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private fun game(count: Int = 3): JSONObject {
        val players = JSONObject()
        repeat(count) { players.put("p$it", JSONObject().put("id", "p$it").put("name", "Player $it").put("joinedAt", it).put("currentAnswers", JSONObject().put("Name", "Alice$it"))) }
        return JSONObject().put("players", players).put("status", "playing").put("currentLetter", "A").put("selectedCategories", JSONArray(listOf("Name")))
            .put("createdAt", 100L).put("roundId", "round-1").put("currentRound", 1).put("totalRounds", 1).put("roundTime", 60).put("answers", JSONObject())
    }
    @Test fun collectionFreezesLatestDraftButKeepsSubmittedAnswer() {
        val g = game(2)
        g.obj("answers").put("p0", JSONObject().put("Name", "Amy").put("_submitted", "yes"))
        Rules.requestReview(g, 1000)
        assertEquals("collecting", g.optString("status"))
        assertNull(Rules.collect(g, "p1", "stale-round", JSONObject(), 1001))
        Rules.collect(g, "p0", "round-1", JSONObject().put("Name", "Altered"), 1001)
        Rules.collect(g, "p1", "round-1", JSONObject().put("Name", "Amber"), 1002)
        assertEquals("voting", g.optString("status"))
        assertEquals("Amy", g.obj("answers").obj("p0").optString("Name"))
        assertEquals("Amber", g.obj("answers").obj("p1").optString("Name"))
    }
    @Test fun deadlineUsesOfflinePlayersLastDraft() {
        val g = Rules.requestReview(game(), 1000)
        Rules.collect(g, "p0", "round-1", JSONObject(), 4000)
        assertEquals("voting", g.optString("status"))
        assertEquals("Alice2", g.obj("answers").obj("p2").optString("Name"))
    }
    @Test fun sharedWrongAndEmptyEntriesNeedNoReview() {
        val g = Rules.beginReview(game())
        g.obj("answers").obj("p0").put("Name", " alice ")
        g.obj("answers").obj("p1").put("Name", "ALICE")
        g.obj("answers").obj("p2").put("Name", "Bob")
        assertNotNull(Rules.issue(g, "p0", "Name"))
        assertNotNull(Rules.issue(g, "p1", "Name"))
        assertNotNull(Rules.issue(g, "p2", "Name"))
        assertTrue(Rules.reviewed(g))
        Rules.advance(g, "p0", "round-1", "B", "next", 5000)
        assertEquals(0, g.obj("scores").optInt("p0"))
    }
    @Test fun disapprovalIsCompleteButTiedVotesScoreZero() {
        val g = Rules.beginReview(game())
        assertNull(Rules.advance(g, "p0", "round-1", "B", "next", 5000))
        Rules.players(g).forEach { target -> Rules.players(g).filter { it != target }.forEachIndexed { i, reviewer ->
            assertNotNull(Rules.vote(g, reviewer, target, "Name", i == 0, "round-1"))
        } }
        assertTrue(Rules.reviewed(g))
        assertNull(Rules.advance(g, "p1", "round-1", "B", "next", 5000))
        assertNull(Rules.vote(g, "p0", "p0", "Name", true, "round-1"))
        Rules.advance(g, "p0", "round-1", "B", "next", 5000)
        assertEquals("finished", g.optString("status"))
        Rules.players(g).forEach { assertEquals(0, g.obj("scores").optInt(it)) }
        assertNull(Rules.advance(g, "p0", "round-1", "B", "next", 5000))
    }
    @Test fun majorityAwardsTenAndRematchClearsScores() {
        val g = Rules.beginReview(game())
        Rules.players(g).forEach { target -> Rules.players(g).filter { it != target }.forEach { reviewer -> Rules.vote(g, reviewer, target, "Name", true, "round-1") } }
        Rules.advance(g, "p0", "round-1", "B", "next", 5000)
        assertEquals(10, g.obj("scores").getInt("p0"))
        Rules.beginRound(g, "C", "rematch", 6000, true)
        assertEquals(1, g.optInt("currentRound")); assertEquals(66000L, g.optLong("roundEndsAt"))
        assertEquals(0, g.obj("scores").length()); assertEquals(0, g.obj("answers").length())
    }
    @Test fun departureTransfersHostAndRemovesReviewDuties() {
        val g = Rules.beginReview(game(2))
        g.obj("players").remove("p0")
        assertTrue(Rules.host(g, "p1")); assertTrue(Rules.reviewed(g))
        Rules.advance(g, "p1", "round-1", "B", "next", 5000)
        assertEquals(10, g.obj("scores").getInt("p1"))
    }
}
