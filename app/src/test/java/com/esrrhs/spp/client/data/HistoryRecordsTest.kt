package com.esrrhs.spp.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRecordsTest {

    private fun rec(id: String) = ConnectionRecord(
        id = id,
        profileId = "p",
        profileName = "p",
        startedAtMs = 1,
        endedAtMs = 2,
        txBytes = 0,
        rxBytes = 0,
        reason = HistoryReason.DISCONNECTED,
    )

    @Test
    fun underLimit_keepsAll() {
        val list = listOf(rec("a"), rec("b"))
        assertEquals(list, HistoryRecords.trim(list, 5))
    }

    @Test
    fun overLimit_dropsOldestKeepsLatest() {
        val list = (1..10).map { rec(it.toString()) }
        val trimmed = HistoryRecords.trim(list, 3)
        assertEquals(listOf("8", "9", "10"), trimmed.map { it.id })
    }

    @Test
    fun exactlyAtLimit_isUnchanged() {
        val list = (1..5).map { rec(it.toString()) }
        assertEquals(5, HistoryRecords.trim(list, 5).size)
    }
}
