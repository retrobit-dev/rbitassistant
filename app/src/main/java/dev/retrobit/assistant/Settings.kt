package dev.retrobit.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Pengaturan pengguna. API key disimpan terenkripsi (Android Keystore). */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rbit", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "rbit_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        prefs // Keystore bermasalah: jangan sampai aplikasi tidak bisa dibuka.
    }

    var apiKey: String
        get() = secure.getString("api_key", "").orEmpty()
        set(v) = secure.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** Google Search grounding: kuota gratis terpisah & kecil, jadi default MATI. */
    var grounding: Boolean
        get() = prefs.getBoolean("grounding", false)
        set(v) = prefs.edit().putBoolean("grounding", v).apply()

    var alwaysOffline: Boolean
        get() = prefs.getBoolean("always_offline", false)
        set(v) = prefs.edit().putBoolean("always_offline", v).apply()

    var city: String
        get() = prefs.getString("city", "Jepara").orEmpty().ifBlank { "Jepara" }
        set(v) = prefs.edit().putString("city", v.trim()).apply()

    var autoListen: Boolean
        get() = prefs.getBoolean("auto_listen", true)
        set(v) = prefs.edit().putBoolean("auto_listen", v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak", true)
        set(v) = prefs.edit().putBoolean("speak", v).apply()

    /** Sudah melewati layar sambutan. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    /** Setelah menjawab pertanyaan lisan, langsung mendengarkan lagi (tanpa menekan tombol). */
    var conversationMode: Boolean
        get() = prefs.getBoolean("conversation", true)
        set(v) = prefs.edit().putBoolean("conversation", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}
