package com.moji.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MojiDaoInstrumentedTest {
    private lateinit var database: MojiDatabase
    private lateinit var dao: MojiDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MojiDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    @Test fun rangeQueriesRulesAndCandidateCleanupStayBounded() = runBlocking {
        dao.insertTransaction(TransactionEntity(id = "old", amountMinor = 100, categoryId = "other", occurredAt = 1_000))
        dao.insertTransaction(TransactionEntity(id = "current", amountMinor = 200, categoryId = "other", occurredAt = 2_000))
        assertEquals(listOf("current"), dao.observeTransactionsBetween(1_500, 2_500).first().map { it.id })

        val rule = MerchantRuleEntity(pattern = "coffee", normalizedBrand = "coffee", matchType = "KEYWORD", categoryId = "other")
        dao.upsertRule(rule)
        assertTrue(dao.observeRules().first().single().enabled)
        dao.setRuleEnabled(rule.id, false, 3_000)
        assertTrue(!dao.observeRules().first().single().enabled)
        dao.deleteRule(rule.id)
        assertTrue(dao.observeRules().first().isEmpty())

        dao.upsertCandidate(TransactionCandidateEntity(id = "terminal", platform = "WECHAT", direction = "EXPENSE", amountMinor = 100, merchant = null, occurredAt = 1_000, confidence = 1f, status = CandidateStatus.AUTO_POSTED.name, dedupeKey = "terminal", updatedAt = 1_000))
        dao.upsertCandidate(TransactionCandidateEntity(id = "pending", platform = "WECHAT", direction = null, amountMinor = 100, merchant = null, occurredAt = 1_000, confidence = 0.7f, status = CandidateStatus.PENDING_REVIEW.name, dedupeKey = "pending", updatedAt = 1_000))
        dao.pruneTerminalCandidates(2_000)
        assertEquals(listOf("pending"), dao.observePendingCandidates().first().map { it.id })
    }
}
