package com.bhu.campus.data

import java.time.LocalDate

/**
 * 电费查询的统一入口：查询 → 落库 → 记下"今天查过了"。
 * 首页的查询按钮和每天第一次打开的自动查询都走这里。
 */
object EnergySync {

    fun isConfigured(): Boolean =
        CampusStore.settings.building.isNotBlank() && CampusStore.settings.room.isNotBlank()

    fun queriedToday(today: LocalDate = LocalDate.now()): Boolean =
        CampusStore.settings.lastQueryDay == today.toString()

    /** 用已保存的楼号房间号查询。 */
    suspend fun refresh(): EnergySnapshot {
        require(isConfigured()) { "先在电费页填好楼号和房间号" }
        val settings = CampusStore.settings
        return refresh(settings.building, settings.room)
    }

    /** 用指定楼号房间号查询（电费页手填的那两个框），成功后会一并保存。 */
    suspend fun refresh(building: String, room: String): EnergySnapshot {
        val b = building.trim()
        val r = room.trim()
        require(b.isNotEmpty() && r.isNotEmpty()) { "请填写楼号与房间号" }
        val snapshot = ElectricClient.query(b, r)
        commit(snapshot, b, r)
        return snapshot
    }

    /**
     * 启动时自动查电费：上次查询距今超过 [maxAgeMs] 才查。
     *
     * 以前是「每天第一次」，现在改成按时间间隔（默认 2 小时），
     * 这样一天里多次打开也能看到较新的余额，又不会每次开 App 都打一次接口。
     * 关掉了电费功能、没配置房间号，都返回 null 什么都不做。
     */
    suspend fun autoIfStale(maxAgeMs: Long = 2 * 3600_000L): EnergySnapshot? {
        if (!CampusStore.settings.showEnergy) return null
        if (!isConfigured()) return null
        val last = CampusStore.energy?.fetchedAt ?: 0L
        if (last > 0 && System.currentTimeMillis() - last < maxAgeMs) return null
        val settings = CampusStore.settings
        val snapshot = ElectricClient.query(settings.building, settings.room)
        commit(snapshot, settings.building, settings.room)
        return snapshot
    }

    /**
     * 每天第一次打开 App 时自动查一次。
     * 关掉了电费功能、没配置房间号、或今天已经查过，都返回 null 什么都不做。
     */
    suspend fun autoOncePerDay(): EnergySnapshot? {
        if (!CampusStore.settings.showEnergy) return null
        if (!isConfigured() || queriedToday()) return null
        val settings = CampusStore.settings
        val snapshot = ElectricClient.query(settings.building, settings.room)
        commit(snapshot, settings.building, settings.room)
        return snapshot
    }

    private fun commit(snapshot: EnergySnapshot, building: String, room: String) {
        CampusStore.recordEnergy(snapshot)
        CampusStore.updateSettings {
            it.copy(
                building = building,
                room = room,
                lastQueryDay = LocalDate.now().toString(),
            )
        }
    }
}
