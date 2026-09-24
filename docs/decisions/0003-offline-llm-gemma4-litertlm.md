# ADR 0003 — LLM offline: Gemma 4 E2B di atas LiteRT-LM

- **Status:** Diterima bersyarat. Syaratnya: uji GPU di Fase 0 ([`../architecture.md`](../architecture.md) §9)
- **Tanggal:** 2026-09-24
- **Menggantikan:** "Gemma 3n E2B int4 via MediaPipe" di draf awal
- **Konteks:** jalur offline di [ADR 0002](0002-online-first-offline-fallback.md) dan [`../architecture.md`](../architecture.md) §4.10

## Masalah

Saat internet tidak ada atau kuota habis, pertanyaan bebas harus dijawab model yang berjalan di Infinix GT 30 Pro (Dimensity 8350, Mali-G615 MC6, RAM 8/12 GB). Model dan runtime mana?

Draf awal menyebut "Gemma 3n E2B int4 (±3,1 GB) via MediaPipe LLM Inference". Kedua bagian itu sudah basi:

- **MediaPipe LLM Inference untuk Android sudah deprecated.** Dokumentasi resmi menyatakannya *maintenance-only* dan meminta migrasi ke **LiteRT-LM**.
- **Gemma 4 sudah rilis** (2 April 2026, Apache 2.0) dengan varian on-device E2B dan E4B.

## Keputusan

| Aspek | Pilihan |
|---|---|
| Runtime | **LiteRT-LM** (Kotlin, `com.google.ai.edge.litertlm:litertlm-android`) |
| Model default | **Gemma 4 E2B**, berkas `gemma-4-E2B-it.litertlm` dari `litert-community` — **2.583 MB** |
| Model opsional | **Gemma 4 E4B** — **3.654 MB**. Hanya ditawarkan bila GPU terbukti jalan dan RAM 12 GB |
| Backend | GPU (OpenCL) bila tersedia, CPU sebagai cadangan |
| Tool calling offline | **Tidak.** Perintah tetap lewat router lokal (§4.5) |
| Distribusi | Diunduh setelah instal, lewat jaringan unmetered. Tidak dikemas di APK |

## Bukti (semua dari sumber primer, diambil 2026-09-24)

### Ukuran dan kecepatan — kartu model `litert-community`

Diukur di **Samsung S26 Ultra**, bukan di GT 30 Pro: 1024 token prefill, 256 token decode, konteks 2048.

| Model | Backend | Decode (token/s) | Token pertama (s) | Memori CPU (MB) | Berkas (MB) |
|---|---|---|---|---|---|
| E2B | GPU | 52,1 | 0,3 | 676 | 2.583 |
| E2B | CPU | 46,9 | 1,8 | 1.733 | 2.583 |
| E4B | GPU | 22,1 | 0,8 | 710 | 3.654 |
| E4B | CPU | 17,7 | 5,3 | 3.283 | 3.654 |

Waktu token pertama **tidak termasuk waktu memuat model**. Waktu muat tidak dipublikasikan dan harus diukur sendiri.

### Kualitas bahasa Indonesia — SEA-HELM (AI Singapore, diperbarui 18 Sep 2026)

| Model | Skor Indonesia | Rentang 95% |
|---|---|---|
| Gemma 4 E2B | **64,13** | −1,39 / +1,41 |
| SEA-LION v4.5 (Gemma E2B) | **63,96** | −1,38 / +1,37 |
| Gemma 4 31B (pembanding: model besar) | 79,75 | −1,25 / +1,20 |

Sebagai gambaran, selisih 15 poin antara E2B dan 31B adalah **harga** menjalankan model di HP.

### Kemampuan agentik — kartu model Gemma 4

Tau2 (penggunaan tool): **E2B 24,5%**, E4B 42,2%, 31B 76,9%. Model kecil sering salah memanggil tool. Karena itu tool calling offline tidak diaktifkan.

## Alasan

