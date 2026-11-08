package com.bhu.campus.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 今日天气，数据源 open-meteo.com（免费、无需 key、按经纬度查询）。
 *
 * 坐标从系统定位取（网络定位优先，失败退 GPS，都没有才用学校兜底坐标）。
 * 城市名用系统 Geocoder 反查，查不到就显示「当前位置」。
 *
 * 两层缓存：
 *  - 坐标缓存 30 分钟（走一趟 GPS 挺费电，短时间内不重复取）
 *  - 天气缓存 2 小时 + 必须同一天
 */
object WeatherClient {

    private const val PREFS = "campus_weather_v1"

    /** 完全拿不到定位时的兜底：渤海大学滨海校区附近。 */
    private const val FALLBACK_LAT = 40.9957
    private const val FALLBACK_LON = 121.1267
    private const val FALLBACK_CITY = "锦州"

    /**
     * 天气缓存的键。v3.8 把旧的 "json" 换成 "json2"：旧版本在反查城市失败时
     * 会往缓存里写死「当前位置」，那份数据会挡住后续重试，换键让它直接作废。
     */
    private const val KEY_JSON = "json2"

    /** 坐标缓存多久算过期。 */
    private const val LOC_TTL_MS = 30 * 60 * 1000L

    /** 比这还老的 lastKnownLocation 就不信了，主动要一次新的。 */
    private const val LOC_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** 当前缓存的天气；UI 直接读这个字段。 */
    @Volatile
    var current: WeatherInfo? = null
        private set

