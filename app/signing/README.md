# Kunci tanda tangan APK

`dev-insecure.p12` adalah kunci **pengembangan** yang sengaja di-commit ke repo publik
ini, dengan kata sandi `rbitdev-insecure` (alias `rbitassistant`).

## Mengapa di-commit?

Android hanya mau memasang pembaruan APK jika tanda tangannya **sama** dengan versi
yang sudah terpasang. Sesi agen yang membangun APK ini tidak punya akses ke GitHub
Secrets, jadi kunci yang stabil antar-build hanya bisa disimpan di repo.

## Risikonya

Siapa pun bisa membuat APK lain bertanda tangan sama. Jika Anda **sendiri** memasang
APK semacam itu dari sumber lain, APK itu akan dianggap pembaruan resmi dan bisa membaca
data aplikasi (termasuk API key Gemini). Selama Anda hanya memasang APK dari halaman
Releases repo ini, risikonya kecil, tetapi tetap nyata.

## Mengganti dengan kunci rahasia (disarankan setelah v0.1 terbukti jalan)

1. Buat keystore sendiri (`keytool -genkeypair -v -storetype PKCS12 -keystore rbit.p12
   -alias rbitassistant -keyalg RSA -keysize 2048 -validity 14600`).
2. Di GitHub: *Settings → Secrets and variables → Actions*, tambahkan
   `RBIT_KEYSTORE_B64` (isi `base64 -w0 rbit.p12`), `RBIT_KEYSTORE_PASSWORD`,
   `RBIT_KEY_PASSWORD`, dan (opsional) `RBIT_KEY_ALIAS`.
3. Build berikutnya otomatis memakai kunci itu. **Sekali** saja, copot APK lama
   sebelum memasang yang baru (tanda tangan berganti), lalu isi ulang API key.

Sidik jari SHA-256 sertifikat DEV:
`01:9B:14:29:83:BB:12:0A:92:A7:8B:E2:04:58:34:51:7A:D4:51:5C:11:D5:E8:0A:6C:D1:42:FD:5E:7D:9F:5D`
