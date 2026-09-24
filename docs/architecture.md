# rbitassistant — Rencana Arsitektur

> **Status:** Draf v0.1 — rencana, belum ada kode di repo.
> **Produk:** Asisten suara untuk Android yang (a) menjalankan perintah ("buka WhatsApp", "setel timer 5 menit") dan (b) menjawab pertanyaan sebagai chatbot.
> **Perangkat target:** Infinix GT 30 Pro.

---

## 1. Ringkasan keputusan

| Aspek | Keputusan | Alasan singkat |
|---|---|---|
| Bentuk aplikasi | **Aplikasi Android biasa (bukan pengganti Google Assistant)** | Menjadi asisten sistem penuh butuh `VoiceInteractionService` + permission `BIND_VOICE_INTERACTION` yang **signature-level**, hanya bisa diberikan OEM pada APK system. App pihak ketiga tidak bisa dapat ini. Lihat §4.1. |
| Bahasa utama | **Kotlin + Jetpack Compose**, satu modul `app` + modul `core` | Native memberi akses penuh ke `AudioRecord`, foreground service, Accessibility, dan kontrol baterai. |
| Bahasa interaksi | **Bahasa Indonesia (`id-ID`)** | Target pengguna. |
| Wake word | **Fase 1: push-to-talk / tombol & overlay.** Wake word baru di Fase 4, memakai kata dalam **bahasa Inggris** (mis. *"Hey Ruby"*) | Porcupine tidak mendukung bahasa Indonesia untuk custom wake word (hanya EN, ES, FR, DE, IT, JA, KO, PT, ZH). Alternatif wake word ID dibahas di §4.2. |
| ASR | **Android `SpeechRecognizer`** sebagai jalur utama, `sherpa-onnx` (Whisper) sebagai jalur offline | Gratis, latensi rendah, kualitas ID terbaik di perangkat. Jalur offline untuk mode tanpa internet. |
| Otak (chatbot + NLU) | **Gemini 3.8 Flash** via Gemini API (streaming + function calling + search grounding) | Model Flash **stabil** terkini (per Sept 2026). Function calling = mekanisme "perintah". |
| TTS | **Android `TextToSpeech`** dengan `Locale("id","ID")` | Sudah terpasang di XOS, nol tambahan ukuran APK. Alternatif cloud: Gemini 3.8 Flash-Lite TTS. Voice neural offline (Piper/Kokoro) = opsi Fase 3. |
| Eksekusi perintah | **Intent eksplisit/implisit + MediaSession + Accessibility Service (opt-in)** | Ini satu-satunya jalur legal untuk "mengendalikan HP" dari app pihak ketiga. |
| Online vs offline | **Online dulu, offline otomatis bila internet tidak tersedia**, diputuskan per permintaan | Kuota habis (429) diperlakukan sama dengan tanpa internet, jadi asisten tidak pernah mati. Perintah selalu lokal. Lihat §4.10 dan [ADR 0002](decisions/0002-online-first-offline-fallback.md). |

---

## 2. Konteks perangkat

Spesifikasi Infinix GT 30 Pro (sumber: GSMArena / nanoreview):

| Komponen | Nilai | Implikasi arsitektur |
|---|---|---|
| SoC | MediaTek Dimensity 8350 Ultimate (4 nm) | Cukup untuk ASR on-device dan LLM kecil on-device. |
| CPU | 1× A715 @3,35 GHz + 3× A715 @3,20 GHz + 4× A510 @2,20 GHz | Ada core cepat untuk burst inference; pakai `Performance` QoS saat inferensi, lalu turun. |
| NPU | MediaTek APU 780 | **Praktis tidak bisa dipakai langsung** oleh runtime pihak ketiga. MediaPipe LLM Inference hanya mengekspor backend CPU & GPU, tanpa NPU. Jadi jangan merencanakan akselerasi NPU. |
| GPU | Mali-G615 MC6 | Backend GPU untuk LLM on-device (MediaPipe/LiteRT). |
| RAM | 8 GB / 12 GB LPDDR5X | Cukup untuk Gemma 3n E2B int4 (±3,1 GB file) sebagai fallback offline. |
| Penyimpanan | 256/512 GB UFS 4.0, **tanpa slot microSD** | Model harus di-download ke penyimpanan app; sediakan manajemen ukuran model. |
| OS | Android 15 / XOS 15 | Batasan mic latar, permission runtime, dan pembatasan baterai agresif khas XOS. |
| Baterai | 5.500 mAh, 45 W | Wake word always-on tetap harus dikontrol ketat (§8). |

**Dua risiko spesifik perangkat ini:**

1. **Ketersediaan recognizer on-device tidak terjamin.** Sudah ada laporan bahwa pada sebagian build Android 15 OEM, `SpeechRecognizer.isOnDeviceRecognitionAvailable()` mengembalikan `false` padahal voice typing sistem berfungsi normal. → **Wajib ada fallback berjenjang** (§4.3). Belum diverifikasi pada XOS 15; ini **tugas validasi pertama** di Fase 0.
2. **Pembunuhan proses oleh XOS.** Foreground service bisa dimatikan oleh manajemen baterai. → Butuh foreground service bertipe `microphone`, notifikasi persisten, dan panduan pengguna untuk whitelist baterai.

---

## 3. Pipeline

```
                         ┌────────────────────────── ON-DEVICE ──────────────────────────┐
  [Mic 16 kHz PCM] ──►  │ (A) Wake/Trigger ──► (B) VAD ──► (C) ASR ──► teks             │
                         └───────────────────────────────────┬───────────────────────────┘
                                                             ▼
                                                    ┌─────────────────┐
                                                    │  (D) ROUTER     │  ← keputusan cepat, on-device
                                                    └────┬───────┬────┘
                                        "ini perintah"   │       │  "ini pertanyaan / ngobrol"
                                                         ▼       ▼
                                          ┌──────────────────┐  ┌──────────────────────────┐
                                          │ (E) Intent +     │  │ (G) LLM Chat             │
                                          │     slot parser  │  │  streaming + tool call   │
                                          └────────┬─────────┘  └────────────┬─────────────┘
                                                   ▼                         │
                                          ┌──────────────────┐               │
                                          │ (F) Tool Executor│               │
                                          │  Intent/Accessib.│               │
                                          └────────┬─────────┘               │
                                                   │                         ▼
                                                   └──────► (H) Reply ──► (I) TTS + UI
                                                            Composer
```

Prinsip desain:

- **Audio hanya keluar dari perangkat bila diperlukan.** Default: ASR sistem (bisa on-device), NLU lokal untuk perintah. Yang dikirim ke cloud hanya teks, bukan audio.
- **Router dulu, LLM kemudian.** Perintah berfrekuensi tinggi ("buka X", "naikkan volume") tidak boleh menunggu round-trip cloud. Target: perintah dieksekusi < 1 detik dari ujung ucapan.
- **Semua modul di balik interface.** ASR/LLM/TTS bisa ditukar tanpa menyentuh logika bisnis (§6).
- **Online dulu, offline bila perlu.** (C) ASR dan (G) LLM Chat masing-masing punya versi online dan offline; pemilihnya adalah lapisan failover yang memeriksa jaringan dan hasil permintaan (§4.10). (D)–(F) dan (I) TTS selalu lokal.

---

## 4. Per komponen

### 4.1 Batas kemampuan: apa yang bisa dan tidak bisa dilakukan app

