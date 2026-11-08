package com.bhu.campus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 宿舍电费：把原来 server.js 里的查询链路搬到手机上。
 * 上游是学校缴费平台，换学校时改 UPSTREAM / AREA_ID 即可。
 */
object ElectricClient {

    private const val UPSTREAM = "https://pay8.bhu.edu.cn"
    private const val AREA_ID = 1
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 20000
    private val CODE_PATTERN = Regex("^[0-9A-Za-z-]{1,20}$")

    data class Building(val value: String, val label: String, val id: Int)

    suspend fun buildings(): List<Building> = withContext(Dispatchers.IO) {
        val result = post("/jfsqBuilding/list", JSONObject().put("areaid", AREA_ID))
        val list = result.optJSONArray("building_list") ?: return@withContext emptyList()
        val out = mutableListOf<Building>()
        for (i in 0 until list.length()) {
            val raw = list.optJSONObject(i) ?: continue
            if (raw.optInt("status", 0) != 1) continue
            val name = raw.optString("name", "")
            val number = raw.optString("buildingNo", "")
            val value = if (name.isEmpty()) number else name
            out += Building(
                value = value,
                label = if (number.isEmpty()) value else number,
                id = raw.optInt("id"),
            )
        }
        out.sortedWith { a, b ->
            val x = a.value.toDoubleOrNull()
            val y = b.value.toDoubleOrNull()
            if (x != null && y != null) x.compareTo(y) else a.label.compareTo(b.label)
        }
    }

    suspend fun query(building: String, room: String): EnergySnapshot = withContext(Dispatchers.IO) {
        val buildingCode = building.trim()
        val roomCode = room.trim()
        require(buildingCode.isNotEmpty() && roomCode.isNotEmpty()) { "请填写楼号与房间号" }
        require(CODE_PATTERN.matches(buildingCode)) { "楼号格式不正确" }
        require(CODE_PATTERN.matches(roomCode)) { "房间号格式不正确" }

        val target = buildings().firstOrNull {
            it.value == buildingCode || it.label == buildingCode
        } ?: throw IllegalStateException("未找到所选楼栋")

        var matchedRoom: JSONObject? = null
        var matchedUnit = ""
        val units = post("/jfsqRoom/unitNoList", JSONObject().put("buildingid", target.id))
        val unitList = units.optJSONArray("unitNo_list")
        if (unitList != null) {
            for (i in 0 until unitList.length()) {
                if (matchedRoom != null) break
                val unit = unitList.optJSONObject(i) ?: continue
                val unitNo = unit.optString("unitNo")
                val rooms = post(
                    "/jfsqRoom/list",
                    JSONObject().put("buildingid", target.id).put("unitNo", unitNo)
                )
                val roomList = rooms.optJSONArray("room_list") ?: continue
                for (j in 0 until roomList.length()) {
                    val candidate = roomList.optJSONObject(j) ?: continue
                    if (roomCode == candidate.optString("roomNo")) {
                        matchedRoom = candidate
                        matchedUnit = unitNo
                        break
                    }
                }
            }
        }

        val room = matchedRoom ?: throw IllegalStateException("未找到该房间，请检查楼号和房间号")
        val details = post("/jfsqRoom/getInfo", JSONObject().put("roomid", room.optInt("id")))
        if (details.optInt("ErrCode", -1) != 0) {
            val message = details.optString("ErrMsg", "")
            throw IllegalStateException(message.ifBlank { "电费查询失败" })
        }
        val info = details.optJSONObject("data")
            ?: throw IllegalStateException("电费查询失败：返回数据为空")

        EnergySnapshot(
            balance = info.opt("leftMoney").asDouble(),
            areaName = info.optString("areaName"),
            buildingNo = info.optString("buildingNo"),
            unitNo = info.optString("unitNo", matchedUnit),
            roomNo = info.optString("roomNo"),
            userNo = info.optString("userNo"),
            meterNo = info.optString("meterNo"),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun Any?.asDouble(): Double = when (this) {
        null -> 0.0
        is Number -> toDouble()
        else -> toString().trim().toDoubleOrNull() ?: 0.0
    }

    private fun post(path: String, payload: JSONObject): JSONObject {
        val conn = (URL(UPSTREAM + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "campus-app/2.0")
        }
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readAll(if (code >= 400) conn.errorStream else conn.inputStream)
        conn.disconnect()
        if (code >= 400) throw IllegalStateException("上游请求失败 HTTP $code")
        if (text.isEmpty()) throw IllegalStateException("上游返回空响应")
        return JSONObject(text)
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        stream.use { input ->
            ByteArrayOutputStream().use { buffer ->
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                return String(buffer.toByteArray(), StandardCharsets.UTF_8)
            }
        }
    }
}
