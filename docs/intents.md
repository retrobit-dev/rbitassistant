# Katalog intent & aturan router tingkat 1–2

> Konteks: [architecture.md §4.5–4.6](architecture.md). Dokumen ini adalah **spesifikasi**: router Kotlin (`core/router`) harus berperilaku persis seperti yang ditulis di sini.

## Tiga berkas yang menjadi kontrak

| Berkas | Isi |
|---|---|
| `intents/*.yaml` | Satu intent per berkas: pola, slot, contoh, aksi |
| `intents/_normalizer.yaml` | Singkatan, kata pengisi, kata bilangan |
| `testdata/golden_intents.json` | 139 ucapan beserta intent dan slot yang diharapkan |

Router Kotlin dan `tools/intent_lab.py` **wajib lulus golden set yang sama**. Implementasi Python ada karena Android tidak bisa dibangun di lingkungan tempat rencana ini ditulis ([architecture.md §14](architecture.md)); ia berfungsi sebagai spesifikasi yang bisa dijalankan, bukan kode produksi.

Yang harus dibayar: ada dua implementasi yang harus dijaga tetap sama. Golden set adalah pengamannya. Bila keduanya berbeda pada satu ucapan, salah satunya menyalahi dokumen ini.

## Cara menjalankan

```bash
# Semua pemeriksaan (dokumen + katalog)
.venv-doccheck/bin/python tools/check_docs.py

# Coba satu ucapan — berguna saat menilai transkrip ASR di Fase 0
.venv-doccheck/bin/python tools/intent_lab.py "tolong buka wa dong"
```

## Normalisasi (tingkat 1)

Urutan langkah **harus persis** seperti ini:

1. Huruf kecil (`Locale.ROOT` di Kotlin).
2. `%` → ` persen `. Jam `6.30` / `6:30` → `6:30` (regex `\b(\d{1,2})[.:](\d{2})\b`). Angka ribuan `1.000` tidak tersentuh karena setelah dua digit masih ada digit.
3. Semua karakter selain huruf, angka, `:` dan spasi → spasi. Rapatkan spasi. (`alun-alun` → `alun alun`.)
4. Singkatan (`abbreviations`), dicocokkan per batas kata, **frasa terpanjang dulu** (`lampu senter` sebelum kata tunggal). Selain singkatan, daftar ini juga memuat ejaan salah dengar (`watsap` → `whatsapp`), akhiran percakapan `-in` (`kecilin` → `kecilkan`, `matiin` → `matikan`), dan durasi (`setengah jam` → `30 menit`, `seperempat jam` → `15 menit`).
5. Kata bilangan → angka (lihat di bawah).
5b. Idiom jam (`half_past`): `jam setengah N` / `pukul setengah N` → `jam (N−1):30`, hanya untuk N = 1…12 (`setengah 1` → `12:30`). Tanpa `jam`/`pukul` di depannya, teks dibiarkan.
6. Buang kata pengisi di awal lalu di akhir, berulang sampai tidak ada yang berubah. Pengisi **tidak boleh mengosongkan** ucapan: `halo` tetap `halo`.

**Teks hasil normalisasi hanya untuk mencocokkan pola.** Bila ucapan diteruskan ke chatbot, yang dikirim adalah teks asli.

### Bilangan

Deret kata bilangan diganti satu angka. Setelah satuan di bawah 100 (`satu`…`sebelas`), yang boleh menyusul hanya pengali (`belas`, `puluh`, `ratus`, `ribu`); kalau tidak, deret berhenti. Jadi `dua tiga` → `2 3`, bukan `5`.

| Ucapan | Hasil |
|---|---|
| lima belas | 15 |
| dua puluh lima | 25 |
| seratus lima | 105 |
| dua ratus lima puluh tiga | 253 |
| seribu lima ratus | 1500 |
| dua ribu dua puluh enam | 2026 |
| dua ratus ribu | 200000 |

`setengah jam` dan `jam setengah tujuh` (= 6:30) sudah ditangani (langkah 4 dan 5b). Belum ditangani: `satu setengah jam` (tercatat sebagai celah).

## Bahasa pola (tingkat 2)

| Sintaks | Arti |
|---|---|
| `kata` | literal |
| `{slot}` | menangkap slot; regex-nya ditentukan tipe slot |
| `(a\|b c)` | salah satu alternatif; tiap alternatif boleh beberapa kata |
| `[ … ]` | opsional; boleh berisi slot, alternatif, atau opsional lain |
| `[a\|b]` | singkatan untuk `[(a\|b)]` |