| Kemampuan | Bisa? | Mekanisme |
|---|---|---|
| Buka aplikasi tertentu | ✅ | `packageManager.getLaunchIntentForPackage(pkg)` |
| Telepon / kirim SMS-WA | ✅ | `Intent.ACTION_DIAL`, `ACTION_SENDTO`; WA via `https://wa.me/` deep link |
| Setel timer / alarm | ✅ | `AlarmClock.ACTION_SET_TIMER` / `ACTION_SET_ALARM` |
| Navigasi ke lokasi | ✅ | `geo:` URI → Google Maps |
| Kontrol musik (play/pause/next) | ✅ | `MediaSessionManager` / `MediaController` + `MODIFY_AUDIO_SETTINGS` |
| Volume, kecerahan, senter | ✅ | `AudioManager`, `Settings.System.SCREEN_BRIGHTNESS`, `CameraManager.setTorchMode` |
| Baca notifikasi / balas chat | ⚠️ opt-in | `NotificationListenerService` (user harus aktifkan manual di Settings) |
| Ketuk tombol di aplikasi lain, gulir, baca isi layar | ⚠️ opt-in | `AccessibilityService` (user harus aktifkan manual; sensitif) |
| **Jadi asisten default (tahan tombol Home, always-on hotword sistem)** | ❌ | Butuh `VoiceInteractionService` + `BIND_VOICE_INTERACTION` = permission signature, hanya untuk APK system-signed yang di-preinstall OEM. App dari Play Store tidak akan pernah dapat. |
| Dengarkan "Hey …" saat app ditutup total | ❌ praktis | Mic di background butuh foreground service + notifikasi; XOS juga agresif mematikan. Solusi realistis: tombol overlay mengambang / pintasan / widget. |

**Konsekuensi desain:** rbitassistant diposisikan sebagai **aplikasi asisten yang dibuka/dipanggil**, bukan pengganti Google Assistant di level sistem. Semua perintah yang melewati batas app harus lewat Accessibility/Notification Listener dengan persetujuan eksplisit pengguna.

### 4.2 Wake word (Fase 4, bukan MVP)

| Opsi | Bahasa Indonesia? | Biaya | Catatan |
|---|---|---|---|
| **Porcupine (Picovoice)** | ❌ hanya EN, ES, FR, DE, IT, JA, KO, PT, ZH | Free tier ±3 user aktif/bulan; tier berikutnya $899/bln (≤1.000 user) | Akurasi & konsumsi daya terbaik. Untuk ID: pakai wake word **bahasa Inggris** ("Hey Ruby", "Hey Bit") — tetap natural diucapkan orang Indonesia. **Free tier 3 user = cocok untuk pemakaian pribadi, tidak untuk rilis publik.** |
| **sherpa-onnx KWS** | Model KWS bawaan terbatas; untuk ID perlu melatih sendiri | Gratis, open source | Perlu dataset + training. Beban kerja nyata, bukan "tinggal pasang". |
| **openWakeWord** | Perlu training sendiri | Gratis | Ditujukan ke desktop/edge; integrasi Android perlu usaha. |
| **Tanpa wake word (MVP)** | — | Gratis | Tombol mic besar di app, floating overlay, widget, atau pintasan. **Ini yang direkomendasikan untuk Fase 1.** |

> **Rekomendasi:** luncurkan tanpa wake word. Ukur dulu apakah pengguna benar-benar menginginkannya sebelum membayar/melatih.

### 4.3 ASR — tiga tingkat, dipilih otomatis saat startup

```
Tingkat 1: SpeechRecognizer.createOnDeviceSpeechRecognizer()   ← jika isOnDeviceRecognitionAvailable() == true
Tingkat 2: SpeechRecognizer.createSpeechRecognizer()           ← recognizer sistem (mungkin lewat jaringan Google)
Tingkat 3: sherpa-onnx + Whisper (int8) di dalam APK           ← 100% offline, ukuran ±100–250 MB
```

Detail penting:

- **Tingkat 1 vs 2 bukan perbedaan kosmetik.** `createSpeechRecognizer()` bisa mengirim audio ke cloud vendor — perlakukan sebagai API jaringan sampai terbukti on-device. UI harus jujur soal ini ("mode offline aktif/tidak").
- `EXTRA_PREFER_OFFLINE=true` **bukan jaminan** offline; ia hanya preferensi. Yang menjamin adalah constructor on-device.
- Konfigurasi intent: `EXTRA_LANGUAGE = "id-ID"`, `EXTRA_PARTIAL_RESULTS = true`, `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_MAX_RESULTS = 5`.
- **Ketersediaan tiap tingkat dideteksi sekali saat start**, tetapi **tingkat yang dipakai dipilih per ucapan**: Tingkat 2 saat online, Tingkat 1 → 3 saat offline (§4.10). Tampilkan tingkat yang sedang dipakai di UI. Jangan sampai pengguna mengira audionya lokal padahal tidak.
- Untuk Tingkat 3: Whisper *multilingual* (bukan `.en`) mendukung bahasa Indonesia, tetapi **kualitasnya pada ID belum saya verifikasi di perangkat ini** — masuk checklist Fase 2.

### 4.4 VAD (Voice Activity Detection)

- Sumber VAD gratis: **Silero VAD via sherpa-onnx** (sudah tersedia sebagai model VAD di sherpa-onnx).
- Fungsi: memotong awal/akhir ucapan supaya ASR tidak menunggu timeout, dan menghemat baterai.
- Parameter awal: frame 512 sampel @16 kHz, threshold 0,5, `minSilenceDuration` 600–800 ms, `minSpeechDuration` 250 ms.
- Untuk Tingkat 1 & 2, `SpeechRecognizer` sudah punya endpointing sendiri — VAD hanya dipakai pada jalur sherpa-onnx dan wake word.

### 4.5 Router — pemisah "perintah" vs "chat"

Ini komponen yang paling menentukan rasa produk. Strategi bertingkat:

```
1. Normalisasi teks   : lowercase, buang tanda baca, ubah angka kata → digit,
                        singkatan umum ("yg"→"yang", "gw"→"saya", "wa"→"whatsapp")
2. Aturan/regex       : kamus pola perintah (§4.6). Latensi ~0 ms, akurasi tinggi untuk pola dikenal.
3. Fuzzy matching     : Levenshtein/rapidfuzz terhadap daftar intent. Toleransi salah dengar ASR.
4. LLM function call  : bila langkah 1–3 tidak yakin (skor < ambang), teks dikirim ke LLM
                        dengan daftar tool. LLM memutuskan: tool call atau jawaban chat.
```

Output router selalu bertipe tegas, bukan string bebas:

```kotlin
sealed class Routed {
    data class Command(val intent: IntentSpec, val confidence: Float, val source: RouteSource) : Routed()
    data class Chat(val utterance: String, val context: List<Message>) : Routed()
    data class Ambiguous(val utterance: String, val candidates: List<IntentSpec>) : Routed() // minta konfirmasi
}
```

Ambang awal: `confidence ≥ 0,75` → eksekusi langsung; `0,45–0,75` → konfirmasi ("Maksudnya buka WhatsApp?"); `< 0,45` → chat.

### 4.6 Katalog intent (format deklaratif)

Disimpan sebagai data, bukan kode — supaya menambah perintah tidak perlu recompilasi logika. **Katalognya sudah ada** di `intents/` (23 berkas) dan diuji otomatis melawan 139 ucapan di `testdata/golden_intents.json`. Spesifikasi lengkap (normalisasi, bahasa pola, aturan pemenang, tipe slot) ada di [intents.md](intents.md).

Contoh — isi `intents/open_app.yaml` apa adanya:

```yaml
# intents/open_app.yaml
id: open_app
patterns:
  - "(buka|jalankan|luncurkan|open|mainkan game) [aplikasi] {app}"
slots:
  app:
    type: app_name        # produksi: resolver fuzzy ke PackageManager
examples:
  - "buka whatsapp"
  - "buka YouTube"
  - "jalankan galeri"
action: launch_app
confirmation: never
needs_network: false
```

Set intent MVP (23 intent, semuanya sudah ada di `intents/`). Draf awal menulis `launch_app` di tabel ini, padahal contoh YAML-nya memakai `open_app`. Yang benar: **id intent `open_app`, nama aksinya `launch_app`**.

| Grup | Intent |
|---|---|
| Aplikasi | `open_app`, `search_play_store` |
| Komunikasi | `call_contact`, `send_sms`, `send_whatsapp` |
| Waktu | `set_timer`, `set_alarm`, `what_time`, `what_date` |
| Media | `play_music`, `pause_music`, `next_track`, `set_volume`, `toggle_flashlight` |
| Layar | `set_brightness`, `open_settings`, `screenshot` (via Accessibility) |
| Info | `weather`, `search_web`, `navigate_to` |
| Kontrol | `cancel`, `repeat`, `stop_listening` |

