package dev.retrobit.assistant.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.retrobit.assistant.AssistantViewModel
import dev.retrobit.assistant.BuildConfig
import dev.retrobit.assistant.ChatMessage
import dev.retrobit.assistant.MainActivity
import dev.retrobit.assistant.Phase
import dev.retrobit.assistant.Role

@Composable
fun AssistantApp(vm: AssistantViewModel = viewModel()) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onPermissionsResult(res[Manifest.permission.RECORD_AUDIO] == true)
    }
    LaunchedEffect(Unit) { launcher.launch(MainActivity.PERMISSIONS) }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(vm) { showSettings = false }
        } else {
            MainScreen(vm) { showSettings = true }
        }
    }
}

@Composable
private fun MainScreen(vm: AssistantViewModel, openSettings: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rbit Asisten", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            StatusChip(if (vm.settings.alwaysOffline) "selalu offline" else if (vm.online) "online" else "offline", vm.online && !vm.settings.alwaysOffline)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = openSettings) { Text("Pengaturan") }
        }
        vm.catalogError?.let { Banner("Katalog perintah gagal dimuat: $it") }
        if (vm.apiKeyMissing) {
            Banner("Isi API key Gemini (gratis dari aistudio.google.com) di Pengaturan agar pertanyaan bisa dijawab. Perintah tetap jalan tanpa API key.")
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.messages, key = { it.id }) { Bubble(it) }
        }

        if (vm.partial.isNotBlank()) {
            Text("“${vm.partial}”", Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
        }
        vm.pendingPrompt?.let { prompt ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(prompt)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.confirmPending(true) }) { Text("Ya") }
                        OutlinedButton(onClick = { vm.confirmPending(false) }) { Text("Batal") }
                    }
                }
            }
        }

        var typed by rememberSaveable { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("atau ketik di sini…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.submit(typed); typed = "" }),
            )
            Spacer(Modifier.size(6.dp))
            TextButton(onClick = { vm.submit(typed); typed = "" }, enabled = typed.isNotBlank()) { Text("Kirim") }
        }

        Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
            val (label, action) = when (vm.phase) {
                Phase.LISTENING -> "Mendengarkan…\nketuk untuk berhenti" to vm::stopListening
                Phase.THINKING -> "Memproses…" to vm::startListening
                Phase.SPEAKING -> "Berbicara…\nketuk untuk memotong" to vm::startListening
                Phase.IDLE -> "Tekan\nuntuk bicara" to vm::startListening
            }
            Button(
                onClick = action,
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = if (vm.phase == Phase.LISTENING) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(label, fontSize = 14.sp, lineHeight = 16.sp) }
        }
    }
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val bg = if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp)
}

@Composable
private fun Banner(text: String) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) { Text(text, Modifier.padding(10.dp), fontSize = 13.sp) }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(10.dp),
        ) {
            if (!mine && m.origin.isNotEmpty()) {
                Text(m.origin, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.text)
        }
    }
}

@Composable
private fun SettingsScreen(vm: AssistantViewModel, close: () -> Unit) {
    val s = vm.settings
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(s.model) }
    var grounding by remember { mutableStateOf(s.grounding) }
    var offline by remember { mutableStateOf(s.alwaysOffline) }
    var city by remember { mutableStateOf(s.city) }
    var auto by remember { mutableStateOf(s.autoListen) }
    var speak by remember { mutableStateOf(s.speakReplies) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pengaturan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close) { Text("Tutup") }
        }
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("API key Gemini") }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showKey, onCheckedChange = { showKey = it })
            Spacer(Modifier.size(8.dp))
            Text("Tampilkan API key", fontSize = 13.sp)
        }
        Text(
            "Ambil gratis di aistudio.google.com → Get API key. Disimpan terenkripsi di HP ini saja.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Model Gemini") }, singleLine = true,
            supportingText = { Text("Ganti bila Google mengganti nama model (galat 404).") },
        )
        OutlinedTextField(
            value = city, onValueChange = { city = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Kota default untuk cuaca") }, singleLine = true,
        )
        ToggleRow("Selalu offline (tidak memakai Gemini)", offline) { offline = it }
        ToggleRow("Google Search grounding (kuota gratis kecil)", grounding) { grounding = it }
        ToggleRow("Langsung mendengarkan saat aplikasi dibuka", auto) { auto = it }
        ToggleRow("Ucapkan jawaban (TTS)", speak) { speak = it }
        Button(onClick = {
            vm.saveSettings(apiKey, model, grounding, offline, city, auto, speak)
            close()
        }, modifier = Modifier.fillMaxWidth()) { Text("Simpan") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Diagnostik (Fase 0)", fontWeight = FontWeight.Bold)
        Text(
            listOf(
                "Versi ${BuildConfig.VERSION_NAME}",
                "Jaringan: " + if (vm.online) "online (tervalidasi)" else "offline",
                "ASR: ${vm.asrInfo}",
                "ASR terakhir: " + if (vm.lastAsrOnDevice) "on-device" else "layanan default",
                vm.ttsInfo,
                "Katalog: " + (vm.router?.catalog?.size?.let { "$it intent" } ?: "GAGAL"),
                "Aplikasi terdeteksi: ${vm.apps.count}",
                "Kontak terbaca: " + if (vm.contacts.hasPermission()) "${vm.contacts.count}" else "izin belum diberikan",
            ).joinToString("\n"),
            fontSize = 13.sp,
        )
        OutlinedButton(onClick = { vm.testTts() }) { Text("Tes suara") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
