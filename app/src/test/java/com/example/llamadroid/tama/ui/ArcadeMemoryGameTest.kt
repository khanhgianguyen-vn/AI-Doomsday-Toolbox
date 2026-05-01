package com.example.llamadroid.tama.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeMemoryGameTest {

    @Test
    fun startMemoryGame_buildsEightPairsAndStartsAtCursorZero() {
        val state = startMemoryGame(nowMs = 0L, seed = 1234L)

        assertEquals(16, state.cards.size)
        assertEquals(8, state.totalPairs)
        assertEquals(0, state.cursorIndex)
        assertEquals(12, state.maxTurns)
        assertTrue(state.introUntilMs > 0L)
    }

    @Test
    fun moveMemoryCursor_wrapsAcrossTheLinearDeck() {
        val state = startMemoryGame(nowMs = 0L, seed = 1234L).copy(introUntilMs = 0L)

        assertEquals(15, moveMemoryCursor(state, -1).cursorIndex)
        assertEquals(1, moveMemoryCursor(state.copy(cursorIndex = 0), 1).cursorIndex)
        assertEquals(8, moveMemoryCursor(state.copy(cursorIndex = 7), 1).cursorIndex)
    }

    @Test
    fun matchPair_marksCardsAndAddsScoreAndTurns() {
        val initialState = startMemoryGame(nowMs = 0L, seed = 4321L).copy(introUntilMs = 0L)
        val pair = initialState.cards.withIndex()
            .groupBy { it.value.kind }
            .values
            .first { it.size == 2 }
            .map { it.index }
            .sorted()

        val afterFirstFlip = flipMemoryCard(
            initialState.copy(cursorIndex = pair[0]),
            nowMs = 1L
        )
        assertEquals(pair[0], afterFirstFlip.firstSelectionIndex)
        assertTrue(afterFirstFlip.cards[pair[0]].faceUp)

        val afterSecondFlip = flipMemoryCard(
            afterFirstFlip.copy(cursorIndex = pair[1]),
            nowMs = 2L
        )
        assertNotNull(afterSecondFlip.resolveAtMs)

        val advanced = advanceMemoryGame(
            afterSecondFlip,
            nowMs = afterSecondFlip.resolveAtMs!! + 1L
        )

        assertEquals(1, advanced.pairsMatched)
        assertEquals(1, advanced.turnsUsed)
        assertEquals(10, advanced.score)
        assertTrue(advanced.cards[pair[0]].matched)
        assertTrue(advanced.cards[pair[1]].matched)
        assertTrue(advanced.finished.not())
    }

    @Test
    fun mismatchPair_flipsBackAndCanEndTheRunOnTheLastTurn() {
        val initialState = startMemoryGame(nowMs = 0L, seed = 99L).copy(introUntilMs = 0L)
        val firstDifferentKind = initialState.cards.withIndex()
            .groupBy { it.value.kind }
            .values
            .map { group -> group.map { it.index } }
            .flatten()
            .distinct()
        val first = firstDifferentKind[0]
        val second = initialState.cards.indices.first { initialState.cards[it].kind != initialState.cards[first].kind }

        val almostFinished = initialState.copy(
            turnsUsed = 11,
            cursorIndex = first,
            firstSelectionIndex = first,
            secondSelectionIndex = second,
            resolveAtMs = 1L,
            cards = initialState.cards.mapIndexed { index, card ->
                when (index) {
                    first, second -> card.copy(faceUp = true)
                    else -> card
                }
            }
        )

        val advanced = advanceMemoryGame(almostFinished, nowMs = 2L)

        assertEquals(12, advanced.turnsUsed)
        assertEquals(0, advanced.pairsMatched)
        assertFalse(advanced.cards[first].faceUp)
        assertFalse(advanced.cards[second].faceUp)
        assertTrue(advanced.finished)
    }

    @Test
    fun rewardTable_matchesPdfRules() {
        assertEquals(0, memoryGameCoins(0, 0))
        assertEquals(5, memoryGameCoins(1, 1))
        assertEquals(10, memoryGameCoins(2, 2))
        assertEquals(35, memoryGameCoins(7, 7))
        assertEquals(40, memoryGameCoins(8, 12))
        assertEquals(50, memoryGameCoins(8, 11))
    }

    @Test
    fun buildMemoryGameResult_returnsPerfectClearBonusWhenFinishedEarly() {
        val finished = MemoryGameState(
            cards = listOf(
                MemoryCard(0, MemoryCardKind.STAR, faceUp = true, matched = true),
                MemoryCard(1, MemoryCardKind.STAR, faceUp = true, matched = true),
                MemoryCard(2, MemoryCardKind.HEART, faceUp = true, matched = true),
                MemoryCard(3, MemoryCardKind.HEART, faceUp = true, matched = true),
                MemoryCard(4, MemoryCardKind.FRUIT, faceUp = true, matched = true),
                MemoryCard(5, MemoryCardKind.FRUIT, faceUp = true, matched = true),
                MemoryCard(6, MemoryCardKind.COIN, faceUp = true, matched = true),
                MemoryCard(7, MemoryCardKind.COIN, faceUp = true, matched = true),
                MemoryCard(8, MemoryCardKind.GEM, faceUp = true, matched = true),
                MemoryCard(9, MemoryCardKind.GEM, faceUp = true, matched = true),
                MemoryCard(10, MemoryCardKind.MOON, faceUp = true, matched = true),
                MemoryCard(11, MemoryCardKind.MOON, faceUp = true, matched = true),
                MemoryCard(12, MemoryCardKind.FLOWER, faceUp = true, matched = true),
                MemoryCard(13, MemoryCardKind.FLOWER, faceUp = true, matched = true),
                MemoryCard(14, MemoryCardKind.SHELL, faceUp = true, matched = true),
                MemoryCard(15, MemoryCardKind.SHELL, faceUp = true, matched = true)
            ),
            turnsUsed = 11,
            pairsMatched = 8,
            score = 80,
            introUntilMs = 0L,
            finished = true
        )

        val result = buildMemoryGameResult(finished)
        assertNotNull(result)
        assertTrue(result!!.perfectClear)
        assertEquals(50, result.coins)
        assertEquals(80, result.score)
    }
}
