package dev.retrobit.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.retrobit.assistant.AssistantViewModel
import dev.retrobit.assistant.BuildConfig
import dev.retrobit.assistant.ChatMessage
import dev.retrobit.assistant.MainActivity
import dev.retrobit.assistant.Phase
import dev.retrobit.assistant.R
import dev.retrobit.assistant.Role

private val EXAMPLES = listOf(
    "Buka YouTube",
    "Timer lima menit",
    "Cuaca besok",
    "Nyalakan senter",
    "Siapa presiden pertama Indonesia?",
)

@Composable
fun AssistantApp(vm: AssistantViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onPermissionsResult(res[Manifest.permission.RECORD_AUDIO] == true)
    }
    LaunchedEffect(vm.onboarded) {
        if (vm.onboarded) launcher.launch(MainActivity.PERMISSIONS)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            !vm.onboarded -> OnboardingScreen { key -> vm.finishOnboarding(key) }
            showSettings -> {
                BackHandler { showSettings = false }
                SettingsScreen(vm) { showSettings = false }
            }
            else -> MainScreen(vm, openSettings = { showSettings = true }, requestMic = { launcher.launch(MainActivity.PERMISSIONS) })
        }
    }
}

@Composable
private fun MainScreen(vm: AssistantViewModel, openSettings: () -> Unit, requestMic: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- kepala
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rbit Asisten", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            val good = vm.online && !vm.settings.alwaysOffline
            Box(
                Modifier.size(8.dp).background(
                    if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape,
                ),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                if (vm.settings.alwaysOffline) "selalu offline" else if (vm.online) "online" else "offline",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = openSettings) {
                Icon(painterResource(R.drawable.px_settings_cog_2), contentDescription = "Pengaturan")
            }
        }
        vm.catalogError?.let { Banner("Katalog perintah gagal dimuat: $it") }
        if (vm.apiKeyMissing) {
            Banner("Pertanyaan belum bisa dijawab: API key Gemini belum diisi. Ketuk untuk mengisi.", onClick = openSettings)
        }

        // ---- isi
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty()) {
                EmptyState { vm.submit(it) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(vm.messages, key = { it.id }) { m ->
                        Bubble(m, onLongPress = {
                            clipboard.setText(AnnotatedString(m.text))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.showNotice("Disalin")
                        })
                    }
                }
            }
        }

        // ---- transkrip & pemberitahuan
        AnimatedVisibility(vm.partial.isNotBlank()) {
            Text(
                vm.partial,
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(vm.notice != null) {
            Text(
                vm.notice.orEmpty(),
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        vm.pendingPrompt?.let { prompt ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(prompt, fontWeight = FontWeight.Medium)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.confirmPending(true) }) {
                            Icon(painterResource(R.drawable.px_check), contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Ya")
                        }
                        OutlinedButton(onClick = { vm.confirmPending(false) }) {
                            Icon(painterResource(R.drawable.px_close), contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Batal")
                        }
                    }
                }
            }
        }

        // ---- kolom ketik
        var typed by rememberSaveable { mutableStateOf("") }
        val send = {
            if (typed.isNotBlank()) {
                vm.submit(typed)
                typed = ""
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = { Text("Ketik perintah atau pertanyaan…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = send, enabled = typed.isNotBlank()) {
                    Icon(painterResource(R.drawable.px_send), contentDescription = "Kirim")
                }
            },
        )

        // ---- tombol bicara
        MicButton(
            phase = vm.phase,
            level = vm.level,
            followUp = vm.followUpListening,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when (vm.phase) {
                    Phase.IDLE -> {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startListening() else requestMic()
                    }
                    Phase.LISTENING -> vm.stopListening()
                    Phase.THINKING, Phase.SPEAKING -> vm.stopAll()
                }
            },
        )
    }
}

@Composable
private fun EmptyState(onExample: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(R.drawable.px_robot_face_happy),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text("Halo! Ada yang bisa dibantu?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ketuk mikrofon lalu bicara, atau coba salah satu:",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (e in EXAMPLES) {
            SuggestionChip(onClick = { onExample(e) }, label = { Text(e) })
        }
    }
}