### 4.7 Chatbot

- **Model utama: Gemini 3.8 Flash** (endpoint `gemini-3.8-flash`) — streaming + function calling. Terverifikasi di halaman model resminya (diperbarui 2026-09-02): input **Text, Image, Video, Audio, PDF**; output text; **Function calling: Supported**; **Search grounding: Supported**; Structured output: Supported; Thinking: low/medium/high; input limit 1.048.576 token. **Audio generation: Not supported** (jadi model ini tidak bisa jadi TTS) dan **Live API: Not supported** (untuk suara dua arah pakai model Live, lihat poin di bawah).
- **Catatan koreksi:** draf awal dokumen ini menyebut "Gemini 3 Flash" sebagai pilihan utama. Itu keliru — `gemini-3-flash-preview` berstatus **Preview**, bukan stabil. Flash stabil terkini adalah `gemini-3.8-flash`.
- **Status free tier untuk `gemini-3.8-flash`: belum terverifikasi.** Halaman harga resmi terlalu besar untuk saya baca lengkap di sesi ini, dan kabar "free tier hanya Flash/Flash-Lite sejak April 2026" berasal dari sumber sekunder. **Periksa `ai.google.dev/gemini-api/docs/pricing` sebelum implementasi.**
- **System prompt** berisi: persona, bahasa wajib Indonesia, gaya ringkas (jawaban untuk diucapkan, bukan dibaca — maks 2–3 kalimat kecuali diminta detail), dan daftar tool yang sama dengan katalog intent.
- **Grounding:** untuk pertanyaan faktual/berita, aktifkan Google Search grounding agar tidak mengarang. Statusnya *Supported* di 3.8 Flash; **biayanya per-query dan harus jadi toggle** di pengaturan.
- **Riwayat:** simpan percakapan lokal (Room/SQLDelight), kirim hanya N pesan terakhir (mis. 10) ke API untuk menjaga kuota.
- **Fallback offline:** LLM on-device via MediaPipe LLM Inference / LiteRT (backend GPU) atau llama.cpp. Realistis di 8–12 GB RAM, tetapi **kualitas jawaban ID akan jauh di bawah Gemini**. Dipakai otomatis saat online tidak tersedia — aturan lengkapnya di §4.10. Catatan: daftar harga resmi Gemini API kini memuat **Gemma 4**, jadi pilihan model on-device perlu disurvei ulang saat Fase 3 — angka "Gemma 3n E2B int4 ±3,1 GB" di draf awal belum saya verifikasi ulang.

### 4.7a Alternatif arsitektur: Gemini 3.8 Live (speech-to-speech)

Temuan yang **mengubah ruang desain**, dan belum ada di draf awal: Gemini API kini punya model suara dua arah.

- **`gemini-3.8-live`** adalah model Live API stabil untuk "low-latency voice agent experiences". Live API mendukung **99 bahasa termasuk `id` (Indonesian)**, menerima audio **PCM 16-bit 16 kHz little-endian**, dan mengeluarkan audio PCM 24 kHz lewat WebSocket.
- Live API punya **live transcription** dengan deteksi bahasa otomatis, **`custom_vocabulary`** (hingga 1.000 istilah — sangat berguna untuk membias nama aplikasi & kontak Indonesia), dan **automatic activity detection** (VAD bawaan, bisa dimatikan). Tabel transkripsinya memuat **`id-ID`** dan bahkan **`jv-ID`** (Jawa).

Dua konsekuensi:

| Skenario | Dampak |
|---|---|
| **Jalur A (tetap seperti draf):** ASR di perangkat → teks → Flash → TTS | Perintah tetap cepat & bisa offline. Tetap pilihan utama untuk Fase 1. |
| **Jalur B:** audio langsung ke `gemini-3.8-live`, model menjawab dengan suara | **Satu WebSocket menggantikan ASR + LLM + TTS + VAD.** Jauh lebih sedikit kode, latensi lebih rendah, dan tidak bergantung pada `SpeechRecognizer` perangkat. Yang harus dibayar: butuh internet selalu, biaya per menit audio, dan perintah harus lewat function calling. |

**Rekomendasi:** pertahankan interface `AsrEngine`/`ChatEngine`/`TtsEngine` yang sudah dirancang (§6) supaya **Jalur B bisa ditambahkan sebagai satu implementasi**, bukan perombakan. Untuk "chatbot yang menjawab pertanyaan", Jalur B kemungkinan besar terasa lebih alami. Untuk "perintah cepat & offline", Jalur A tetap menang.

**Belum terverifikasi:** apakah function calling tersedia di `gemini-3.8-live` dengan reliabilitas cukup untuk mengeksekusi perintah, dan berapa biaya per menit audionya. Masuk checklist Fase 0.

### 4.8 TTS

- **MVP:** Android `TextToSpeech`, `Locale("id","ID")`. Cek ketersediaan dengan `tts.isLanguageAvailable(locale)` dan arahkan pengguna mengunduh paket bahasa Google bila `LANG_MISSING_DATA`.
- **Gaya bicara:** teks untuk TTS harus **berbeda** dari teks di layar. Buat `Reply.spoken` (ringkas, tanpa markdown, angka dieja) dan `Reply.display` (markdown, boleh panjang). LLM diminta menghasilkan keduanya sekaligus.
- **Interruption:** saat pengguna menekan mic, `tts.stop()` segera. Jangan biarkan asisten bicara menimpa dirinya sendiri.
- **Opsi upgrade (Fase 3):** voice neural offline via sherpa-onnx (Piper/Kokoro). **Catatan: dukungan bahasa Indonesia pada model Kokoro/Piper perlu diverifikasi** — Kokoro secara resmi disebut mendukung EN, FR, KO, JA, ZH; klaim "50+ bahasa" di beberapa proyek komunitas belum saya verifikasi untuk ID.

### 4.9 Memori & preferensi

- **Preferensi** (nama panggilan, kecepatan bicara, mode offline, toggle grounding): DataStore.
- **Riwayat percakapan**: database lokal, terenkripsi (SQLCipher) bila menyimpan data sensitif.
- **Slot yang dipelajari**: alias kontak & aplikasi ("buka wa" → WhatsApp). Disimpan sebagai kamus pribadi, dipakai oleh resolver slot.
- **Tidak ada profil pengguna di server.** Semua data personal tetap di perangkat.

### 4.10 Online dulu, offline bila internet tidak tersedia

Keputusan dan alasannya ada di [ADR 0002](decisions/0002-online-first-offline-fallback.md). Bagian ini menjelaskan cara kerjanya.

#### Apa yang punya dua versi, apa yang tidak

| Komponen | Online (diutamakan) | Offline (fallback) | Catatan |
|---|---|---|---|
| ASR | `createSpeechRecognizer()` — recognizer jaringan Google | `createOnDeviceSpeechRecognizer()` bila tersedia, jika tidak Whisper int8 (sherpa-onnx) | Lihat "Audio dimiliki app" di bawah |
| Router + intent + tool | — | — | **Selalu lokal.** Tidak bergantung pada koneksi. |
| Chat | `gemini-3.8-flash` + search grounding | LLM on-device (llama.cpp / MediaPipe) | Offline tanpa tool calling; perintah tetap lewat router |
| TTS | — | — | **Selalu lokal**, disengaja (ADR 0002). Pilih voice dengan `Voice.isNetworkConnectionRequired() == false`, atau Piper `id_ID`. |

Intent yang **tetap butuh internet** walau dieksekusi lokal: `weather` dan `search_web`. Saat offline, keduanya menjawab "butuh internet" alih-alih mengarang. `navigate_to`, `send_whatsapp`, dan `call_contact` hanya meluncurkan app lain, jadi tetap jalan.

#### Kapan dianggap "online"

Dua lapis, karena satu lapis saja tidak cukup:

