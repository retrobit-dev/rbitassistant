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

## Mode gratis & tanpa kuota

Seluruh pipeline bisa dijalankan **di perangkat, tanpa API berbayar dan tanpa kuota**: ASR Whisper int8, TTS Piper berbahasa Indonesia, katalog intent lokal, dan LLM on-device untuk pertanyaan terbuka. Rincian stack, harga yang dibayar (kualitas, bukan uang), serta jawaban atas pertanyaan **perlu root atau Shizuku?** ada di [architecture.md §16](docs/architecture.md).

Ringkasnya: **root tidak diperlukan** (dan tidak membuka kemampuan utamanya), **Shizuku belum tentu diperlukan** — keduanya soal privilege, bukan soal biaya.

## Batasan penting yang perlu diketahui sejak awal

rbitassistant adalah **aplikasi asisten**, bukan asisten default Android. Menjadi asisten default (tahan tombol Home, hotword selalu aktif milik sistem) memerlukan `VoiceInteractionService` dengan permission `BIND_VOICE_INTERACTION`, yang bersifat *signature-level* dan hanya bisa diberikan OEM pada APK system. Aplikasi pihak ketiga tidak akan pernah mendapatkannya.

Kemampuan yang tetap bisa dicapai: membuka aplikasi, menelepon, mengirim pesan, timer/alarm, kontrol musik & volume, navigasi, cuaca, pencarian web, dan (dengan izin tambahan) membaca/membalas notifikasi serta mengontrol aplikasi lain lewat Accessibility Service.

## Langkah berikutnya

`Fase 0 — Validasi` di [architecture.md §9](docs/architecture.md): empat pertanyaan yang harus dijawab di perangkat nyata sebelum satu baris kode fitur ditulis.
