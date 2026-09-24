# ADR 0002 — Online dulu, offline bila internet tidak tersedia

- **Status:** Diterima (draf v0.1)
- **Tanggal:** 2026-09-24
- **Menggantikan:** rekomendasi "mode offline sebagai default" di [`../architecture.md`](../architecture.md) §16.2
- **Rincian teknis:** [`../architecture.md`](../architecture.md) §4.10

## Masalah

Ada dua tuntutan yang saling tarik:

1. Pengguna ingin **gratis dan tanpa batas**.
2. Pengguna ingin jawaban yang **pintar dan terkini** — dan itu hanya diberikan model cloud (Gemini), yang free tier-nya punya kuota.

Mode offline penuh memenuhi (1) tetapi mengorbankan (2). Mode cloud penuh memenuhi (2) tetapi mati saat kuota habis atau sinyal hilang.

## Keputusan

**Online dulu. Bila online tidak bisa dipakai, turun ke offline otomatis — per permintaan, tanpa pengguna harus mengganti mode.**

"Tidak bisa dipakai" mencakup lebih dari sekadar tidak ada sinyal:

| Pemicu | Contoh |
|---|---|
| Tidak ada jaringan tervalidasi | Mode pesawat, sinyal hilang, Wi-Fi dengan captive portal |
| Kuota habis | Gemini API mengembalikan HTTP 429 |
| Layanan bermasalah | HTTP 5xx, WebSocket putus |
| Terlalu lambat | Token pertama tidak datang dalam batas waktu |
| Belum dikonfigurasi | API key belum diisi |

## Alasan

- **Kuota habis diperlakukan sama dengan tidak ada internet.** Inilah yang membuat sistem "tanpa batas": saat kuota harian free tier habis, asisten tidak mati — ia turun ke model lokal. Kualitas turun, tetapi layanan tidak berhenti.
- **Perintah tidak pernah bergantung pada internet.** Router, katalog intent, dan eksekutor tool selalu berjalan lokal (§4.5–4.6). Hanya jalur chat dan ASR yang memiliki dua versi.
- **Keputusan diambil per permintaan, bukan sekali saat start.** Sinyal seluler naik-turun; mode global yang dipilih pengguna akan selalu basi.

## Konsekuensi

**Yang didapat:**

- Jawaban terbaik saat online (Gemini + search grounding).
- Asisten tetap bekerja di mode pesawat, di area tanpa sinyal, dan saat kuota habis.
- Biaya tetap Rp 0 selama API key berasal dari proyek **tanpa billing** — free tier menolak dengan 429, bukan menagih. *(Perilaku umum free tier; verifikasi di halaman harga resmi sebelum rilis.)*

**Yang harus dibayar:**

- **Dua implementasi** untuk ASR dan chat — lebih banyak kode dan pengujian.
- **Paket offline harus diunduh selagi online** (±2,5–4,5 GB). Tanpa itu, fallback chat tidak ada. Perintah tetap jalan.
- **Latensi terburuk lebih buruk** pada jaringan yang lambat tapi tidak putus: menunggu batas waktu, lalu memuat model lokal. Circuit breaker (§4.10) membatasi ini hanya ke permintaan pertama.
- **Privasi:** online dulu berarti teks — dan audio, bila ASR jaringan dipakai — keluar ke Google secara default. Sediakan toggle **"Selalu offline"** dan nyatakan ini saat onboarding.

## Pengecualian yang disengaja

**TTS tidak ikut pola online-dulu.** Suara selalu dihasilkan lokal (TTS sistem dengan voice yang tidak butuh jaringan, atau Piper `id_ID`). Alasannya:

1. `gemini-3.8-flash` tidak bisa menghasilkan audio (*Audio generation: Not supported*), jadi TTS cloud berarti satu round-trip lagi ke layanan lain.
2. Bila TTS ikut failover, **suara asisten berganti di tengah percakapan** saat sinyal naik-turun. Itu terasa seperti rusak.

Pengecualian ini bisa ditinjau ulang bila Jalur B (`gemini-3.8-live`, §4.7a) diadopsi, karena di sana suara memang datang dari model.

## Alternatif yang ditolak

| Alternatif | Alasan ditolak |
|---|---|
| Offline default, cloud opsional | Pengguna secara eksplisit memilih online dulu; kualitas jawaban offline terlalu jauh di bawah |
| Mode manual (pengguna memilih online/offline) | Pengguna tidak tahu kapan kuota habis atau sinyal hilang; akan salah pilih |
| Hanya cloud | Mati total saat kuota habis — melanggar syarat "tanpa batas" |
| Menjalankan online & offline bersamaan, ambil yang tercepat | Memuat LLM lokal (±3 GB RAM, 2–4 s inisialisasi) di setiap permintaan menghabiskan baterai dan memicu throttling termal |
