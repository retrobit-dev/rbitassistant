package dev.retrobit.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.retrobit.assistant.router.Normalizer

class ContactDirectory(private val context: Context, private val norm: Normalizer) {
    data class Contact(val name: String, val number: String)

    private var cache: List<Pair<String, Contact>> = emptyList()
    private var loadedAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun contacts(): List<Pair<String, Contact>> {
        if (!hasPermission()) return emptyList()
        val now = System.currentTimeMillis()
        if (cache.isEmpty() || now - loadedAt > 60_000) {
            val out = LinkedHashMap<String, Contact>()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        out.putIfAbsent(name, Contact(name, number))
                    }
                }
            } catch (e: SecurityException) {
                return emptyList()
            }
            cache = out.values.map { norm.normalize(it.name) to it }
            loadedAt = now
        }
        return cache
    }

    val count: Int get() = contacts().size

    fun resolve(raw: String): Contact? {
        val all = contacts()
        all.firstOrNull { it.first == raw }?.let { return it.second }
        val byWord = all.filter { it.first.split(' ').firstOrNull() == raw || it.first.startsWith("$raw ") }
        if (byWord.size == 1) return byWord[0].second
        val best = all.maxByOrNull { similarity(it.first, raw) } ?: return null
        return if (similarity(best.first, raw) >= 0.85) best.second else null
    }

    fun byName(name: String): Contact? = contacts().firstOrNull { it.second.name == name }?.second
}
