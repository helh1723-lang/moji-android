package com.moji.app.voice

import com.moji.app.data.CategoryEntity
import com.moji.app.data.Direction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

/** Local, deterministic parsing only. The transcript is intentionally not persisted. */
data class VoiceDraft(val rawText: String, val amountMinor: Long?, val categoryIds: List<String>, val occurredAt: Long, val note: String?, val direction: Direction)

object VoiceEntryParser {
    private data class Keyword(val categoryName: String, val terms: List<String>)
    private data class AmountToken(val range: IntRange, val number: String, val fraction: String? = null)

    // Only built-in categories are matched; custom-category matching remains intentionally deferred.
    private val categoryKeywords = listOf(
        Keyword("外卖", listOf("外卖", "点外卖", "饿了么", "美团外卖", "配送", "送餐")),
        Keyword("咖啡奶茶", listOf("咖啡", "奶茶", "果茶", "茶饮", "饮料", "冰美式", "拿铁", "瑞幸", "星巴克", "蜜雪冰城", "喜茶", "奈雪", "霸王茶姬", "古茗", "茶百道", "沪上阿姨", "一点点", "书亦", "茶颜悦色")),
        Keyword("零食", listOf("零食", "薯片", "饼干", "辣条", "糖果", "巧克力", "坚果", "瓜子", "果冻", "膨化食品")),
        Keyword("公交地铁", listOf("公交", "地铁", "公交卡", "地铁卡", "轻轨", "有轨电车")),
        Keyword("打车", listOf("打车", "出租车", "网约车", "滴滴", "高德打车", "快车", "专车")),
        Keyword("日用品", listOf("日用品", "纸巾", "洗衣液", "洗发水", "沐浴露", "牙膏", "牙刷", "卫生纸", "湿巾", "垃圾袋", "清洁剂", "买菜", "菜市场")),
        Keyword("网购", listOf("淘宝", "天猫", "京东", "拼多多", "网购", "网上买", "抖音商城")),
        Keyword("娱乐", listOf("电影", "游戏", "唱歌", "游乐园", "演唱会", "剧本杀", "桌游", "KTV")),
        Keyword("生活", listOf("房租", "水电", "电费", "水费", "燃气", "物业", "宽带", "话费")),
        Keyword("学习", listOf("课程", "学习资料", "教材", "考研", "书籍", "买书", "培训")),
        Keyword("医疗", listOf("看病", "买药", "医院", "诊所", "挂号", "体检", "药店")),
        Keyword("人情", listOf("红包", "礼物", "份子钱", "随礼", "礼金")),
        Keyword("正餐", listOf("吃饭", "早餐", "早饭", "午餐", "午饭", "晚餐", "晚饭", "正餐", "餐馆", "餐厅", "饭店", "小吃", "面馆", "米粉", "麻辣烫", "火锅", "烧烤", "盖饭"))
    )
    private val chineseNumber = "[零〇一二两三四五六七八九十百千万亿]+"
    private val numberToken = "(?:\\d+(?:\\.\\d+)?|$chineseNumber)"
    private val amountWithUnit = Regex("($numberToken)(?:元|块(?:钱)?)([零一二三四五六七八九])?")
    private val amountAfterVerb = Regex("(?:花了|花|消费|用了|收到|工资|退款|还钱|一共|总共)\\s*($numberToken)(?=\\s*(?:[，,。；;]|$|然后|再|和|买|喝|吃|打车|点))")
    private val drinkPrice = Regex("(?:一(?:杯|份|个|碗|盒|瓶)?|[杯份个碗盒瓶])?\\s*($numberToken)的")
    private val monthDay = Regex("(?<!\\d)(1[0-2]|0?[1-9])月(3[01]|[12]\\d|0?[1-9])日?")

    fun parseAll(raw: String, categories: List<CategoryEntity>, now: Long = System.currentTimeMillis()): List<VoiceDraft> {
        val text = raw.trim().take(400)
        val amounts = amountTokens(text)
        if (amounts.isEmpty()) return listOf(draft(text, null, categories, now))
        return amounts.mapIndexed { index, amount ->
            val start = if (index == 0) 0 else amounts[index - 1].range.last + 1
            // Prices such as “一杯 25 的蜜雪冰城奶茶” describe their category after
            // the number, while conventional “花了 25 元” phrases end at the amount.
            val endExclusive = if (text.substring(amount.range).endsWith("的")) {
                val afterAmount = amount.range.last + 1
                val delimiter = text.indexOfAny(charArrayOf('，', ',', '。', '；', ';'), afterAmount).takeIf { it >= 0 } ?: text.length
                val nextAmount = amounts.getOrNull(index + 1)?.range?.first ?: text.length
                minOf(delimiter, nextAmount)
            } else amount.range.last + 1
            val clause = text.substring(start, endExclusive).trim('，', ',', '。', '；', ';', ' ')
            draft(clause, amount, categories, now, occurredAt(text, now))
        }
    }

