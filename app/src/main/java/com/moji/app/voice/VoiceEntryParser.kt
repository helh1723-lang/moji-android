package com.moji.app.voice

import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

/** Local, deterministic parsing only. The transcript is intentionally not persisted. */
data class VoiceDraft(
    val rawText: String,
    val amountMinor: Long?,
    val categoryIds: List<String>,
    val occurredAt: Long,
    val note: String?,
    val direction: Direction
)

object VoiceEntryParser {
    private data class Keyword(val categoryName: String, val terms: List<String>)
    private val categoryKeywords = listOf(
        Keyword("正餐", listOf("吃饭", "早餐", "午餐", "晚餐", "餐馆", "吃东西")),
        Keyword("外卖", listOf("外卖", "点外卖", "配送")),
        Keyword("咖啡奶茶", listOf("咖啡", "奶茶", "饮料")),
        Keyword("零食", listOf("零食", "薯片", "饼干")),
        Keyword("公交地铁", listOf("公交", "地铁", "公交卡")),
        Keyword("打车", listOf("打车", "出租车", "网约车")),
        Keyword("日用品", listOf("买菜", "纸巾", "洗衣液", "日用品", "超市")),
        Keyword("网购", listOf("淘宝", "京东", "网购", "网上买")),
        Keyword("娱乐", listOf("电影", "游戏", "唱歌", "游乐园")),
        Keyword("生活", listOf("房租", "水电", "物业")),
        Keyword("学习", listOf("书", "课程", "学习资料")),
        Keyword("医疗", listOf("看病", "买药", "医院")),
        Keyword("人情", listOf("红包", "礼物", "份子钱"))
    )
    private val chineseNumber = "[零〇一二两三四五六七八九十百千万亿]+"
    private val numberToken = "(?:\\d+(?:\\.\\d+)?|$chineseNumber)"
    private val amountWithUnit = Regex("($numberToken)(?:元|块(?:钱)?)([零一二三四五六七八九])?")
    private val amountAfterVerb = Regex("(?:花了|花|消费|用了|收到|工资|退款|还钱|一共|总共)\\s*($numberToken)")
    private val monthDay = Regex("(?<!\\d)(1[0-2]|0?[1-9])月(3[01]|[12]\\d|0?[1-9])日?")

    fun parse(raw: String, categories: List<CategoryEntity>, now: Long = System.currentTimeMillis()): VoiceDraft {
        val text = raw.trim().take(200)
        return VoiceDraft(
            rawText = text,
            amountMinor = amountMinor(text),
            categoryIds = categoryIds(text, categories),
            occurredAt = occurredAt(text, now),
            note = note(text),
            direction = direction(text)
        )
    }

    private fun amountMinor(text: String): Long? {
        val match = amountWithUnit.find(text)
        val rawNumber = match?.groupValues?.getOrNull(1)
            ?: amountAfterVerb.find(text)?.groupValues?.getOrNull(1)
            ?: return null
        val value = parseNumber(rawNumber) ?: return null
        val fraction = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::parseChineseInteger)
        val decimal = if (fraction != null) BigDecimal.valueOf(value).add(BigDecimal.valueOf(fraction, 1)) else BigDecimal.valueOf(value)
        return runCatching {
            decimal.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it > 0 }
        }.getOrNull()
    }

    private fun parseNumber(value: String): Double? = value.toDoubleOrNull() ?: parseChineseInteger(value)?.toDouble()

    private fun parseChineseInteger(value: String): Long? {
        if (value.isBlank()) return null
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var result = 0L
        var section = 0L
        var current = 0L
        value.forEach { char ->
            when (char) {
                in digit.keys -> current = digit.getValue(char).toLong()
                '十' -> { section += (if (current == 0L) 1 else current) * 10; current = 0 }
                '百' -> { section += (if (current == 0L) 1 else current) * 100; current = 0 }
                '千' -> { section += (if (current == 0L) 1 else current) * 1_000; current = 0 }
                '万' -> { result += (section + current).coerceAtLeast(1) * 10_000; section = 0; current = 0 }
                '亿' -> { result += (section + current).coerceAtLeast(1) * 100_000_000; section = 0; current = 0 }
                else -> return null
            }
        }
        return result + section + current
    }

    private fun categoryIds(text: String, categories: List<CategoryEntity>): List<String> {
        val visible = categories.filterNot { it.hidden }
        val matchedNames = categoryKeywords.filter { item -> item.terms.any(text::contains) }.map { it.categoryName }.toMutableSet()
        visible.filter { it.name.isNotBlank() && text.contains(it.name) }.forEach { matchedNames += it.name }
        return visible.filter { it.name in matchedNames }.map { it.id }
    }

    private fun occurredAt(text: String, now: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        when {
            text.contains("前天") -> calendar.add(Calendar.DAY_OF_YEAR, -2)
            text.contains("昨天") || text.contains("昨晚") -> calendar.add(Calendar.DAY_OF_YEAR, -1)
            monthDay.find(text) != null -> {
                val match = monthDay.find(text)!!
                calendar.set(Calendar.MONTH, match.groupValues[1].toInt() - 1)
                calendar.set(Calendar.DAY_OF_MONTH, match.groupValues[2].toInt())
            }
        }
        return calendar.timeInMillis
    }

    private fun direction(text: String) = when {
        listOf("工资到账", "收到工资", "收到红包", "收到退款", "别人还钱").any(text::contains) -> Direction.INCOME
        else -> Direction.EXPENSE
    }

    private fun note(text: String): String? {
        var result = text
        result = result.replace(Regex("今天|昨天|前天|昨晚|(?<!\\d)(?:1[0-2]|0?[1-9])月(?:3[01]|[12]\\d|0?[1-9])日?"), "")
        result = result.replace(amountWithUnit, "").replace(amountAfterVerb, "")
        result = result.replace(Regex("(?:一共|总共|花了|花|消费|用了|买了|买|支付|付款|元|块钱|块)"), "")
        return result.trim('，', '。', ',', '.', ' ').take(100).takeIf { it.isNotBlank() }
    }
}
