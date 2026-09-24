# ADR 0001 — rbitassistant bukan asisten sistem Android

- **Status:** Diterima (draf v0.1)
- **Tanggal:** 2026-09-24
- **Konteks terkait:** [`../architecture.md`](../architecture.md) §4.1

## Masalah

Pengguna mengharapkan asisten suara seperti Google Assistant: dipanggil kapan saja, menahan tombol Home untuk memunculkannya, mendengar hotword secara selalu aktif, dan mengendalikan seluruh perangkat. Apakah rbitassistant bisa dibangun seperti itu?

## Keputusan

**Tidak.** rbitassistant dibangun sebagai aplikasi Android biasa yang dipanggil lewat ikon, widget, pintasan, atau overlay mengambang — bukan sebagai `VoiceInteractionService` pengganti Google Assistant.

## Alasan

Untuk menjadi asisten sistem, sebuah layanan harus:

1. Mengekspos service yang meng-extend `VoiceInteractionService` dengan intent filter `android.service.voice.VoiceInteractionService`, dan
2. Memegang permission `android.permission.BIND_VOICE_INTERACTION`.

Dokumentasi Android mengklasifikasikan `BIND_VOICE_INTERACTION` sebagai **system signature permission**: hanya dapat diberikan pada APK yang ditandatangani dengan kunci platform dan di-preinstall oleh OEM. Aplikasi yang dipasang pengguna (termasuk dari Play Store) tidak dapat memperolehnya, apa pun yang ditulis di manifest.

## Konsekuensi

**Yang hilang:**

- Tidak bisa menjadi target "tahan tombol Home".
- Tidak bisa mendaftarkan hotword selalu aktif di level sistem.
- Tidak ada proses yang dijaga hidup oleh sistem untuk mendengarkan latar.

**Yang diambil sebagai pengganti:**

| Kebutuhan | Solusi |
|---|---|
| Pemanggilan cepat | Widget, pintasan launcher, overlay mengambang (permission `SYSTEM_ALERT_WINDOW`) |
| Mendengarkan latar | Foreground service bertipe `microphone` + notifikasi persisten (hanya saat pengguna mengaktifkannya) |
| Mengontrol aplikasi lain | `AccessibilityService`, opt-in eksplisit oleh pengguna |
| Membaca/balas notifikasi | `NotificationListenerService`, opt-in eksplisit |
| Perintah lintas aplikasi umum | Intent implisit/eksplisit: launch app, dial, SMS/WA, timer, alarm, geo, media session |

**Risiko yang diterima:** XOS 15 dikenal agresif mematikan proses latar. Bila foreground service dimatikan, fitur "selalu mendengarkan" berhenti. Mitigasi: notifikasi persisten, deteksi kematian service, dan panduan whitelist baterai — namun tidak ada jaminan setara layanan sistem.

## Alternatif yang dipertimbangkan

1. **Tetap menulis `VoiceInteractionService`.** Ditolak: kode tidak akan pernah aktif di perangkat pengguna; memberikan harapan palsu.
2. **Menjadi aplikasi preinstall lewat kerja sama OEM.** Di luar lingkup saat ini; dapat dibuka kembali bila ada jalur bisnis dengan Infinix/Transsion.
3. **Root + pemasangan sebagai app sistem.** Ditolak: tidak realistis untuk pengguna umum dan merusak postur keamanan.