1. **Pemantau jaringan** — `ConnectivityManager.registerDefaultNetworkCallback()`; dianggap online hanya bila jaringan punya `NET_CAPABILITY_VALIDATED`. Tanpa syarat ini, Wi-Fi dengan captive portal (hotel, kafe) akan dikira online.
2. **Hasil permintaan nyata** — jaringan tervalidasi belum berarti Gemini bisa dijangkau atau kuota masih ada. Setiap permintaan online punya batas waktu dan penanganan error sendiri.

Data seluler dihitung online (teks chat berukuran sangat kecil). Hanya **unduhan model** yang ditahan sampai jaringan *unmetered*.

#### Aturan failover per permintaan (chat)

| Kejadian | Tindakan | Circuit breaker |
|---|---|---|
| Tidak ada jaringan tervalidasi | Langsung offline, tanpa mencoba | — |
| API key kosong | Langsung offline | — |
| Token pertama tidak datang dalam **3 s** | Batalkan, jawab offline | Hitung sebagai gagal |
| HTTP 5xx / koneksi error | Jawab offline | Hitung sebagai gagal |
| **HTTP 429** (kuota habis) | Jawab offline | **Buka segera** sampai waktu reset kuota — baca dari respons error, jangan menebak |
| Putus **di tengah** jawaban | Hentikan, tandai bubble "terputus", ucapkan *"Koneksi putus, saya jawab ulang secara offline"*, lalu jawab ulang dari awal secara offline | Hitung sebagai gagal |

Circuit breaker: **3 kegagalan berturut-turut → semua permintaan langsung offline selama 60 s**, lalu satu permintaan berikutnya dicoba online sebagai uji. Tanpa ini, setiap pertanyaan di jaringan yang lambat akan membayar 3 s menunggu sebelum turun ke offline.

Semua angka (3 s, 3 kali, 60 s) adalah **titik awal**, bukan hasil pengukuran. Setel ulang setelah uji di GT 30 Pro.

Jawaban yang putus di tengah **tidak disambung**. Menggabungkan awal kalimat dari Gemini dengan lanjutan dari model lokal menghasilkan jawaban yang tidak konsisten.

#### Audio dimiliki app (supaya ASR bisa failover tanpa minta ulang)

Masalah: bila `SpeechRecognizer` online gagal (`ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT`, `ERROR_SERVER`, `ERROR_SERVER_DISCONNECTED`), audionya sudah hilang — pengguna harus mengulang ucapan.

Solusi: app yang merekam lewat `AudioRecord` ke buffer, lalu menyuapkan audio ke recognizer lewat `RecognizerIntent.EXTRA_AUDIO_SOURCE` (tersedia sejak Android 13). Bila online gagal, **buffer yang sama** diputar ulang ke Whisper lokal. Pengguna cukup bicara sekali.

**Belum terverifikasi:** apakah recognizer Google di XOS 15 menerima `EXTRA_AUDIO_SOURCE` untuk recognizer jaringan. Masuk Fase 0. Bila tidak, fallback-nya: minta pengguna mengulang, dan ucapan berikutnya langsung diproses offline.

#### Paket offline harus diunduh selagi online

Fallback chat hanya ada bila modelnya sudah ada di perangkat. Maka:

- Onboarding menawarkan **"Unduh paket offline"** (±2,5–4,5 GB: ASR + LLM + TTS), hanya lewat jaringan unmetered.
- Status paket ditampilkan di pengaturan: *belum diunduh / sebagian / siap*.
- Bila offline dan paket belum ada: **perintah tetap jalan**; pertanyaan dijawab *"Butuh internet atau paket offline untuk menjawab ini."*

#### Mencegah jawaban offline yang mengarang

Model lokal tidak tahu kejadian terkini. Dua pengaman:

1. System prompt offline menyatakan batas pengetahuan dan meminta model mengaku tidak tahu.
2. Pertanyaan yang jelas butuh data terkini (kata kunci seperti *hari ini, sekarang, terbaru, harga, berita, skor*) saat offline dijawab dengan *"Ini butuh internet"*, tidak diteruskan ke model lokal.

#### Kejujuran di UI

- Setiap bubble jawaban diberi tanda asal: **online** atau **offline**.
- Saat berpindah online → offline, asisten mengucapkan satu kali *"Sedang offline, jawaban mungkin kurang lengkap."* Tidak diulang di setiap jawaban.
- Toggle **"Selalu offline"** di pengaturan mematikan jalur online sepenuhnya (untuk privasi atau hemat data).

#### Memori & baterai

- Model lokal **tidak dimuat selama online**. Memuatnya memakan ±3 GB RAM dan 2–4 s.
- Saat pemantau jaringan melaporkan hilang, atau circuit breaker terbuka, **dan app sedang di layar depan**: mulai memuat model lokal di latar supaya jawaban offline pertama tidak menunggu inisialisasi.
- Saat kembali online dan tidak ada permintaan selama 60 s: lepaskan model lokal.

#### Anggaran latensi terburuk

Di jaringan yang lambat tapi tidak putus, permintaan pertama bisa membayar: batas waktu 3 s + inisialisasi model lokal 2–4 s + pembangkitan. **Itu 6–8 s sebelum suara pertama**, jauh di atas target 2 s di §8. Circuit breaker membatasi ini ke satu permintaan; pemuatan awal (di atas) memangkas bagian inisialisasi.

---

## 5. Teknologi yang dipilih

| Lapisan | Pilihan utama | Alternatif | Alasan |
|---|---|---|---|
| UI | Jetpack Compose + Material 3 | XML Views | Standar baru Android, deklaratif, mudah bikin waveform & state. |
| DI | Hilt | Koin | Standar Jetpack. |
| Async | Kotlin Coroutines + Flow | RxJava | Flow cocok untuk pipeline audio & streaming LLM. |
| Audio | `AudioRecord` (16 kHz, mono, PCM 16-bit) | Oboe | Cukup untuk ASR; Oboe hanya perlu bila latensi < 20 ms. |
| ASR | Android SpeechRecognizer | sherpa-onnx Whisper | Lihat §4.3. |
| VAD / KWS | sherpa-onnx (Silero VAD) | Porcupine (Fase 4) | Gratis, open source, sudah ada binding Android. |
| LLM | Gemini API (Retrofit + SSE) | Groq / OpenRouter / on-device Gemma | Function calling + free tier Flash. |
| On-device LLM | MediaPipe LLM Inference (Gemma 3n E2B int4) | llama.cpp GGUF | MediaPipe lebih mudah; llama.cpp lebih fleksibel tapi butuh JNI. |
| TTS | Android TTS | sherpa-onnx (Piper/Kokoro) | Nol ukuran tambahan untuk MVP. |
| Storage | Room + DataStore | SQLDelight | Standar. |
| Backend | **Tidak ada** (Fase 1) | Cloud Run/Fly.io | API key Gemini **tidak boleh** dikapalkan di APK. Lihat §7 — ini masalah yang harus diputuskan sebelum rilis publik. |

---

## 6. Struktur repo yang diusulkan

```
rbitassistant/
├── README.md
├── docs/
│   ├── architecture.md          ← dokumen ini
│   ├── intents.md               ← spesifikasi router tingkat 1–2 (ada)
│   ├── privacy.md               ← apa yang keluar dari perangkat
│   └── decisions/               ← ADR (Architecture Decision Record) pendek
│       ├── 0001-not-a-system-assistant.md
│       └── 0002-online-first-offline-fallback.md
├── settings.gradle.kts
├── gradle/libs.versions.toml    ← version catalog
├── app/                         ← UI, Activity, service, DI wiring
│   └── src/main/java/dev/retrobit/assistant/
│       ├── MainActivity.kt
│       ├── chat/                ← Compose chat UI
│       └── AssistantViewModel.kt
├── core/
│   ├── audio/                   ← AudioRecord wrapper, resampling, VAD
│   ├── asr/                     ← AsrEngine + 3 implementasi (§4.3)
│   ├── router/                  ← normalizer, pattern matcher, fuzzy, LLM fallback
│   ├── nlu/                     ← IntentSpec, slot resolver, IntentCatalog loader
│   ├── tools/                   ← satu file per intent action
│   │   ├── LaunchAppTool.kt
│   │   ├── TimerTool.kt
│   │   └── …
│   ├── chat/                    ← LlmClient (Gemini), streaming, history
│   ├── tts/                     ← TtsEngine + implementasi
│   ├── memory/                  ← Room, DataStore
│   └── domain/                  ← model data & interface (tanpa dependensi Android)
├── intents/                     ← 23 katalog intent + _normalizer.yaml (ada), dikemas sebagai asset
├── tools/
│   ├── check_docs.py            ← pemeriksa: YAML, tautan, aksara nyasar, katalog intent (ada)
│   └── intent_lab.py            ← spesifikasi router tingkat 1–2 yang bisa dijalankan (ada)
└── testdata/
    ├── utterances_id.txt        ← 20 kalimat untuk uji ASR Fase 0 (ada)
    ├── golden_intents.json      ← 139 ucapan → intent + slot; kontrak router (ada)
    └── fixtures/device.yaml     ← aplikasi & kontak tiruan untuk resolver (ada)
```

