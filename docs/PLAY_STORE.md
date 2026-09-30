# Distribusi Lewat Google Play

## Dua versi aplikasi (build flavor)

| | `pilot` | `play` |
|---|---|---|
| Untuk | HP yang dipasang dinkes (MDM/adb), **wajib** untuk HP gateway puskesmas | Google Play Store |
| Izin SMS | Ya (`SEND_SMS`, `RECEIVE_SMS`) | **Tidak ada** |
| Kirim rujukan | Otomatis, antri, kirim ulang, status terpantau, ACK otomatis | Membuka aplikasi SMS HP dengan teks terisi. Status tidak bisa dipantau; layar meminta kader menelepon |
| Peran PUSKESMAS, kotak masuk bidan, kabar kapasitas | Ada | Tidak ada (butuh terima SMS) |
| Pembaruan aturan, mode nifas, skrip baca manual | Ada | Ada |

Versi `play` tidak memerlukan pengecualian kebijakan SMS, jadi jalur ini realistis untuk lolos review.
Diverifikasi: APK `play` tidak berisi izin SMS maupun penerima SMS (dicek dengan `aapt2 dump permissions`).

```bash
./gradlew :app:assemblePilotRelease   # app/build/outputs/apk/pilot/release/app-pilot-release.apk
./gradlew :app:bundlePlayRelease      # app/build/outputs/bundle/playRelease/app-play-release.aab  → upload ke Play
```

## 1. Kunci upload (sekali, simpan cadangannya)

Tanpa `keystore.properties`, build release ditandatangani kunci debug. Itu cukup untuk pilot, tetapi
**ditolak Play**.

```bash
keytool -genkeypair -v -keystore ~/bundasiaga-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Buat file `keystore.properties` di folder repositori (sudah ada di `.gitignore`, jangan di-commit):

```properties
storeFile=/Users/<anda>/bundasiaga-upload.jks
storePassword=<sandi keystore>
keyAlias=upload
keyPassword=<sandi kunci>
```

Aktifkan **Play App Signing** di Play Console: Google memegang kunci aplikasi, Anda memegang kunci
upload. Jika kunci upload hilang, bisa diganti lewat dukungan Play. Simpan `.jks` dan sandinya di
dua tempat aman.

Naikkan `versionCode` di `app/build.gradle.kts` setiap upload (sekarang 2, `versionName` 0.2.0).

## 2. Isian Play Console

Akun developer sebaiknya atas nama **organisasi** (dinkes/lembaga), bukan pribadi. Google meminta
verifikasi identitas organisasi.

### Informasi aplikasi
- Nama: BUNDA SIAGA
- Kategori: Medis (Medical)
- Deskripsi singkat (≤ 80 karakter): `Daftar tanda bahaya ibu hamil & nifas untuk kader dan bidan desa.`
- Deskripsi lengkap: jelaskan untuk kader/bidan, bahwa hasil adalah **sinyal prioritas rujukan, bukan
  diagnosis**, dan bahwa aturan masih draft sampai divalidasi. Jangan menjanjikan hasil klinis.
- Kebijakan privasi: `https://<server-dinkes>/privasi` (isi dulu bagian [kurung siku] di
  `server/src/main/resources/privasi.html`).

### App content
- **Target audience:** 18+ (tenaga kesehatan dan kader). Bukan untuk anak.
- **Health apps / fitur kesehatan** (jika ditanya): aplikasi pendukung keputusan untuk tenaga
  kesehatan dan kader, bukan alat diagnosis, tidak terhubung ke alat medis.
- **Iklan:** tidak ada.
- **Data safety** untuk versi `play` (usulan; minta pendapat petugas pelindungan data):
  - Data yang diproses: *Health info* (jawaban tanda bahaya), disimpan terenkripsi **di HP saja**.
  - Aplikasi sendiri tidak mengirim data pasien ke internet. Rujukan dikirim oleh pengguna lewat
    aplikasi SMS miliknya. Pembaruan aturan hanya **mengunduh** file, tanpa data pengguna.
  - Enkripsi saat transit: tidak berlaku (tidak ada pengiriman data oleh aplikasi).
  - Pengguna bisa menghapus data dengan menghapus data aplikasi.
  Jika ragu, nyatakan lebih banyak, bukan lebih sedikit.

## 3. (Opsional) Pengecualian izin SMS untuk versi pilot

Kebijakan Play hanya mengizinkan izin SMS untuk aplikasi SMS default atau kategori pengecualian yang
disetujui lewat formulir **Permissions Declaration**. Persetujuan tidak dijamin; `RECEIVE_SMS`
lebih sulit disetujui. Lihat [DEPLOYMENT.md](DEPLOYMENT.md) untuk kategori yang mungkin cocok.

Jika tetap ingin mencoba, unggah versi `pilot` ke **jalur uji tertutup** (closed testing) dengan
paket terpisah, lalu isi formulir. Teks usulan di bawah ditulis dalam bahasa Indonesia; formulir
Play Console berbahasa Inggris, jadi terjemahkan dulu sebelum menempelkannya.

> **Fungsi inti:** BUNDA SIAGA dipakai kader posyandu dan bidan desa di daerah terpencil yang tidak
> punya data seluler. Ketika ibu hamil atau ibu nifas menunjukkan tanda bahaya, aplikasi mengirim
> rujukan darurat berupa SMS terstruktur ke HP gateway puskesmas, mencoba ulang otomatis sampai
> jaringan menerimanya, lalu menampilkan balasan SMS puskesmas (siap / penuh / hubungi balik). SMS
> adalah satu-satunya saluran yang tersedia di daerah tersebut.
>
> **SEND_SMS:** mengirim rujukan darurat ibu dan balasan (ACK) puskesmas. Pengiriman dimulai pengguna
> dengan menekan "Kirim", lalu dicoba ulang otomatis saat tidak ada sinyal.
>
> **RECEIVE_SMS:** menerima balasan rujukan di HP kader, dan menerima rujukan masuk di HP puskesmas.
> Hanya pesan berawalan "BS1" milik aplikasi yang dibaca; semua pesan lain diabaikan, tidak disimpan,
> dan tidak dikirim ke mana pun.
>
> **Kenapa alternatifnya tidak cukup:** menyerahkan teks ke aplikasi SMS bawaan (sudah kami sediakan
> di build Play) tidak bisa mencoba ulang saat tidak ada sinyal dan tidak bisa menerima balasan,
> sehingga rujukan penting bisa gagal tanpa diketahui.

Biasanya diminta juga **video** singkat yang memperlihatkan fitur ini. Rekam dari HP nyata:
isi pemeriksaan → Kirim → mode pesawat → status "belum terkirim, dicoba ulang" → mode pesawat mati →
"Terkirim" → balasan puskesmas "Diterima". Skenario ada di [DEMO_SCRIPT.md](DEMO_SCRIPT.md).

Jika ditolak: tetap pakai versi `play` untuk Play Store, dan versi `pilot` dipasang dinkes lewat MDM.

## 4. Sebelum rilis produksi

- Tinjauan klinis selesai dan ditandatangani ([CLINICAL_REVIEW.md](CLINICAL_REVIEW.md)).
- Data faskes nyata menggantikan MOCK.
- Uji di HP fisik (P1–P20 di [MANUAL_TEST.md](MANUAL_TEST.md)).
- Mulai dari jalur **internal testing** → **closed testing** (bidan koordinator) → produksi bertahap.