@Composable
private fun Banner(text: String, onClick: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) { Text(text, Modifier.padding(10.dp), fontSize = 16.sp) }
}

@Composable
private fun Bubble(m: ChatMessage, onLongPress: () -> Unit) {
    val mine = m.role == Role.USER
    val shape = if (mine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape,
                )
                .pointerInput(m.text) { detectTapGestures(onLongPress = { onLongPress() }) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.text.isEmpty()) {
                TypingDots()
            } else {
                Text(m.text)
            }
            if (m.origin == "offline") {
                Text("offline", fontSize = 18.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "dotsValue",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 3) {
            Box(
                Modifier.size(8.dp)
                    .alpha(if (t.toInt() == i) 1f else 0.3f)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun SettingsScreen(vm: AssistantViewModel, close: () -> Unit) {
    val s = vm.settings
    val uri = LocalUriHandler.current
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(s.model) }
    var grounding by remember { mutableStateOf(s.grounding) }
    var offline by remember { mutableStateOf(s.alwaysOffline) }
    var city by remember { mutableStateOf(s.city) }
    var auto by remember { mutableStateOf(s.autoListen) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var conversation by remember { mutableStateOf(s.conversationMode) }
    var confirmClear by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    if (showLicenses) {
        val text = remember {
            runCatching {
                ctx.assets.list("licenses").orEmpty().sorted().joinToString("\n\n————\n\n") { f ->
                    ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() }
                }
            }.getOrElse { "Gagal membaca lisensi: ${it.message}" }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Lisensi pihak ketiga") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Tutup") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus riwayat chat?") },
            text = { Text("Semua percakapan di HP ini akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                    close()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pengaturan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = close) { Icon(painterResource(R.drawable.px_close), contentDescription = "Tutup") }
        }

        SectionTitle("Gemini")
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("API key Gemini") }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Sembunyikan" else "Lihat") } },
        )
        OutlinedButton(onClick = { uri.openUri(API_KEY_URL) }) { Text("Dapatkan API key gratis") }
        OutlinedTextField(
            value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Model Gemini") }, singleLine = true,
            supportingText = { Text("Ganti bila Google mengganti nama model (galat 404).") },
        )
        ToggleRow("Google Search grounding", "Jawaban memakai hasil pencarian terbaru. Kuota gratisnya kecil.", grounding) { grounding = it }
        ToggleRow("Selalu offline", "Tidak pernah memakai Gemini.", offline) { offline = it }

        SectionTitle("Percakapan")
        ToggleRow("Mode percakapan", "Setelah menjawab pertanyaan lisan, langsung mendengarkan lagi.", conversation) { conversation = it }
        ToggleRow("Mendengarkan saat aplikasi dibuka", null, auto) { auto = it }
        ToggleRow("Ucapkan jawaban", null, speak) { speak = it }
        OutlinedTextField(
            value = city, onValueChange = { city = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Kota default untuk cuaca") }, singleLine = true,
        )

        Button(onClick = {
            vm.saveSettings(apiKey, model, grounding, offline, city, auto, speak, conversation)
            close()
        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Simpan") }

        SectionTitle("Akses cepat")
        Text(
            "• Tile: tarik panel notifikasi › ikon pensil › seret \"Rbit: bicara\".\n" +
                "• Widget: tekan lama layar utama › Widget › Rbit Asisten.\n" +
                "• Pintasan: tekan lama ikon aplikasi › Bicara.",
            fontSize = 16.sp,
        )

        SectionTitle("Data")
        OutlinedButton(onClick = { confirmClear = true }) {
            Icon(painterResource(R.drawable.px_trash), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Hapus riwayat chat")
        }

        SectionTitle("Lisensi")
        Text(
            "• Font PixelOperator: Jayvee Enaguas, CC0 1.0 (domain publik).\n" +
                "• Ikon pixelarticons: Gerrit Halfmann, lisensi MIT.",
            fontSize = 16.sp,
        )
        OutlinedButton(onClick = { showLicenses = true }) { Text("Lihat teks lisensi") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Diagnostik", fontWeight = FontWeight.Bold)
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
            fontSize = 16.sp,
        )
        OutlinedButton(onClick = { vm.testTts() }) { Text("Tes suara") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
