package dev.retrobit.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrobit.assistant.R

const val API_KEY_URL = "https://aistudio.google.com/apikey"

/** Layar sambutan: hanya muncul sekali. API key boleh dilewati. */
@Composable
fun OnboardingScreen(onDone: (apiKey: String) -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var key by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text("Selamat datang di Rbit Asisten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Feature("Perintah", "Buka aplikasi, timer, alarm, senter, volume, dan lainnya. Langsung jalan, juga tanpa internet.")
        Feature("Tanya apa saja", "Dijawab Gemini dari Google: gratis, tetapi butuh API key.")
        Feature("Privasi", "API key disimpan terenkripsi di HP ini. Riwayat chat tidak dikirim ke mana pun selain ke Gemini saat bertanya.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pasang API key (1 menit)", fontWeight = FontWeight.Bold)
                Text(
                    "1. Ketuk tombol di bawah dan masuk dengan akun Google.\n" +
                        "2. Pilih \"Create API key\", lalu salin.\n" +
                        "3. Kembali ke sini dan ketuk Tempel.",
                    fontSize = 14.sp,
                )
                OutlinedButton(onClick = { uri.openUri(API_KEY_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Buka Google AI Studio")
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key Gemini") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { clipboard.getText()?.text?.let { key = it.trim() } }) { Text("Tempel") }
                    },
                )
            }
        }

        Text(
            "Setelah ini Android akan meminta izin Mikrofon (untuk mendengar) dan Kontak (untuk menelepon/SMS dengan nama).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Tombol selalu terlihat di bawah, berapa pun tinggi layarnya.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onDone("") }) { Text("Lewati") }
        Button(onClick = { onDone(key) }, modifier = Modifier.weight(1f).height(52.dp)) {
            Text(if (key.isBlank()) "Mulai tanpa API key" else "Mulai")
        }
    }
    }
}

@Composable
private fun Feature(title: String, body: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
