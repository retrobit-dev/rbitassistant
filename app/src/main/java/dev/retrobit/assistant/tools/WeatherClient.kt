package dev.retrobit.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Cuaca dari Open-Meteo: gratis, tanpa API key, tanpa kuota harian untuk pemakaian pribadi. */
class WeatherClient {
    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    suspend fun report(place: String, tomorrow: Boolean): ToolOutcome.Done = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(place, "UTF-8")
            val geo = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=id&format=json")
            val loc = geo.optJSONArray("results")?.optJSONObject(0)
                ?: return@withContext ToolOutcome.Done("Lokasi $place tidak ditemukan.")
            val name = loc.optString("name", place)
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val f = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2",
            )
            val daily = f.getJSONObject("daily")
            if (tomorrow) {
                val code = daily.getJSONArray("weather_code").getInt(1)
                val max = daily.getJSONArray("temperature_2m_max").getDouble(1).roundToInt()
                val min = daily.getJSONArray("temperature_2m_min").getDouble(1).roundToInt()
                val rain = daily.getJSONArray("precipitation_probability_max").optInt(1, -1)
                val rainText = if (rain >= 0) ", peluang hujan $rain persen" else ""
                ToolOutcome.Done("Besok di $name ${describe(code)}, suhu $min sampai $max derajat$rainText.")
            } else {
                val cur = f.getJSONObject("current")
                val t = cur.getDouble("temperature_2m").roundToInt()
                val code = cur.getInt("weather_code")
                val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, -1)
                val rainText = if (rain >= 0) " Peluang hujan hari ini $rain persen." else ""
                ToolOutcome.Done("Sekarang di $name ${describe(code)}, $t derajat.$rainText")
            }
        } catch (e: Exception) {
            ToolOutcome.Done("Gagal mengambil cuaca: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /** Kode cuaca WMO → bahasa Indonesia. */
    private fun describe(code: Int): String = when (code) {
        0 -> "cerah"
        1 -> "cerah berawan"
        2 -> "berawan sebagian"
        3 -> "mendung"
        45, 48 -> "berkabut"
        51, 53, 55, 56, 57 -> "gerimis"
        61, 66, 80 -> "hujan ringan"
        63, 81 -> "hujan sedang"
        65, 67, 82 -> "hujan lebat"
        71, 73, 75, 77, 85, 86 -> "bersalju"
        95 -> "hujan badai petir"
        96, 99 -> "badai petir disertai hujan es"
        else -> "kode cuaca $code"
    }
}
