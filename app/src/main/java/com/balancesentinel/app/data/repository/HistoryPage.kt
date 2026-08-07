package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.RawRecord

data class HistoryCursor(
    val recordedAt: Long,
    val id: Long
)

data class HistoryRecord(
    val id: Long,
    val value: RawRecord
)

data class HistoryPage(
    val records: List<HistoryRecord>,
    val nextCursor: HistoryCursor?
)