Pemeriksa dokumen sudah ada dan bisa dijalankan sekarang (belum ada CI):

```bash
python3 -m venv .venv-doccheck && .venv-doccheck/bin/pip install pyyaml
.venv-doccheck/bin/python tools/check_docs.py
```

Fungsinya empat hal: (1) setiap blok berlabel `yaml` di `docs/**` harus benar-benar ter-parse dan, bila berupa katalog intent, wajib punya `id`/`patterns`/`slots`/`action`; (2) setiap tautan markdown relatif harus menunjuk berkas dan anchor yang ada; (3) tidak boleh ada aksara CJK/Kana/Hangul yang nyasar di dokumen berbahasa Indonesia; (4) katalog `intents/*.yaml` harus valid dan lulus golden set lewat `tools/intent_lab.py` ([intents.md](intents.md)).

Interface kunci (supaya tiap modul bisa diuji tanpa mikrofon/jaringan):

```kotlin
interface AsrEngine {
    val tier: AsrTier                       // ON_DEVICE | SYSTEM | LOCAL_WHISPER
    fun start(request: AsrRequest): Flow<AsrEvent>
}

interface Router {
    suspend fun route(text: String, ctx: SessionContext): Routed
}

interface Tool {
    val spec: IntentSpec
    suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult
}

interface ChatEngine {
    fun stream(messages: List<Message>, tools: List<IntentSpec>): Flow<ChatEvent>
}

interface TtsEngine {
    suspend fun speak(text: String, style: SpeechStyle)
    fun stop()
}

// --- Online dulu, offline bila perlu (§4.10) ---

data class NetState(val validated: Boolean, val metered: Boolean)

interface ConnectivityMonitor {
    val state: StateFlow<NetState>          // dari registerDefaultNetworkCallback()
}

enum class Origin { ONLINE, OFFLINE }

// ChatEvent ditambah dua jenis:
//   ChatEvent.Origin(origin)     — dikirim sekali di awal, untuk tanda di bubble
//   ChatEvent.Interrupted        — jawaban online putus di tengah; UI menandai bubble

class FailoverChatEngine(
    private val online: ChatEngine,         // Gemini
    private val offline: ChatEngine,        // LLM on-device
    private val net: ConnectivityMonitor,
    private val breaker: CircuitBreaker,    // 3 gagal → 60 s offline; 429 → sampai reset
) : ChatEngine
```

`FailoverChatEngine` adalah satu-satunya tempat keputusan online/offline untuk chat. UI dan router tidak tahu — mereka hanya melihat `ChatEngine`. `FailoverAsrEngine` mengikuti pola yang sama untuk ASR. Karena logikanya murni (tanpa Android bila `ConnectivityMonitor` dan jam di-inject), seluruh tabel failover di §4.10 bisa diuji unit.

`ToolResult` wajib membawa `spoken` (untuk TTS) dan `display` (untuk UI) — jangan pakai satu string untuk keduanya.

---

## 7. Privasi, keamanan, dan API key

| Isu | Aturan |
|---|---|
| Audio | Tidak pernah dikirim ke server kita (kita tidak punya server). Hanya ke ASR sistem bila tingkat 2 dipakai — UI harus menyatakan ini. |
| API key Gemini | **Tidak boleh di-hardcode di APK.** APK bisa di-decompile. Untuk pemakaian pribadi: key disimpan di penyimpanan terenkripsi perangkat dan diisi pengguna sendiri lewat layar pengaturan. Untuk rilis publik: butuh backend proxy (Cloud Run / Fly.io) yang menyimpan key dan membatasi kuota per user. **Keputusan ini harus diambil sebelum Fase 2 selesai.** |
| Accessibility Service | Hanya untuk aksi yang benar-benar butuh. Jangan pernah membaca/mengirim konten layar. Play Store mensyaratkan deklarasi penggunaan yang jelas. |
| Notification Listener | Matikan default; aktifkan hanya bila pengguna meminta fitur "baca/balas notifikasi". |
| Riwayat chat | Lokal, dapat dihapus total dari UI, tidak ikut di-backup ke cloud. |
| Data free tier Google | Free tier Gemini bisa memakai data untuk memperbaiki produk Google — **beri tahu pengguna**, sediakan toggle ke tier berbayar/endpoint lain bila mereka menolak. |

---

## 8. Anggaran latensi & daya

**Target latensi (dari pengguna selesai bicara → terdengar jawaban):**

| Tahap | Anggaran |
|---|---|
| Endpoint VAD (tunggu hening) | 600–800 ms |
| ASR final | ≤ 300 ms setelah endpoint |
| Router (jalur aturan/fuzzy) | ≤ 50 ms |
| Eksekusi perintah | ≤ 200 ms |
| **Total untuk perintah** | **≤ 1,5 s** |
| First token LLM (chat) | ≤ 800 ms |
| TTS first byte | ≤ 300 ms |
| **Total terasa untuk chat** | **≤ 2,0 s** |

**Daya:**

- ASR sistem hanya aktif saat dipicu (push-to-talk) → dampak baterai nyaris nol.
- Wake word always-on (Fase 4) → jaga buffer ring 16 kHz mono (±64 KB/detik PCM; pakai buffer kecil + proses per frame 32 ms). Perkiraan kasar: 1–3%/jam. **Angka ini belum diukur di GT 30 Pro — ukur sebelum menjanjikan always-on.**
- Jangan jalankan LLM on-device di background. Muat saat dibutuhkan, `close()` segera setelah selesai (inisialisasi 2–4 detik, jadi simpan di ViewModel, jangan di Composable).
- Thermal: GT 30 Pro tercatat naik ±14% suhu saat gaming 30 menit. Inferensi berat beruntun akan throttle → pakai timeout + fallback ke model cloud yang lebih kecil.

---

## 9. Roadmap

### Fase 0 — Validasi (½ hari, di perangkat nyata)
Sebelum menulis fitur apa pun, jawab lima pertanyaan ini di GT 30 Pro:

- [ ] `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` → `true` atau `false` di XOS 15?
- [ ] Kualitas transkrip `id-ID` untuk 20 kalimat uji di `testdata/utterances_id.txt` (nama aplikasi, angka, campuran Inggris–Indonesia, dua jebakan)? Umpankan tiap transkrip ke `tools/intent_lab.py` untuk melihat apakah intent-nya tetap benar.
- [ ] `TextToSpeech` dengan `Locale("id","ID")` tersedia? Kualitasnya bagaimana?
- [ ] Apakah foreground service mic bertahan 30 menit dengan XOS battery optimization aktif?
- [ ] Apakah recognizer jaringan menerima audio dari app lewat `EXTRA_AUDIO_SOURCE`? (Menentukan apakah ASR bisa failover tanpa pengguna mengulang ucapan — §4.10.)

**Kalau salah satu gagal, rencana di dokumen ini berubah.** Fase 0 murah; asumsi yang salah mahal.