1. **LiteRT-LM adalah penerus resmi** dan satu-satunya yang masih dikembangkan: function calling, constrained decoding, Multi-Token Prediction (hingga 2,2× lebih cepat), serta pemuatan audio/vision sesuai kebutuhan.
2. **E2B, bukan E4B, sebagai default.** Berkas 1 GB lebih kecil, decode 2,4× lebih cepat di GPU, dan memori di CPU separuhnya. E4B lebih pintar (MMMLU 76,6% vs 67,4%), tetapi di HP kelas menengah jawaban yang lambat terasa lebih buruk daripada jawaban yang sedikit kurang pintar.
3. **SEA-LION v4.5 E2B ditolak** walaupun disetel khusus untuk Asia Tenggara. Skor Indonesianya seri dengan Gemma 4 E2B, dan modelnya hanya tersedia sebagai GGUF, sehingga butuh runtime kedua (llama.cpp + JNI). Kerumitan tambahan tanpa keuntungan yang terukur.
4. **Gemini Nano lewat AICore tidak bisa diandalkan.** Daftar perangkat Gemini Nano v2/v3 yang dipublikasikan tidak memuat Infinix. Aplikasi harus membawa modelnya sendiri.

## Risiko terbesar: GPU mungkin tidak bisa dipakai

Sumber sekunder memperingatkan bahwa sebagian chip MediaTek dan Qualcomm kelas menengah **tidak mengekspos OpenCL ke aplikasi**, dan LiteRT-LM **diam-diam turun ke CPU** tanpa error. Sumber yang sama menyebut 2–5 token/detik di CPU kelas menengah. Angka itu **bertentangan** dengan data primer S26 Ultra (46,9 token/detik di CPU), jadi kecepatan CPU di GT 30 Pro benar-benar belum diketahui.

Seberapa parah dampaknya:

| Kecepatan di GT 30 Pro | Jawaban 60 token | Layak? |
|---|---|---|
| ≥ 20 token/s | ≤ 3 s | Ya |
| 8–20 token/s | 3–8 s | Pas-pasan; jawaban harus sangat ringkas |
| < 8 token/s | > 8 s | Tidak. Mode offline hanya menjawab perintah; pertanyaan dijawab "butuh internet" |

**Cara menguji tanpa menulis kode:** pasang **Google AI Edge Gallery** dari Play Store di GT 30 Pro, unduh Gemma 4 E2B, lalu jalankan dengan backend GPU dan CPU. Aplikasi itu menampilkan waktu token pertama dan kecepatan decode. Ini ditambahkan ke Fase 0.

Implementasi harus **memeriksa backend yang benar-benar aktif** setelah inisialisasi. Jangan berasumsi GPU aktif hanya karena `Backend.GPU()` diminta.

## Yang belum terverifikasi

- Kecepatan dan waktu muat di Dimensity 8350 / Mali-G615 (Fase 0).
- Apakah Mali-G615 di XOS 15 mengekspos OpenCL ke aplikasi.
- Skor SEA-HELM Indonesia untuk Gemma 4 E4B dan Qwen 3.5 4B. Keduanya ada di leaderboard, tetapi tidak tampil di tampilan yang bisa diambil.
- Dukungan LiteRT NeuroPilot (NPU MediaTek) untuk Dimensity 8350. Yang terdokumentasi adalah Dimensity 9500; jangan direncanakan.
- Gemma 4 E2B punya ASR bawaan (encoder audio ±300M). Kualitasnya untuk bahasa Indonesia belum diuji. Bila bagus, model ini bisa sekaligus menggantikan Whisper di jalur offline dan menghemat ±240 MB.

## Alternatif yang ditolak

| Alternatif | Alasan |
|---|---|
| MediaPipe LLM Inference | Deprecated untuk Android |
| Gemma 4 E4B sebagai default | 2,4× lebih lambat di GPU; 3,3 GB memori bila jatuh ke CPU |
| SEA-LION v4.5 E2B (GGUF, llama.cpp) | Skor Indonesia seri; butuh runtime kedua |
| Gemini Nano (AICore) | Infinix tidak ada di daftar perangkat |
| Tool calling dengan model offline | Tau2 E2B 24,5%: terlalu sering salah memanggil tool |
