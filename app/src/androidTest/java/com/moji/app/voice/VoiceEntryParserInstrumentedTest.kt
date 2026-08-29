package com.moji.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moji.app.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class VoiceEntryParserInstrumentedTest {
    private val categories = listOf(
        CategoryEntity("meal", name = "正餐", icon = "🍚", sortOrder = 0),
        CategoryEntity("snack", name = "零食", icon = "🍪", sortOrder = 1),
        CategoryEntity("coffee", name = "咖啡奶茶", icon = "☕", sortOrder = 2),
        CategoryEntity("delivery", name = "外卖", icon = "🥡", sortOrder = 3)
    )
    private val now = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 29, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun parsesTypedOrInputMethodTextIntoSeparateConfirmedDrafts() {
        val drafts = VoiceEntryParser.parseAll("今天吃饭花了35块，买零食花了10块", categories, now)
        assertEquals(listOf(3500L, 1000L), drafts.map { it.amountMinor })
        assertEquals(listOf(listOf("meal"), listOf("snack")), drafts.map { it.categoryIds })

        val milkTea = VoiceEntryParser.parse("今天喝了一杯25的蜜雪冰城奶茶", categories, now)
        assertEquals(2500L, milkTea.amountMinor)
        assertEquals(listOf("coffee"), milkTea.categoryIds)
    }
}