### Fase 1 — MVP push-to-talk (1–2 minggu)
- [ ] Tombol mic besar + waveform + transkrip real-time (`EXTRA_PARTIAL_RESULTS`).
- [ ] Router tingkat 1–2 di Kotlin yang **lulus `testdata/golden_intents.json`**, lalu tingkat 3 (fuzzy). Katalog 23 intent sudah tersedia.
- [ ] Chat ke `gemini-3.8-flash`, streaming, ditampilkan sebagai bubble.
- [ ] TTS `id-ID` lokal dengan `spoken`/`display` terpisah.
- [ ] **Kerangka online-dulu:** `ConnectivityMonitor`, `FailoverChatEngine`, circuit breaker, tanda online/offline di bubble. Engine offline di fase ini masih *stub* yang menjawab "butuh internet" — yang penting jalurnya sudah ada dan teruji.
- [ ] Layar pengaturan: key API, toggle "Selalu offline", status paket offline.
- **Selesai bila:** 20 kalimat uji perintah tereksekusi benar ≥ 90% **baik online maupun dalam mode pesawat**, dan pertanyaan bebas terjawab dengan suara saat online.

### Fase 2 — Perintah yang benar-benar berguna (2–3 minggu)
- [ ] 20+ intent lengkap, termasuk slot resolver untuk kontak & aplikasi terpasang.
- [ ] Function calling: LLM boleh memanggil tool, bukan hanya router regex.
- [ ] Konfirmasi untuk aksi destruktif/berbiaya (kirim pesan, telepon).
- [ ] Riwayat percakapan tersimpan + bisa dihapus.
- [ ] Overlay mengambang + widget + pintasan supaya bisa dipanggil cepat.

### Fase 3 — Fallback offline penuh (2 minggu)
- [ ] ASR sherpa-onnx Whisper int8 + unduhan model terkelola (hanya lewat jaringan unmetered).
- [ ] LLM on-device menggantikan stub offline di `FailoverChatEngine` (model dipilih setelah survei ulang, §12).
- [ ] `FailoverAsrEngine` dengan buffer audio milik app (bila Fase 0 mengonfirmasi `EXTRA_AUDIO_SOURCE`).
- [ ] Pengaman pertanyaan "butuh data terkini" saat offline.
- [ ] Pemuatan awal model lokal saat jaringan hilang; pelepasan saat kembali online.
- [ ] TTS Piper `id_ID` / Supertonic sebagai alternatif TTS sistem (§16.1).
- **Selesai bila:** mencabut jaringan di tengah percakapan tidak pernah membuat asisten diam — setiap pertanyaan dijawab (online atau offline) atau ditolak dengan alasan yang jelas.

### Fase 4 — Wake word (1–2 minggu, setelah fitur lain stabil)
- [ ] Keputusan: wake word Inggris (Porcupine) vs training sendiri (sherpa-onnx KWS).
- [ ] Foreground service + notifikasi persisten + panduan whitelist baterai XOS.
- [ ] Ukur false-accept/hour dan konsumsi baterai nyata sebelum mengaktifkan default.

### Fase 5 — Rilis publik (hanya bila diperlukan)
- [ ] Backend proxy untuk API key + kuota per user.
- [ ] Kebijakan Play Store untuk Accessibility & Notification Listener.
- [ ] Kebijakan privasi + deklarasi data.

---

## 10. Cara memverifikasi

- **Unit test:** normalizer, pattern matcher, fuzzy matcher, slot resolver — semua murni Kotlin, tanpa Android. Target: seluruh katalog intent punya test dari `testdata/golden_intents.json`.
- **Instrumented test:** ASR engine memakai **WAV sebagai input**, bukan mikrofon, supaya deterministik.
- **Benchmark korpus:** 200 kalimat Indonesia campuran (perintah + pertanyaan). Metrik: akurasi intent, WER ASR, P95 latensi.
- **Uji perangkat:** skrip manual 30 langkah di GT 30 Pro, dijalankan tiap rilis, termasuk mode pesawat dan layar mati.

---

## 11. Risiko

| Risiko | Dampak | Mitigasi |
|---|---|---|
| `isOnDeviceRecognitionAvailable()` = false di XOS 15 | Mode "offline" tidak bisa pakai ASR sistem | Tingkat 3 (Whisper) sudah direncanakan; Fase 0 mengonfirmasi kebutuhan |
| Kualitas ASR ID untuk nama aplikasi/kontak | Perintah salah dieksekusi | Fuzzy resolver terhadap daftar aplikasi/kontak terpasang, bukan pencocokan string mentah |
| API key bocor dari APK | Tagihan & penyalahgunaan | Fase 5 backend proxy; MVP pakai key milik pengguna sendiri |
| XOS mematikan foreground service | Wake word mati diam-diam | Notifikasi persisten + deteksi kematian service + panduan whitelist |
| Kuota free tier Gemini habis/diubah | Chat turun kualitas | 429 membuka circuit breaker → jawaban offline otomatis (§4.10). Chat tidak mati, hanya menurun |
| Jaringan lambat tapi tidak putus | Jawaban pertama 6–8 s | Batas waktu token pertama + circuit breaker + pemuatan awal model lokal (§4.10) |
| Paket offline belum diunduh | Tidak ada fallback chat | Onboarding menawarkan unduhan; perintah tetap jalan tanpa paket |
| Model lokal mengarang soal kejadian terkini | Jawaban salah yang meyakinkan | Pertanyaan "butuh data terkini" ditolak saat offline; tanda "offline" di bubble |
| Porcupine tidak mendukung ID | Wake word ID tidak mungkin | Pakai wake word EN, atau latih KWS sendiri (beban kerja nyata) |
| Ekspektasi pengguna = "seperti Google Assistant" | Kekecewaan | Komunikasi batas §4.1 di README dan onboarding |

---

## 12. Yang belum diverifikasi (jangan dianggap fakta)

Poin-poin berikut **belum** saya konfirmasi langsung di perangkat/dokumentasi primer, dan harus dicek pada Fase 0:

1. Perilaku `SpeechRecognizer` on-device pada XOS 15 / Infinix GT 30 Pro.
2. Ketersediaan dan kualitas paket suara `id-ID` di TTS sistem perangkat ini.
3. Kualitas Whisper (int8) untuk bahasa Indonesia pada kalimat perintah pendek.
4. ~~Ketersediaan model TTS neural berbahasa Indonesia~~ — **sudah terjawab: ADA.** Lihat §16.1. Yang tersisa hanya menguji kualitas `vits-piper-id_ID-news_tts-medium` dan `supertonic-3-id`.
5. **Status free tier dan harga `gemini-3.8-flash`** — halaman harga resmi belum terbaca lengkap di sesi ini; kabar "free tier hanya Flash/Flash-Lite sejak April 2026" berasal dari sumber sekunder.
6. Angka konsumsi baterai wake word always-on pada perangkat ini.
7. **Ketersediaan function calling di `gemini-3.8-live`** dan biaya per menit audionya (§4.7a).
8. **Model Gemma on-device terbaik saat ini** — daftar harga resmi Gemini API kini memuat "Gemma 4"; angka Gemma 3n di draf awal belum disurvei ulang.
9. **Kualitas jawaban bahasa Indonesia dari LLM on-device** kelas ~4B (§16.2). Ini penentu apakah mode offline layak jadi default.
10. **Keandalan Shizuku di XOS 15** — dokumen Shizuku tidak memuat XOS dalam daftar workaround OEM-nya (§16.4).
11. **Apakah root + `/system/priv-app/` + `privapp-permissions` bisa mengaktifkan `BIND_VOICE_INTERACTION`** — jalur teoritis, belum diverifikasi (§16.3).
12. **`EXTRA_AUDIO_SOURCE` pada recognizer jaringan di XOS 15** — menentukan apakah ASR bisa failover tanpa pengguna mengulang (§4.10).
13. **Angka failover** (batas waktu 3 s, 3 kegagalan, jeda 60 s) — titik awal, belum diukur di jaringan seluler nyata.
14. **Proyek Gemini tanpa billing menolak dengan 429 alih-alih menagih** — perilaku umum free tier, tetapi harus dicek di halaman harga resmi sebelum menjanjikan "Rp 0".

