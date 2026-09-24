package dev.retrobit.assistant.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import dev.retrobit.assistant.device.AppDirectory
import dev.retrobit.assistant.device.ContactDirectory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class ToolOutcome {
    /** @param display teks di layar; @param spoken yang diucapkan TTS. */
    data class Done(val spoken: String, val display: String = spoken) : ToolOutcome()

    /** Aksi berisiko (telepon/SMS/WA): tanyakan dulu, jalankan [run] bila pengguna setuju. */
    class Confirm(val prompt: String, val run: () -> Done) : ToolOutcome()
}

/**
 * Menjalankan intent perintah. Semua aksi lewat API/Intent publik Android — tanpa root,
 * tanpa Shizuku (lihat docs/architecture.md §4.8).
 */
class ToolExecutor(
    private val context: Context,
    private val apps: AppDirectory,
    private val contacts: ContactDirectory,
    private val weather: WeatherClient,
    private val defaultCity: () -> String,
) {
    private val id = Locale.forLanguageTag("id-ID")
    private val audio = context.getSystemService(AudioManager::class.java)

    suspend fun execute(intentId: String, slots: Map<String, Any>, normalized: String): ToolOutcome = when (intentId) {
        "open_app" -> openApp(slots["app"] as String)
        "call_contact" -> callContact(slots["contact"] as String)
        "send_sms" -> sendSms(slots["contact"] as String, slots["message"] as String)
        "send_whatsapp" -> sendWhatsApp(slots["contact"] as String, slots["message"] as String)
        "set_timer" -> setTimer(slots["amount"] as Int, slots["unit"] as String)
        "set_alarm" -> (slots["time"] as String?)?.let { setAlarm(it, slots["period"] as String?) } ?: alarmWithoutTime()
        "what_time" -> tellTime()
        "what_date" -> tellDate()
        "play_music" -> playMusic((slots["query"] ?: slots["title"]) as String?)
        "pause_music" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Musik dijeda.")
        "next_track" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Lagu berikutnya.")
        "set_volume" -> setVolume(slots["direction"] as String?, slots["level"] as Int?)
        "set_brightness" -> setBrightness(slots["direction"] as String?, slots["level"] as Int?)
        "toggle_flashlight" -> torch(slots["state"] as String? ?: "on")
        "open_settings" -> openSettings(slots["page"] as String?)
        "screenshot" -> ToolOutcome.Done(
            "Tangkapan layar belum bisa di versi ini. Tekan tombol daya dan volume turun bersamaan.",
        )
        "weather" -> weather.report((slots["place"] as String?) ?: defaultCity(), normalized.contains("besok"))
        "search_web" -> searchWeb(slots["query"] as String)
        "navigate_to" -> navigate(slots["place"] as String)
        "search_play_store" -> playStore(slots["query"] as String)
        else -> ToolOutcome.Done("Perintah $intentId belum didukung di versi ini.")
    }

    // ---- aplikasi & komunikasi ------------------------------------------------------

    private fun openApp(label: String): ToolOutcome.Done {
        val app = apps.byLabel(label) ?: apps.resolve(label)
            ?: return ToolOutcome.Done("Aplikasi $label tidak ditemukan.")
        val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return ToolOutcome.Done("${app.label} tidak bisa dibuka.")
        start(launch)
        return ToolOutcome.Done("Membuka ${app.label}.")
    }

    private fun contactOrNull(name: String): ContactDirectory.Contact? = contacts.byName(name) ?: contacts.resolve(name)

    private fun noContact(name: String): ToolOutcome.Done =
        if (!contacts.hasPermission()) {
            ToolOutcome.Done("Izin kontak belum diberikan. Buka Pengaturan aplikasi lalu izinkan Kontak.")
        } else {
            ToolOutcome.Done("Kontak $name tidak ditemukan.")
        }

    private fun callContact(name: String): ToolOutcome {
        val c = contactOrNull(name) ?: return noContact(name)
        return ToolOutcome.Confirm("Telepon ${c.name}, ${c.number}?") {
            start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number))))
            ToolOutcome.Done("Membuka panggilan ke ${c.name}. Tekan tombol hijau untuk menelepon.")
        }
    }

    private fun sendSms(name: String, message: String): ToolOutcome {
        val c = contactOrNull(name) ?: return noContact(name)
        return ToolOutcome.Confirm("Kirim SMS ke ${c.name}: \"$message\"?") {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
                .putExtra("sms_body", message)
            start(i)
            ToolOutcome.Done("SMS untuk ${c.name} sudah disiapkan. Tekan kirim.")
        }
    }

    private fun sendWhatsApp(name: String, message: String): ToolOutcome {
        val c = contactOrNull(name) ?: return noContact(name)
        val phone = waNumber(c.number)
        return ToolOutcome.Confirm("Kirim WhatsApp ke ${c.name}: \"$message\"?") {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=" + Uri.encode(message))
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
            if (!start(i, quiet = true)) start(Intent(Intent.ACTION_VIEW, uri))
            ToolOutcome.Done("Pesan WhatsApp untuk ${c.name} sudah disiapkan. Tekan kirim.")
        }
    }

    /** 0812… → 62812…, +62… → 62… */
    private fun waNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("0")) "62" + digits.drop(1) else digits
    }

    // ---- waktu ------------------------------------------------------------------------

    private fun setTimer(amount: Int, unit: String): ToolOutcome.Done {
        val seconds = when (unit) {
            "hour" -> amount * 3600
            "minute" -> amount * 60
            else -> amount
        }
        if (seconds !in 1..86_400) return ToolOutcome.Done("Timer harus antara 1 detik dan 24 jam.")
        val unitWord = mapOf("hour" to "jam", "minute" to "menit", "second" to "detik")[unit]
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Rbit")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(i)) ToolOutcome.Done("Timer $amount $unitWord dimulai.") else noClock()
    }

    private fun setAlarm(time: String, period: String?): ToolOutcome.Done {
        val (h0, m) = time.split(":").map { it.toInt() }
        val h = when (period) {
            "pagi" -> if (h0 == 12) 0 else h0
            "siang" -> if (h0 in 1..5) h0 + 12 else h0
            "sore" -> if (h0 in 1..11) h0 + 12 else h0
            "malam" -> when (h0) {
                in 6..11 -> h0 + 12
                12 -> 0
                else -> h0
            }
            else -> h0
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Rbit")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val shown = String.format(Locale.ROOT, "%02d.%02d", h, m)
        return if (start(i)) ToolOutcome.Done("Alarm jam ${spokenClock(h, m)} dipasang.", "Alarm $shown dipasang.") else noClock()
    }

    /** "pasang alarm" tanpa jam: buka layar alarm aplikasi jam supaya jamnya dipilih sendiri. */
    private fun alarmWithoutTime(): ToolOutcome.Done {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_MESSAGE, "Rbit")
        return if (start(i)) ToolOutcome.Done("Silakan pilih jam alarmnya.") else noClock()
    }

    private fun noClock() = ToolOutcome.Done("Aplikasi jam tidak menerima perintah ini.")

    private fun spokenClock(h: Int, m: Int) = if (m == 0) "$h tepat" else "$h lewat $m menit"

    private fun tellTime(): ToolOutcome.Done {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return ToolOutcome.Done("Sekarang jam ${spokenClock(h, m)}.", String.format(Locale.ROOT, "Sekarang %02d.%02d", h, m))
    }

    private fun tellDate(): ToolOutcome.Done {
        val s = SimpleDateFormat("EEEE, d MMMM yyyy", id).format(Date())
        return ToolOutcome.Done("Hari ini $s.")
    }

    // ---- media & perangkat ----------------------------------------------------------

    private fun playMusic(query: String?): ToolOutcome.Done {
        if (query.isNullOrBlank()) return mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Memutar musik.")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (start(i, quiet = true)) return ToolOutcome.Done("Memutar $query.")
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, query)
        if (start(yt, quiet = true)) return ToolOutcome.Done("Mencari $query di YouTube.")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        return ToolOutcome.Done("Mencari $query.")
    }

    private fun mediaKey(code: Int, reply: String): ToolOutcome.Done {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolOutcome.Done(reply)
    }

    private fun setVolume(direction: String?, level: Int?): ToolOutcome.Done {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        if (level != null) {
            val pct = level.coerceIn(0, 100)
            audio.setStreamVolume(stream, (max * pct + 50) / 100, AudioManager.FLAG_SHOW_UI)
            return ToolOutcome.Done("Volume $pct persen.")
        }
        val dir = if (direction == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        repeat(2) { audio.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI) }
        val pct = audio.getStreamVolume(stream) * 100 / max.coerceAtLeast(1)
        return ToolOutcome.Done(if (direction == "down") "Volume diturunkan." else "Volume dinaikkan.", "Volume $pct%")
    }

    private fun setBrightness(direction: String?, level: Int?): ToolOutcome.Done {
        if (!Settings.System.canWrite(context)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
            return ToolOutcome.Done("Izinkan dulu \"Ubah setelan sistem\" untuk Rbit Asisten, lalu ulangi perintahnya.")
        }
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        // Skala 0–255 adalah asumsi; sebagian ponsel memakai skala lain (dicatat di Fase 0).
        val target = when {
            level != null -> level.coerceIn(0, 100) * 255 / 100
            direction == "down" -> cur - 64
            else -> cur + 64
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val pct = target * 100 / 255
        return ToolOutcome.Done("Kecerahan $pct persen.")
    }

    private fun torch(state: String): ToolOutcome.Done {
        val cm = context.getSystemService(CameraManager::class.java)
        return try {
            val camId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolOutcome.Done("Ponsel ini tidak punya lampu senter yang bisa dikendalikan.")
            cm.setTorchMode(camId, state == "on")
            ToolOutcome.Done(if (state == "on") "Senter menyala." else "Senter dimatikan.")
        } catch (e: Exception) {
            ToolOutcome.Done("Senter tidak bisa dikendalikan: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun openSettings(page: String?): ToolOutcome.Done {
        val (action, name) = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "baterai"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "suara"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "layar"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "lokasi"
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS to "data seluler"
            else -> Settings.ACTION_SETTINGS to null
        }
        if (!start(Intent(action), quiet = true)) start(Intent(Settings.ACTION_SETTINGS))
        return ToolOutcome.Done(if (name != null) "Membuka pengaturan $name." else "Membuka pengaturan.")
    }

    // ---- web & peta -----------------------------------------------------------------

    private fun searchWeb(query: String): ToolOutcome.Done {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!start(i, quiet = true)) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
        return ToolOutcome.Done("Mencari $query.")
    }

    private fun navigate(place: String): ToolOutcome.Done {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .setPackage("com.google.android.apps.maps")
        if (!start(i, quiet = true)) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        }
        return ToolOutcome.Done("Menuju $place.")
    }

    private fun playStore(query: String): ToolOutcome.Done {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query)))
        if (!start(i, quiet = true)) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(query))))
        }
        return ToolOutcome.Done("Mencari $query di Play Store.")
    }

    // ---- util -----------------------------------------------------------------------

    /** Aplikasi sedang di depan, jadi boleh membuka activity lain dari context aplikasi. */
    private fun start(intent: Intent, quiet: Boolean = false): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) android.util.Log.w("rbit", "tidak ada aplikasi untuk $intent")
        false
    } catch (e: SecurityException) {
        android.util.Log.w("rbit", "ditolak: $intent", e)
        false
    }
}
