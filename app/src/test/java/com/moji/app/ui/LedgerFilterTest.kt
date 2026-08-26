package com.moji.app.ui

import com.moji.app.data.CategoryEntity
import com.moji.app.data.Platform
import com.moji.app.data.TransactionEntity
import com.moji.app.data.TransactionSource
import com.moji.app.data.TransactionStatus
import com.moji.app.data.TransactionWithCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerFilterTest {
    private val row = TransactionWithCategory(
        transaction = TransactionEntity(
            amountMinor = 1680,
            merchantRaw = "瑞幸咖啡",
            categoryId = "food_drink",
            platform = Platform.WECHAT.name,
            source = TransactionSource.AUTO.name,
            status = TransactionStatus.ACTIVE.name,
            occurredAt = 1_700_000_000_000,
            note = "早餐"
        ),
        category = CategoryEntity("food_drink", name = "咖啡奶茶", icon = "☕", sortOrder = 0)
    )

    @Test fun combinesTextTimePlatformSourceAndStatusFilters() {
        val filters = LedgerFilters(
            dateFrom = row.transaction.occurredAt - 1,
            dateTo = row.transaction.occurredAt + 1,
            platforms = setOf(Platform.WECHAT.name),
            sources = setOf(TransactionSource.AUTO.name),
            statuses = setOf(TransactionStatus.ACTIVE.name)
        )
        assertTrue(row.matchesLedgerFilter("瑞幸", filters))
        assertTrue(row.matchesLedgerFilter("咖啡奶茶", filters))
        assertTrue(row.matchesLedgerFilter("早餐", filters))
        assertTrue(row.matchesLedgerFilter("1680", filters))
    }

    @Test fun rejectsEachNonMatchingStructuredFilter() {
        assertFalse(row.matchesLedgerFilter("", LedgerFilters(dateFrom = row.transaction.occurredAt + 1)))
        assertFalse(row.matchesLedgerFilter("", LedgerFilters(dateTo = row.transaction.occurredAt - 1)))
        assertFalse(row.matchesLedgerFilter("", LedgerFilters(platforms = setOf(Platform.ALIPAY.name))))
        assertFalse(row.matchesLedgerFilter("", LedgerFilters(sources = setOf(TransactionSource.MANUAL.name))))
        assertFalse(row.matchesLedgerFilter("", LedgerFilters(statuses = setOf(TransactionStatus.FULLY_REFUNDED.name))))
    }
}