    fun parse(raw: String, categories: List<CategoryEntity>, now: Long = System.currentTimeMillis()): VoiceDraft = parseAll(raw, categories, now).first()

    private fun draft(text: String, amount: AmountToken?, categories: List<CategoryEntity>, now: Long, occurredAt: Long = occurredAt(text, now)) = VoiceDraft(
        rawText = text,
        amountMinor = amount?.let(::amountMinor) ?: amountMinor(text),
        categoryIds = categoryIds(text, categories),
        occurredAt = occurredAt,
        note = note(text),
        direction = direction(text)
    )

    private fun amountTokens(text: String): List<AmountToken> {
        val candidates = buildList {
            amountWithUnit.findAll(text).forEach { add(AmountToken(it.range, it.groupValues[1], it.groupValues[2].ifBlank { null })) }
            amountAfterVerb.findAll(text).forEach { add(AmountToken(it.range, it.groupValues[1])) }
            drinkPrice.findAll(text).forEach { add(AmountToken(it.range, it.groupValues[1])) }
        }.sortedBy { it.range.first }
        return candidates.fold(mutableListOf()) { accepted, candidate ->
            if (accepted.none { candidate.range.first <= it.range.last && it.range.first <= candidate.range.last }) accepted += candidate
            accepted
        }
    }

    private fun amountMinor(text: String): Long? = amountTokens(text).firstOrNull()?.let(::amountMinor)

    private fun amountMinor(token: AmountToken): Long? {
        val value = parseNumber(token.number) ?: return null
        val fraction = token.fraction?.let(::parseChineseInteger)
        val decimal = if (fraction != null) BigDecimal.valueOf(value).add(BigDecimal.valueOf(fraction, 1)) else BigDecimal.valueOf(value)
        return runCatching { decimal.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it > 0 } }.getOrNull()
    }

    private fun parseNumber(value: String): Double? = value.toDoubleOrNull() ?: parseChineseInteger(value)?.toDouble()

    private fun parseChineseInteger(value: String): Long? {
        if (value.isBlank()) return null
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var result = 0L; var section = 0L; var current = 0L
        value.forEach { char -> when (char) {
            in digit.keys -> current = digit.getValue(char).toLong()
            '十' -> { section += (if (current == 0L) 1 else current) * 10; current = 0 }
            '百' -> { section += (if (current == 0L) 1 else current) * 100; current = 0 }
            '千' -> { section += (if (current == 0L) 1 else current) * 1_000; current = 0 }
            '万' -> { result += (section + current).coerceAtLeast(1) * 10_000; section = 0; current = 0 }
            '亿' -> { result += (section + current).coerceAtLeast(1) * 100_000_000; section = 0; current = 0 }
            else -> return null
        } }
        return result + section + current
    }

    private fun categoryIds(text: String, categories: List<CategoryEntity>): List<String> {
        val names = categoryKeywords.map { it.categoryName }.toSet() + "收入"
        val visibleSystem = categories.filterNot { it.hidden }.filter { it.name in names }
        if (direction(text) == Direction.INCOME) return visibleSystem.filter { it.name == "收入" }.map { it.id }
        val matched = categoryKeywords.filter { item -> item.terms.any(text::contains) }.map { it.categoryName }
        val resolved = if ("外卖" in matched) listOf("外卖") else matched
        return resolved.distinct().flatMap { name -> visibleSystem.filter { it.name == name }.map { it.id } }
    }

    private fun occurredAt(text: String, now: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        when {
            text.contains("前天") -> calendar.add(Calendar.DAY_OF_YEAR, -2)
            text.contains("昨天") || text.contains("昨晚") -> calendar.add(Calendar.DAY_OF_YEAR, -1)
            monthDay.find(text) != null -> monthDay.find(text)!!.let { calendar.set(Calendar.MONTH, it.groupValues[1].toInt() - 1); calendar.set(Calendar.DAY_OF_MONTH, it.groupValues[2].toInt()) }
        }
        return calendar.timeInMillis
    }

    private fun direction(text: String) = if (listOf("工资到账", "收到工资", "收到红包", "收到退款", "别人还钱").any(text::contains)) Direction.INCOME else Direction.EXPENSE

    private fun note(text: String): String? {
        var result = text.replace(Regex("今天|昨天|前天|昨晚|(?<!\\d)(?:1[0-2]|0?[1-9])月(?:3[01]|[12]\\d|0?[1-9])日?"), "")
        amountTokens(result).sortedByDescending { it.range.first }.forEach { token -> result = result.removeRange(token.range) }
        result = result.replace(Regex("(?:一共|总共|花了|花|消费|用了|买了|买|支付|付款|元|块钱|块|一杯|一份)"), "")
        return result.trim('，', '。', ',', '.', ' ').take(100).takeIf { it.isNotBlank() }
    }
}
