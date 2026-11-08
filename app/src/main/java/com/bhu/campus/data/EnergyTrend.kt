package com.bhu.campus.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 电费走势的统计粒度。 */
enum class EnergyRange(val label: String, val hint: String) {
    WEEK("周", "最近 7 天"),
    MONTH("月", "最近 30 天"),
    YEAR("年", "最近 12 个月"),
}

/**
 * 折线图上的一个横轴槽位。
 *
 * `balance` 为 null 表示这个时段没有查询记录 —— 折线仍然推进横轴，
 * 但跳过这个点，这样"某几天忘了查"不会把曲线挤成一团。
 */
data class TrendSlot(
    val label: String,
    val balance: Double?,
)

/** 把逐次的电费记录按时间聚合到 周 / 月 / 年 的横轴槽位上。 */
object EnergyTrend {

    fun build(
        history: List<EnergyPoint>,
        range: EnergyRange,
        today: LocalDate = LocalDate.now(),
    ): List<TrendSlot> {
        if (history.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

        // 同一天只保留最后一条
        val byDay = history
            .groupBy { dayOf(it.fetchedAt) }
            .mapValues { (_, list) -> list.maxBy { it.fetchedAt } }

        return when (range) {
            EnergyRange.WEEK -> {
                val start = today.minusDays(6)
                (0 until 7).map { i ->
                    val day = start.plusDays(i.toLong())
                    TrendSlot(
                        label = "${day.monthValue}/${day.dayOfMonth}",
                        balance = byDay[day]?.balance,
                    )
                }
            }

            EnergyRange.MONTH -> {
                val start = today.minusDays(29)
                (0 until 30).map { i ->
                    val day = start.plusDays(i.toLong())
                    TrendSlot(
                        label = if (i % 6 == 0 || i == 29) "${day.monthValue}/${day.dayOfMonth}" else "",
                        balance = byDay[day]?.balance,
                    )
                }
            }

            EnergyRange.YEAR -> {
                val months = (0 until 12).map { back ->
                    val anchor = today.minusMonths(back.toLong())
                    anchor.year to anchor.monthValue
                }.reversed()
                months.map { (year, month) ->
                    // 该月最后一次查询
                    val hit = history
                        .filter { dayOf(it.fetchedAt).let { d -> d.year == year && d.monthValue == month } }
                        .maxByOrNull { it.fetchedAt }
                    TrendSlot(label = "${month}月", balance = hit?.balance)
                }
            }
        }
    }

    /** 有记录的槽位里最低的余额；全空返回 null。 */
    fun minOf(slots: List<TrendSlot>): Double? = slots.mapNotNull { it.balance }.minOrNull()

    /** 有记录的槽位的平均余额。 */
    fun averageOf(slots: List<TrendSlot>): Double? {
        val values = slots.mapNotNull { it.balance }
        return if (values.isEmpty()) null else values.average()
    }
}
