# rbitassistant

Asisten suara Android berbahasa Indonesia: **menjalankan perintah** ("buka WhatsApp", "setel timer 5 menit") sekaligus **menjawab pertanyaan** sebagai chatbot.

Ditujukan pertama-tama untuk **Infinix GT 30 Pro** (Android 15 / XOS 15, Dimensity 8350 Ultimate).

## Status

Rencana arsitektur. **Belum ada kode aplikasi** — repo baru berisi dokumen.

## Baca dulu

- [`docs/architecture.md`](docs/architecture.md) — pipeline lengkap (wake → VAD → ASR → router → intent/chatbot → TTS), pilihan teknologi, struktur repo, roadmap per fase, dan risiko.
- [`docs/decisions/0001-not-a-system-assistant.md`](docs/decisions/0001-not-a-system-assistant.md) — mengapa rbitassistant **tidak** menjadi pengganti Google Assistant di level sistem.

## Kendala yang perlu diketahui lebih awal

Dua di antaranya menentukan bentuk proyek ini:

1. **Tidak bisa menjadi asisten default Android.** `VoiceInteractionService` membutuhkan `BIND_VOICE_INTERACTION`, permission *signature-level* yang hanya diberikan OEM pada APK system. Lihat [architecture.md §4.1](docs/architecture.md) dan ADR 0001.
2. **Rencana ini tidak bisa dibangun di lingkungan tempat ia ditulis.** Sandbox penulisnya tidak punya JDK, Gradle, maupun Android SDK, dan jalur unduhannya (Maven Central, Gradle, Adoptium) terblokir — terukur, bukan dugaan. Build harus dilakukan di mesin Anda atau lewat CI. Rinciannya di [architecture.md §14](docs/architecture.md).

Selebihnya — wake word yang tidak mendukung bahasa Indonesia, XOS yang agresif mematikan proses latar, kuota API yang bisa berubah — ada di [architecture.md §15](docs/architecture.md).

## Katalog perintah (sudah bisa diuji)

23 perintah berbahasa Indonesia ada di `intents/`, dan diuji otomatis melawan 139 ucapan di `testdata/golden_intents.json`. Di dalamnya ada 22 jebakan seperti *"buka puasa jam berapa"*, yang harus dijawab chatbot dan bukan malah membuka aplikasi. Coba sendiri:

```bash
python3 -m venv .venv-doccheck && .venv-doccheck/bin/pip install pyyaml
.venv-doccheck/bin/python tools/intent_lab.py "tolong buka wa dong"
.venv-doccheck/bin/python tools/check_docs.py
```

Aturannya ada di [docs/intents.md](docs/intents.md).

## Online dulu, offline bila tidak ada internet

Asisten memakai **Gemini (online) bila tersedia**, lalu **otomatis turun ke model di perangkat** saat sinyal hilang, kuota free tier habis, atau server terlalu lambat — per permintaan, tanpa pengguna mengganti mode. Perintah (buka aplikasi, timer, telepon, volume) **selalu lokal** dan tetap jalan di mode pesawat. Suara (TTS) sengaja selalu lokal supaya tidak berganti di tengah percakapan. Rincian: [architecture.md §4.10](docs/architecture.md) dan [ADR 0002](docs/decisions/0002-online-first-offline-fallback.md).

Model offline: **Gemma 4 E2B di atas LiteRT-LM** (berkas 2,6 GB). Kecepatannya di GT 30 Pro belum diketahui, dan GPU-nya belum pasti bisa dipakai. Cara mengujinya tanpa menulis kode ada di [ADR 0003](docs/decisions/0003-offline-llm-gemma4-litertlm.md).

## Mode gratis & tanpa kuota

Jalur offline di atas adalah jaring pengaman: seluruh pipeline bisa dijalankan **di perangkat, tanpa API berbayar dan tanpa kuota**: ASR Whisper int8, TTS Piper berbahasa Indonesia, katalog intent lokal, dan LLM on-device untuk pertanyaan terbuka. Rincian stack, harga yang dibayar (kualitas, bukan uang), serta jawaban atas pertanyaan **perlu root atau Shizuku?** ada di [architecture.md §16](docs/architecture.md).

Ringkasnya: **root tidak diperlukan** (dan tidak membuka kemampuan utamanya), **Shizuku belum tentu diperlukan** — keduanya soal privilege, bukan soal biaya.

## Batasan penting yang perlu diketahui sejak awal

rbitassistant adalah **aplikasi asisten**, bukan asisten default Android. Menjadi asisten default (tahan tombol Home, hotword selalu aktif milik sistem) memerlukan `VoiceInteractionService` dengan permission `BIND_VOICE_INTERACTION`, yang bersifat *signature-level* dan hanya bisa diberikan OEM pada APK system. Aplikasi pihak ketiga tidak akan pernah mendapatkannya.

Kemampuan yang tetap bisa dicapai: membuka aplikasi, menelepon, mengirim pesan, timer/alarm, kontrol musik & volume, navigasi, cuaca, pencarian web, dan (dengan izin tambahan) membaca/membalas notifikasi serta mengontrol aplikasi lain lewat Accessibility Service.

## Langkah berikutnya

`Fase 0 — Validasi` di [architecture.md §9](docs/architecture.md): enam pertanyaan yang harus dijawab di perangkat nyata sebelum satu baris kode fitur ditulis.