**Sudah diverifikasi lewat dokumentasi primer (diperbarui 2026-09-02):** kapabilitas `gemini-3.8-flash` — input Text/Image/Video/Audio/PDF, Function calling *Supported*, Search grounding *Supported*, Structured output *Supported*, Thinking low/medium/high, Audio generation *Not supported*, Live API *Not supported*. Juga: Live API mendukung 99 bahasa termasuk `id`, dan live transcription mencantumkan `id-ID` serta `jv-ID`.

---

## 13. Referensi yang sudah dicek

- Spesifikasi Infinix GT 30 Pro — GSMArena & nanoreview (Dimensity 8350 Ultimate, APU 780, Mali-G615 MC6, 8/12 GB LPDDR5X, Android 15 / XOS 15, baterai 5.500 mAh).
- `VoiceInteractionService` membutuhkan `BIND_VOICE_INTERACTION`; dokumentasi Android menyatakan permission ini signature-level dan hanya diberikan OEM pada APK system.
- `SpeechRecognizer.createOnDeviceSpeechRecognizer()` tersedia sejak API 31, bersama `isOnDeviceRecognitionAvailable()`; ada laporan build Android 15 OEM yang mengembalikan `false` meski recognizer sistem berfungsi.
- Bahasa yang didukung Porcupine: EN, ES, FR, DE, IT, JA, KO, PT, ZH (tidak termasuk Indonesia). Free tier Picovoice ±3 user aktif/bulan, tier berikutnya $899/bln untuk ≤1.000 user.
- sherpa-onnx: dukungan Android/iOS/WASM, model VAD (Silero), KWS, Whisper multilingual, dan TTS.
- MediaPipe LLM Inference di Android mengekspos backend CPU & GPU (tanpa NPU); inisialisasi 2–4 detik sehingga session harus ditahan di ViewModel. (Angka "Gemma 3n E2B int4 ±3,1 GB" berasal dari sumber sekunder — survei ulang saat Fase 3.)
- **Primer:** `ai.google.dev/gemini-api/docs/models` dan `/models/gemini-3.8-flash` (diperbarui 2026-09-02) — daftar model stabil/preview dan tabel kapabilitas `gemini-3.8-flash`.
- **Primer:** `ai.google.dev/gemini-api/docs/live-api`, `/live-api/capabilities`, `/live-api/live-transcribe` — 99 bahasa termasuk `id`, format audio PCM 16-bit 16 kHz in / 24 kHz out, `custom_vocabulary` ≤1.000 istilah, transkripsi `id-ID` & `jv-ID`.
- **Sekunder, perlu verifikasi ulang:** perubahan free tier Gemini API per 1 April 2026 (Flash/Flash-Lite tetap free dengan kuota diperketat; Pro jadi paid-only).
- **Primer:** `k2-fsa.github.io/sherpa/onnx/tts/all/` — daftar model TTS per bahasa; seksi **Indonesian** memuat `vits-piper-id_ID-news_tts-medium` dan `supertonic-3-id`.
- **Primer:** `shizuku.rikka.app/guide/setup/` — tiga cara start Shizuku; wireless debugging (Android 11+) perlu diulang tiap reboot; daftar workaround OEM memuat MIUI/ColorOS/Flyme/EMUI/Sony, **tidak memuat XOS**.
- **Primer:** diskusi `RikkaApps/Shizuku#320` (maintainer RikkaW) — aplikasi Shizuku berjalan di UID yang sama dengan server: **2000 bila lewat adb, 0 bila lewat root**.
- **Sekunder:** analisis keamanan Shizuku — mewarisi privilege `adb shell`; tidak bisa menulis partisi terlindung, melewati SELinux, atau memberi permission signature-level.
- **Sekunder:** unlock bootloader Infinix tidak didukung resmi; GT 30 Pro (X6873) di-root lewat layanan pihak ketiga/EDL, unlock menghapus data, OTA hilang.

---

## 14. Kendala lingkungan pengembangan (sandbox tempat rencana ini ditulis)

Ini batas nyata yang sudah saya ukur langsung, bukan perkiraan. Penting diketahui sebelum Fase 1 dimulai.

### 14.1 Yang terukur di sandbox ini

```
java       TIDAK ADA          repo1.maven.org     -> HTTP 000 (timeout)
javac      TIDAK ADA          services.gradle.org -> HTTP 000 (timeout)
gradle     TIDAK ADA          api.adoptium.net    -> HTTP 000 (timeout)
kotlinc    TIDAK ADA          objects.githubusercontent.com -> HTTP 000
adb        TIDAK ADA
sdkmanager TIDAK ADA          github.com          -> HTTP 200
                              pypi.org            -> HTTP 200
                              registry.npmjs.org  -> HTTP 200
```

Ditambah: `apt-get update` gagal (`Connection failed` ke `deb.debian.org`), dan paket `openjdk-17-jdk-headless` tidak ditemukan di cache apt.

### 14.2 Artinya

**Tidak ada satu pun bagian Android dari proyek ini yang bisa dibangun atau diuji di sini.** Tidak ada JDK, tidak ada Gradle, tidak ada Android SDK, dan jalur unduhannya (Maven Central, Gradle distributions, Adoptium, Android repository Google) semuanya terblokir. Yang bisa berjalan di sini hanya tooling berbasis Python dan Node — itu sebabnya `tools/check_docs.py` dibuat dalam Python.

Konsekuensi untuk Fase 1:

| Bagian proyek | Bisa dikerjakan di sini? |
|---|---|
| `docs/`, katalog intent YAML, korpus uji, pemeriksa dokumen | ✅ Ya — sudah berjalan |
| Logika murni: normalizer, pattern matcher, fuzzy matcher, slot resolver | ⚠️ Bisa **ditulis**, **tidak bisa dijalankan** (butuh JDK + Gradle + Maven Central) |
| Modul Android (UI Compose, service, ASR, TTS) | ❌ Tidak bisa dibangun |
| APK, instrumented test, uji di perangkat | ❌ Tidak bisa |

### 14.3 Jalur keluar

1. **Build di mesin Anda** — Android Studio di PC/laptop, atau Termux di GT 30 Pro. Paling cepat dan paling jujur.
2. **CI di GitHub Actions** — runner `ubuntu-latest` punya akses jaringan penuh ke Maven Central dan Google Maven. **Belum terverifikasi** karena saya tidak bisa memicu run dari sini; harus diuji pada push pertama yang memuat workflow.
3. **Prototipe logika dalam Python dulu** — **sudah diambil untuk router tingkat 1–2**: `tools/intent_lab.py` + `testdata/golden_intents.json`. Risiko dua implementasi dikendalikan dengan menjadikan golden set sebagai kontrak yang wajib dilulusi router Kotlin ([intents.md](intents.md)). Jalur ini **tidak** dipakai untuk bagian yang bergantung pada Android (ASR, TTS, eksekusi tool).

**Rekomendasi:** jalur 1 untuk kode Android, jalur 2 dipasang sejak awal supaya tiap commit teruji, dan jangan pakai jalur 3 kecuali ada alasan kuat.

---

## 15. Kendala yang berada di luar kendali kita

| Kendala | Sifat | Apa yang bisa dilakukan |
|---|---|---|
| `BIND_VOICE_INTERACTION` signature-level | Permanen, kebijakan Android | Terima. Posisi produk = aplikasi asisten, bukan asisten sistem (ADR 0001). |
| Porcupine tak mendukung bahasa Indonesia | Kebijakan vendor | Wake word bahasa Inggris, atau latih KWS sendiri (biaya nyata). |
| Free tier Picovoice ±3 user aktif/bulan | Kebijakan vendor | Cukup untuk pemakaian pribadi; tidak untuk rilis publik. |
| XOS 15 agresif mematikan proses latar | Perilaku OEM | Foreground service + notifikasi persisten + panduan whitelist baterai. Tidak ada jaminan setara layanan sistem. |
| Kuota & harga Gemini API bisa berubah kapan saja | Kebijakan vendor | Deteksi `429`, pesan jelas ke pengguna, siapkan endpoint alternatif. |
| Kualitas ASR ID di perangkat tidak bisa dipastikan dari jauh | Ketergantungan perangkat | **Fase 0 wajib dijalankan Anda sendiri di GT 30 Pro** — lima pertanyaan di §9. |

