package com.bhu.campus.data

import android.content.Context

/**
 * 可以用「一句话说完」的提醒：作业/考试快到期、打水余额不足、电费快没了。
 *
 * 这些不值得占一个岛，走 [IslandHub.alert] 的焦点通知样式：手机顶部提示一下，
 * 一分钟自己收，不进通知栏堆着。
 *
 * 去重规则：
 * - 作业按 id 只提醒一次（CampusData 里任务 id 是稳定的）；
 * - 余额类一天最多一次，避免每次开 App 都弹。
 */
object IslandAlerts {

    private const val PREFS = "campus_island_alerts"
    private const val DAY_MS = 86_400_000L

    /** 24 小时内到期的作业才提醒。 */
    private const val DUE_WINDOW_MS = 24 * 3600_000L

    /** 打水余额低于这个数就提醒，单位元。 */
    private const val DRINK_LOW = 5.0

    /** 电费低于这个数就提醒。 */
    private const val POWER_LOW = 20.0

    fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 一条岛只能显示一件事，所以按「最要紧」的顺序挑一件，发完就返回
        val due = CampusStore.tasks
            .filter { task ->
                val at = task.dueAt ?: return@filter false
                !task.done && at in now..(now + DUE_WINDOW_MS)
            }
            .minByOrNull { it.dueAt ?: Long.MAX_VALUE }
        if (due != null) {
            val key = "task_" + due.id
            if (prefs.getLong(key, 0L) == 0L) {
                prefs.edit().putLong(key, now).apply()
                val hours = ((due.dueAt!! - now) / 3600_000L).coerceAtLeast(1)
                IslandHub.alert(
                    context,
                    if (due.isExam) "考试快到了" else "作业快到期",
                    "${due.title} · 还剩 $hours 小时",
                )
                return
            }
        }

        val drink = CampusStore.drinkBalance
        if (drink != null && drink < DRINK_LOW && dueToday(prefs, "drink", now)) {
            prefs.edit().putLong("drink", now).apply()
            IslandHub.alert(context, "打水余额不多了", "剩 ¥%.2f，记得充值".format(drink))
            return
        }

        val power = CampusStore.energy?.balance
        if (power != null && power < POWER_LOW && power > 0 && dueToday(prefs, "power", now)) {
            prefs.edit().putLong("power", now).apply()
            IslandHub.alert(context, "电费快用完了", "剩 ¥%.2f".format(power))
        }
    }

    /** 距离上次这类提醒是否超过一天。 */
    private fun dueToday(
        prefs: android.content.SharedPreferences,
        key: String,
        now: Long,
    ): Boolean = now - prefs.getLong(key, 0L) > DAY_MS
}
