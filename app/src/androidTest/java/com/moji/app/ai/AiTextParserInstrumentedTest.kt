package com.moji.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class AiTextParserInstrumentedTest {
    private val categories = listOf(
        CategoryEntity("meal", name = "正餐", icon = "🍜", sortOrder = 0),
        CategoryEntity("online", name = "网购", icon = "📦", sortOrder = 1),
        CategoryEntity("custom", name = "猫咪用品", icon = "🐱", sortOrder = 2),
        CategoryEntity("hidden", name = "旧分类", icon = "◌", sortOrder = 3, hidden = true)
    )
    private val now = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 29, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun acceptsMultipleValidatedDraftsAndCustomCategories() {
        val response = """{"transactions":[
          {"amount":35,"direction":"EXPENSE","category":"正餐","occurred_date":"2026-08-29","merchant":"食堂","note":"午饭","description":"吃饭花了35元"},
          {"amount":88.5,"direction":"EXPENSE","category":"猫咪用品","occurred_date":"","merchant":"","note":"猫砂","description":"买猫砂"}
        ]}"""
        val drafts = AiTextParser.parseResponse(response, categories, now)
        assertEquals(2, drafts.size)
        assertEquals(3500L, drafts[0].amountMinor)
        assertEquals(listOf("meal"), drafts[0].categoryIds)
        assertEquals("食堂", drafts[0].merchant)
        assertEquals(8850L, drafts[1].amountMinor)
        assertEquals(listOf("custom"), drafts[1].categoryIds)
    }

    @Test fun doesNotAcceptUnknownOrHiddenCategoriesAsAssignments() {
        val response = """{"transactions":[{"amount":12,"direction":"INCOME","category":"旧分类","occurred_date":"","merchant":"","note":"","description":"测试"}]}"""
        val draft = AiTextParser.parseResponse(response, categories, now).single()
        assertEquals(Direction.INCOME, draft.direction)
        assertTrue(draft.categoryIds.isEmpty())
    }
}
