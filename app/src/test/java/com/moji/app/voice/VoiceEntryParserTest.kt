package com.moji.app.voice

import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class VoiceEntryParserTest {
    private val categories = listOf(
        CategoryEntity("meal", name = "正餐", icon = "🍚", sortOrder = 0),
        CategoryEntity("delivery", name = "外卖", icon = "🥡", sortOrder = 1),
        CategoryEntity("coffee", name = "咖啡奶茶", icon = "☕", sortOrder = 2),
        CategoryEntity("taxi", name = "打车", icon = "🚕", sortOrder = 3),
        CategoryEntity("daily", name = "日用品", icon = "🧻", sortOrder = 4),
        CategoryEntity("income", name = "收入", icon = "↑", sortOrder = 5)
    )
    private val now = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 29, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun parsesPrdExamplesAndChineseAmounts() {
        val meal = VoiceEntryParser.parse("今天吃饭花了十五块钱", categories, now)
        assertEquals(1500L, meal.amountMinor); assertEquals(listOf("meal"), meal.categoryIds)
        val taxi = VoiceEntryParser.parse("昨晚打车三十二元", categories, now)
        assertEquals(3200L, taxi.amountMinor); assertEquals(listOf("taxi"), taxi.categoryIds)
        assertEquals(28, Calendar.getInstance().apply { timeInMillis = taxi.occurredAt }.get(Calendar.DAY_OF_MONTH))
        assertEquals(10550L, VoiceEntryParser.parse("买咖啡一百零五块五", categories, now).amountMinor)
        assertEquals(1250L, VoiceEntryParser.parse("买咖啡12.5元", categories, now).amountMinor)
    }

    @Test fun keepsAmbiguousAndMissingFieldsForManualConfirmation() {
        assertNull(VoiceEntryParser.parse("今天吃饭", categories, now).amountMinor)
        assertTrue(VoiceEntryParser.parse("花了十五块钱", categories, now).categoryIds.isEmpty())
        assertEquals(setOf("coffee", "daily"), VoiceEntryParser.parse("买咖啡和纸巾一共三十", categories, now).categoryIds.toSet())
    }

    @Test fun recognizesIncomeAndSpecificMonthDay() {
        val income = VoiceEntryParser.parse("8月20日收到工资五千元", categories, now)
        assertEquals(Direction.INCOME, income.direction)
        assertEquals(500000L, income.amountMinor)
        val date = Calendar.getInstance().apply { timeInMillis = income.occurredAt }
        assertEquals(Calendar.AUGUST, date.get(Calendar.MONTH)); assertEquals(20, date.get(Calendar.DAY_OF_MONTH))
    }
}