**Kendala terbesar secara praktis:** Fase 0 hanya bisa dijalankan oleh orang yang memegang Infinix GT 30 Pro itu. Selama lima pertanyaan di §9 belum terjawab, setiap pilihan ASR/TTS di dokumen ini masih berupa hipotesis yang masuk akal — bukan fakta.

---

## 16. Varian "100% gratis & tanpa kuota": perlu root atau Shizuku?

**Jawaban pendek: tidak perlu root. Shizuku juga belum tentu perlu — dan keduanya tidak menentukan apakah asisten ini gratis.**

Yang menentukan "gratis & unlimited" adalah **apakah seluruh pipeline berjalan di perangkat**. Root/Shizuku adalah soal *privilege*, bukan soal *biaya*. Menjawab dengan root berarti menjawab pertanyaan yang salah.

### 16.1 Stack on-device penuh (biaya Rp 0, kuota 0)

| Komponen | Pilihan gratis | Status |
|---|---|---|
| VAD | Silero VAD via sherpa-onnx | Tersedia, gratis |
| ASR | sherpa-onnx + Whisper **multilingual** int8 | Tersedia. Bukti dukungan ID: paket spoken-language-ID sherpa-onnx menyertakan `id-indonesian.wav`. **Kualitas pada kalimat perintah pendek belum diukur.** |
| Perintah | Router regex + fuzzy + katalog intent lokal | Gratis selamanya — ini justru bagian paling andal |
| Chatbot | LLM on-device: llama.cpp (GGUF) atau MediaPipe/LiteRT | Gratis, tanpa kuota. **Kualitas jawaban ID di bawah Gemini Flash.** |
| TTS | **Piper `vits-piper-id_ID-news_tts-medium`** via sherpa-onnx | ✅ **Terverifikasi ada** — halaman TTS sherpa-onnx punya seksi "Indonesian" |
| TTS alternatif | **`supertonic-3-id`** via sherpa-onnx | ✅ Terverifikasi ada |
| Wake word | sherpa-onnx KWS (latih sendiri) | Perlu dataset + training |
| Search grounding / berita terkini | — | ❌ **Tidak ada pengganti offline.** Ini hilang total di mode offline. |

**Ini menyelesaikan salah satu butir "belum terverifikasi" di §12:** di draf awal saya menulis ketersediaan TTS neural berbahasa Indonesia "perlu diverifikasi". Ternyata **memang ada**, dan ada dua: Piper `id_ID` dan Supertonic 3 `id`. Yang tersisa untuk diuji adalah kualitas suaranya, bukan keberadaannya.

### 16.2 Harga yang sebenarnya dibayar

Bukan uang — **kualitas**:

1. **Chatbot offline akan terasa lebih bodoh** daripada Gemini Flash, terutama untuk pertanyaan yang butuh pengetahuan luas atau penalaran panjang. Untuk perangkat 8/12 GB RAM, kelas model yang muat adalah ~4B parameter terkuantisasi.
2. **Tidak ada informasi terkini.** Tanpa search grounding, pertanyaan "harga Infinix GT 30 Pro sekarang" atau "berita hari ini" tidak bisa dijawab benar.
3. **Model memakan penyimpanan.** GT 30 Pro tidak punya slot microSD: ASR ±100–250 MB + LLM 2–4 GB + TTS ±60–120 MB, semua di penyimpanan internal.
4. **Perintah tidak terpengaruh.** Jalur perintah (buka aplikasi, timer, telepon, volume) sama sekali tidak butuh LLM cloud — di sinilah mode offline paling masuk akal.

**Keputusan (diperbarui):** draf sebelumnya merekomendasikan offline sebagai default. Pengguna memilih sebaliknya — **online dulu, offline bila internet tidak tersedia**. Rinciannya di §4.10 dan [ADR 0002](decisions/0002-online-first-offline-fallback.md).

Kombinasi ini tetap memenuhi syarat "gratis & tanpa batas": online memakai free tier; saat kuota habis (429) atau sinyal hilang, stack on-device di tabel §16.1 mengambil alih. Stack di atas bukan lagi mode utama, melainkan **jaring pengaman**.

### 16.3 Kapan Shizuku berguna — dan batasnya

Kegunaan nyata Shizuku untuk proyek ini adalah **menjaga asisten tetap hidup di XOS**, bukan membuka kemampuan baru.

| Kemampuan | Tanpa Shizuku | Dengan Shizuku (UID 2000/shell) | Dengan root (UID 0) |
|---|---|---|---|
| Whitelist battery optimization | Manual di Settings | ✅ lewat `appops`/`cmd deviceidle` | ✅ |
| Grant permission tanpa dialog | ❌ | ✅ | ✅ |
| Baca/tulis `Settings.Secure` & `Settings.Global` | ❌ | ✅ (`WRITE_SECURE_SETTINGS`) | ✅ |
| Disable/unfreeze aplikasi sistem | ❌ | ✅ | ✅ |
| **Jadi asisten sistem** (`BIND_VOICE_INTERACTION`) | ❌ | ❌ | ⚠️ lihat catatan |
| Mempercepat ASR/LLM on-device | — | ❌ tidak berpengaruh | ❌ tidak berpengaruh |
| Meningkatkan kualitas jawaban | — | ❌ tidak berpengaruh | ❌ tidak berpengaruh |

Batas yang **tidak** bisa dilewati Shizuku (sesuai dokumentasi & analisis keamanannya): tidak bisa menulis partisi terlindung, tidak bisa memodifikasi boot image, tidak bisa melewati SELinux, tidak bisa memuat kernel module, dan **tidak bisa memberikan permission signature-level**. Shizuku hanya mewarisi apa yang diizinkan `adb shell`.

Catatan soal root dan `BIND_VOICE_INTERACTION`: permission itu mensyaratkan APK **ditandatangani kunci platform** *dan* di-preinstall OEM — bukan sekadar "punya akses root". Jalur teoritis root (pasang APK ke `/system/priv-app/` + whitelist `privapp-permissions`) **belum saya verifikasi** dan jangan dianggap tersedia.

### 16.4 Biaya praktis Shizuku di GT 30 Pro

- Android 11+ bisa mulai lewat **wireless debugging** tanpa PC, tetapi dokumen resmi Shizuku menyatakan **langkah start harus diulang setiap reboot**. Untuk asisten yang diharapkan selalu siap, ini friksi nyata.
- Dokumen Shizuku memuat workaround khusus OEM untuk **MIUI, ColorOS, Flyme, EMUI, Sony** — **XOS tidak tercantum**. Artinya keandalannya di Infinix **belum terverifikasi**.
- Shizuku sendiri meminta: jangan matikan Developer options/USB debugging, USB mode "Charge only"/"No data transfer", dan (Android 11+) aktifkan "Disable adb authorization timeout".

**Rekomendasi:** jangan mulai dari Shizuku. Jalankan dulu Fase 0 butir ke-4 — apakah foreground service mic bertahan 30 menit di XOS. Kalau bertahan, Shizuku tidak diperlukan sama sekali. Kalau tidak, baru pertimbangkan.

### 16.5 Root: tidak disarankan

- Unlock bootloader Infinix **tidak didukung resmi**; tersedia lewat layanan pihak ketiga berbayar atau tool "ENG bootloader" via EDL yang dibagikan di forum.
- **Unlock menghapus seluruh data**, dan **OTA resmi hilang** setelah root.
- Risiko bricking nyata, dan manfaatnya untuk proyek ini **nol**: kemampuan utama yang diinginkan (asisten sistem) tidak terbuka oleh root.

Satu-satunya alasan root yang masuk akal di sini: Shizuku jadi bisa start otomatis saat boot dan tidak perlu pairing ulang. Itu terlalu mahal untuk manfaat sekecil itu.