    /** 是否已经拿到过定位权限。UI 用它决定要不要弹授权。 */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null) ?: return
        current = runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    /** 今天已经拿到过数据、且不超过 2 小时，就直接用缓存。 */
    fun isFresh(): Boolean {
        val info = current ?: return false
        val now = System.currentTimeMillis()
        val fetchedDay = java.time.Instant.ofEpochMilli(info.fetchedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        return fetchedDay == LocalDate.now() && now - info.fetchedAt < 2 * 3600_000L
    }

    /** 联网刷新一次。失败抛异常，由调用方决定要不要提示。 */
    suspend fun refresh(context: Context): WeatherInfo = withContext(Dispatchers.IO) {
        val place = resolvePlace(context)
        val json = JSONObject(http(api(place.lat, place.lon)))
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val code = cur.optInt("weather_code", -1)
        val info = WeatherInfo(
            temp = cur.optDouble("temperature_2m", 0.0).roundToInt(),
            feelsLike = cur.optDouble("apparent_temperature", 0.0).roundToInt(),
            high = daily.optJSONArray("temperature_2m_max")?.optDouble(0, 0.0)?.roundToInt() ?: 0,
            low = daily.optJSONArray("temperature_2m_min")?.optDouble(0, 0.0)?.roundToInt() ?: 0,
            humidity = cur.optInt("relative_humidity_2m", 0),
            wind = cur.optDouble("wind_speed_10m", 0.0).roundToInt(),
            code = code,
            text = describe(code),
            emoji = emojiOf(code, cur.optInt("is_day", 1) == 1),
            city = place.city,
            fetchedAt = System.currentTimeMillis(),
        )
        current = info
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, encode(info)).apply()
        info
    }

    /**
     * 有缓存就先用缓存，过期再偷偷刷一次；全程不抛异常。
     *
     * 另外：缓存里城市名是空的（上次反查城市没成功）也算「要刷新」，
     * 否则会一直停在没城市名的那份数据上，等两小时才有机会重试。
     */
    suspend fun refreshIfStale(context: Context) {
        val missingCity = current.let { it != null && (it.city.isBlank() || it.city == "当前位置") }
        if (isFresh() && !missingCity) return
        runCatching { refresh(context) }
    }

    /* ---------------- 定位 ---------------- */

    private data class Place(val lat: Double, val lon: Double, val city: String)

    private suspend fun resolvePlace(context: Context): Place {
        val loc = locate(context) ?: return Place(FALLBACK_LAT, FALLBACK_LON, FALLBACK_CITY)
        val city = cityName(context, loc.latitude, loc.longitude)
        return Place(loc.latitude, loc.longitude, city)
    }

    private suspend fun locate(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        // 1) 30 分钟内取过的坐标直接用
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedAt = prefs.getLong("locAt", 0L)
        if (System.currentTimeMillis() - cachedAt in 0 until LOC_TTL_MS) {
            val lat = prefs.getString("locLat", null)?.toDoubleOrNull()
            val lon = prefs.getString("locLon", null)?.toDoubleOrNull()
            if (lat != null && lon != null) return Location("cache").apply {
                latitude = lat
                longitude = lon
            }
        }

        // 2) 系统最后已知位置（取最新的那个）
        val last = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        val usable = last?.takeIf { System.currentTimeMillis() - it.time < LOC_MAX_AGE_MS }
        val result = usable ?: singleFix(lm) ?: last
        if (result != null) {
            prefs.edit()
                .putString("locLat", result.latitude.toString())
                .putString("locLon", result.longitude.toString())
                .putLong("locAt", System.currentTimeMillis())
                .apply()
        }
        return result
    }

    /** 主动要一次定位，最多等 6 秒。没有可用的 provider 就返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun singleFix(lm: LocationManager): Location? = withTimeoutOrNull(6000L) {
        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            else -> null
        } ?: return@withTimeoutOrNull null

        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
            }
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * 坐标 → 城市名。
     *
     * 系统 Geocoder 在国内多数 ROM（小米/华为）走厂商自己的定位服务，可用但要等，
     * 所以给足 6 秒；实在拿不到就返回空串，界面上干脆不显示这一行，别硬写一个错的。
     *
     * 城市名按坐标缓存：位置挪动不到 0.3°（约 30km）就复用上次的结果，不重复反查。
     */
    @SuppressLint("MissingPermission")
    private suspend fun cityName(context: Context, lat: Double, lon: Double): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cLat = prefs.getString("cityLat", null)?.toDoubleOrNull()
        val cLon = prefs.getString("cityLon", null)?.toDoubleOrNull()
        val cached = prefs.getString("cityName", null)
        if (!cached.isNullOrBlank() && cLat != null && cLon != null &&
            abs(cLat - lat) < 0.3 && abs(cLon - lon) < 0.3
        ) {
            return cached
        }

        val name = withTimeoutOrNull(6000L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!Geocoder.isPresent()) return@runCatching null
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)
                        ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                }.getOrNull()
            }
        }

        if (!name.isNullOrBlank()) {
            prefs.edit()
                .putString("cityName", name)
                .putString("cityLat", lat.toString())
                .putString("cityLon", lon.toString())
                .apply()
            return name
        }
        android.util.Log.w("CampusWeather", "反查城市名失败 lat=$lat lon=$lon")
        return ""
    }

    /* ---------------- 内部 ---------------- */

    private fun api(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=Asia%2FShanghai&forecast_days=1"

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("天气接口返回 $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather code → 中文描述。 */
    private fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "未知"
    }

    private fun emojiOf(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()

    private fun encode(info: WeatherInfo): String = JSONObject()
        .put("temp", info.temp)
        .put("feelsLike", info.feelsLike)
        .put("high", info.high)
        .put("low", info.low)
        .put("humidity", info.humidity)
        .put("wind", info.wind)
        .put("code", info.code)
        .put("text", info.text)
        .put("emoji", info.emoji)
        .put("city", info.city)
        .put("fetchedAt", info.fetchedAt)
        .toString()

    private fun decode(o: JSONObject): WeatherInfo = WeatherInfo(
        temp = o.optInt("temp"),
        feelsLike = o.optInt("feelsLike"),
        high = o.optInt("high"),
        low = o.optInt("low"),
        humidity = o.optInt("humidity"),
        wind = o.optInt("wind"),
        code = o.optInt("code"),
        text = o.optString("text", ""),
        emoji = o.optString("emoji", "🌡️"),
        city = o.optString("city", ""),
        fetchedAt = o.optLong("fetchedAt"),
    )
}

data class WeatherInfo(
    val temp: Int = 0,
    val feelsLike: Int = 0,
    val high: Int = 0,
    val low: Int = 0,
    val humidity: Int = 0,
    val wind: Int = 0,
    val code: Int = -1,
    val text: String = "",
    val emoji: String = "🌡️",
    val city: String = "",
    val fetchedAt: Long = 0L,
)