Aturan:

- Pola dicocokkan terhadap **seluruh** ucapan (berjangkar di awal dan akhir). Karena itu `cara buka whatsapp di laptop` tidak cocok dengan `buka {app}`.
- Satu slot hanya boleh muncul sekali per pola.
- Setiap slot yang dideklarasikan harus dipakai minimal satu pola.

Cara kompilasi ke regex, agar dua implementasi identik: setiap token diawali **satu spasi literal**, dan pola dicocokkan terhadap `" " + ucapan`. Dengan begitu bagian opsional yang tidak muncul tidak meninggalkan spasi ganda. Grup bernama ditulis `(?P<nama>…)` di Python dan `(?<nama>…)` di Java/Kotlin. Selain itu, perilaku kuantifier *lazy* keduanya sama.

### Tipe slot

| Tipe | Regex tangkapan | Resolver | Nilai hasil |
|---|---|---|---|
| `number` | `\d+` | — | bilangan bulat |
| `clock` | `\d{1,2}(:\d{2})?` | jam ≤ 23, menit ≤ 59 | `"H:MM"` (mis. `"6:30"`) |
| `enum` | alternatif dari kunci `values`, terpanjang dulu | peta kunci → nilai kanonik | string kanonik |
| `app_name` | teks *lazy* | aplikasi terpasang | nama kanonik aplikasi |
| `contact` | teks *lazy* | kontak | nama kanonik kontak |
| `text` | teks *lazy*, opsional `max_words` dan `reject_words` | — | teks ternormalisasi |

`reject_words` (boleh untuk slot tipe apa pun, paling berguna untuk `text`): slot gugur bila **salah satu katanya** ada di daftar. Contohnya kata tanya pada `search_play_store.query`, sehingga `download lagu gratis dimana` jatuh ke chat. Atau `putar kanan`/`mainkan game` pada `play_music.title`.

**Kata kepemilikan pada kontak** (`possessives` di `_normalizer.yaml`): bila kontak tidak ditemukan, resolver mencoba lagi tanpa kata kepemilikan di akhir (`mama saya` → `mama`, `bapak aku` → `bapak`) atau tanpa akhiran (`ibuku` → `ibu`). Ini bagian router, bukan normalisasi teks, sehingga berlaku sama untuk resolver fixture dan resolver kontak sungguhan.

**Resolver yang gagal menggugurkan pola itu**, lalu router mencoba pola lain. Inilah yang membuat `buka puasa jam berapa` jatuh ke chat: polanya cocok secara teks, tetapi `puasa jam berapa` bukan aplikasi terpasang.

**Nilai enum wajib string yang dikutip bila berupa `on`/`off`/`yes`/`no`.** YAML 1.1 (PyYAML) membaca `on` tanpa kutip sebagai boolean `True`, sedangkan parser YAML 1.2 membacanya sebagai string. Pemeriksa menolak nilai enum yang bukan string. Ini pernah terjadi sungguhan saat katalog ini ditulis.

### Memilih pemenang bila beberapa pola cocok

1. Untuk setiap pola yang cocok dan slotnya lolos resolver, hitung **karakter literal** = panjang ucapan ternormalisasi − total panjang teks yang ditangkap slot.
2. Dalam satu intent, ambil pola dengan karakter literal terbanyak.
3. Antarintent, yang karakter literalnya terbanyak menang.
4. Bila dua intent **berbeda** seri di puncak → hasil `ambiguous` (router tingkat atas meminta konfirmasi).

Contoh: `buka pengaturan wifi` cocok dengan `open_settings` (seluruhnya literal) **dan** berpotensi cocok dengan `buka {app}`. Yang kedua gugur di resolver, tetapi seandainya pun lolos, `open_settings` tetap menang karena literalnya lebih banyak.

## Apa yang TIDAK dilakukan tingkat 1–2

Sepuluh celah pertama (v0.1–v0.2) ditutup di v0.3 dengan data, bukan kode khusus per kasus:

| Dulu celah | Ditutup dengan |
|---|---|
| `timer setengah jam` | singkatan frasa `setengah jam` → `30 menit` |
| `bangunkan saya jam setengah tujuh` | langkah 5b `half_past` |
| `besok pagi jam lima bangunkan aku` | pola `set_alarm` berurutan terbalik |
| `putar despacito` | pola `(putar\|putarkan\|mainkan) {title}` dengan `reject_words` |
| `buka watsap` | ejaan di `abbreviations` |
| `telepon mama saya` | `possessives` |
| `kecilin suaranya`, `tolong matiin senternya` | akhiran `-in` dan `senternya` di `abbreviations` |
| `kirim pesan ke ibu aku pulang telat` | pola tanpa penanda (kontak = potongan terpendek yang dikenal resolver) |
| **salah positif** `download lagu gratis dimana` | `reject_words` kata tanya pada `search_play_store.query` |

Yang masih tersisa (tercatat `known_gap: true`):

| Masalah | Contoh di golden set | Tugas siapa |
|---|---|---|
| Kontak multikata tanpa penanda pesan | `kirim pesan ke budi santoso aku telat` → kontak `budi`, pesan `santoso aku telat` | Tingkat 4 (konfirmasi selalu tampil, jadi kesalahan terlihat sebelum terkirim) |
| Pecahan campuran | `pasang timer satu setengah jam` | Normalisasi (belum) |
| Judul lagu yang memuat kata tolak | `putar video killed the radio star` | Tingkat 4 |

Semua kasus ini ada di golden set dengan tanda `known_gap: true`. Pemeriksa **gagal** bila salah satunya tiba-tiba lulus. Tujuannya agar tanda itu dihapus dan kasusnya pindah ke daftar yang dijaga, bukan dibiarkan diam-diam.

Salah positif adalah jenis celah paling berbahaya: salah positif mengeksekusi aksi yang tidak diminta, sedangkan celah biasa hanya berakhir di chatbot. Karena itu intent dengan akibat nyata (`call_contact`, `send_sms`, `send_whatsapp`) memakai `confirmation: always`.

## Slot teks di aplikasi nyata

Golden set menyimpan slot `text` dalam bentuk **ternormalisasi**: `besok rapat jam 9`, huruf kecil, bilangan sudah jadi angka. Untuk `query` pencarian itu tidak masalah. Untuk **isi pesan** (`send_sms.message`, `send_whatsapp.message`), aplikasi nyata harus mengirim potongan **transkrip asli** (`Besok rapat jam sembilan`). Caranya: petakan rentang karakter tangkapan kembali ke teks sebelum normalisasi. Pemetaan ini belum dispesifikasikan dan belum diuji; ia masuk pekerjaan Fase 2.

## Menambah intent

1. Buat `intents/<id>.yaml`. Nama berkas harus sama dengan `id`.
2. Wajib ada: `id`, `patterns`, `slots`, `examples`, `action`, `confirmation` (`never`/`always`), `needs_network` (`true`/`false`).
3. Tambahkan **minimal 3 kasus** ke `testdata/golden_intents.json`, plus minimal satu jebakan yang mirip tapi harus menjadi `chat`.
4. Jalankan `tools/check_docs.py`. Setiap `examples` di YAML juga harus benar-benar terarah ke intent-nya sendiri.

`needs_network: true` dipakai oleh jalur offline ([architecture.md §4.10](architecture.md)): saat offline, intent seperti ini menjawab "butuh internet" alih-alih gagal diam-diam.

## Katalog saat ini (23 intent)

| Grup | Intent | Konfirmasi | Butuh internet |
|---|---|---|---|
| Aplikasi | `open_app`, `search_play_store` | tidak | `search_play_store` |
| Komunikasi | `call_contact`, `send_sms`, `send_whatsapp` | **selalu** | tidak |
| Waktu | `set_timer`, `set_alarm`, `what_time`, `what_date` | tidak | tidak |
| Media | `play_music`, `pause_music`, `next_track`, `set_volume`, `toggle_flashlight` | tidak | tidak |
| Layar | `set_brightness`, `open_settings`, `screenshot` | tidak | tidak |
| Info | `weather`, `search_web`, `navigate_to` | tidak | `weather`, `search_web` |
| Kontrol | `cancel`, `repeat`, `stop_listening` | tidak | tidak |

## Korpus Fase 0

`testdata/utterances_id.txt` berisi 20 kalimat untuk diucapkan ke recognizer di GT 30 Pro (Fase 0, [architecture.md §9](architecture.md)). Setiap barisnya wajib ada di golden set, sehingga transkrip ASR bisa langsung dinilai dua kali: seberapa mirip dengan kalimat aslinya (WER), dan apakah router tetap memilih intent yang benar.
