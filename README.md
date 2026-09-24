# rbitassistant

Asisten suara Android berbahasa Indonesia: **menjalankan perintah** ("buka WhatsApp", "setel timer 5 menit") sekaligus **menjawab pertanyaan** sebagai chatbot.

Ditujukan pertama-tama untuk **Infinix GT 30 Pro** (Android 15 / XOS 15, Dimensity 8350 Ultimate).

## Status

Rencana arsitektur. **Belum ada kode aplikasi** — repo baru berisi dokumen.

## Baca dulu

- [`docs/architecture.md`](docs/architecture.md) — pipeline lengkap (wake → VAD → ASR → router → intent/chatbot → TTS), pilihan teknologi, struktur repo, roadmap per fase, dan risiko.
- [`docs/decisions/0001-not-a-system-assistant.md`](docs/decisions/0001-not-a-system-assistant.md) — mengapa rbitassistant **tidak** menjadi pengganti Google Assistant di level sistem.

## Batasan penting yang perlu diketahui sejak awal

rbitassistant adalah **aplikasi asisten**, bukan asisten default Android. Menjadi asisten default (tahan tombol Home, hotword selalu aktif milik sistem) memerlukan `VoiceInteractionService` dengan permission `BIND_VOICE_INTERACTION`, yang bersifat *signature-level* dan hanya bisa diberikan OEM pada APK system. Aplikasi pihak ketiga tidak akan pernah mendapatkannya.

Kemampuan yang tetap bisa dicapai: membuka aplikasi, menelepon, mengirim pesan, timer/alarm, kontrol musik & volume, navigasi, cuaca, pencarian web, dan (dengan izin tambahan) membaca/membalas notifikasi serta mengontrol aplikasi lain lewat Accessibility Service.

## Langkah berikutnya

`Fase 0 — Validasi` di [architecture.md §9](docs/architecture.md): empat pertanyaan yang harus dijawab di perangkat nyata sebelum satu baris kode fitur ditulis.
