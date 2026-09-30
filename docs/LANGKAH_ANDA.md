# Yang Harus Anda Lakukan

Semua kode untuk lima fitur baru sudah jadi dan sudah diuji (lihat [MANUAL_TEST.md](MANUAL_TEST.md)).
Dokumen ini berisi hal-hal yang **tidak bisa dikerjakan oleh kode**: keputusan, akun, kredensial,
perangkat, dan tanda tangan manusia. Urutannya sesuai prioritas.

Tanda: 🔴 wajib sebelum dipakai ke pasien · 🟡 wajib untuk fitur itu bisa jalan · ⚪ disarankan

---

## 1. Tinjauan klinis (paling penting) 🔴

Aturan **hamil** dan aturan **nifas** yang baru masih DRAFT. Aturan nifas disusun tim teknis dari
pedoman umum, **bukan** oleh tenaga kesehatan.

1. Cetak atau bagikan [CLINICAL_REVIEW.md](CLINICAL_REVIEW.md) ke bidan koordinator (dan dokter/psikolog
   untuk pertanyaan kesehatan jiwa nifas).
2. Minta mereka mengisi kolom "Disetujui oleh" per aturan dan menjawab pertanyaan terbuka
   (ada 7 untuk hamil dan 7 untuk nifas).
3. Jika ada perubahan, ubah file JSON aturan lalu jalankan pemeriksaan (tidak perlu mengubah kode):
   ```bash
   ./gradlew -q :server:rulesTool --args="check rules/src/main/resources/rules/nifas.json"
   ```
4. Setelah disetujui, ubah `"status"` dan `"verified"` di JSON, lalu minta mereka menandatangani tabel.

## 2. Uji di 2 HP fisik dengan SIM 🔴

Emulator sudah lulus, tetapi HP sungguhan belum. Ikuti daftar P1–P20 di
[MANUAL_TEST.md](MANUAL_TEST.md). Yang baru untuk fitur ini: P14–P20 (nifas, kapasitas, pembaruan
aturan, versi Play). Siapkan HP murah (RAM 2 GB) dan HP Xiaomi/Oppo/Vivo.

APK untuk dipasang:
```bash
./gradlew :app:assemblePilotDebug
adb install -r app/build/outputs/apk/pilot/debug/app-pilot-debug.apk
```

## 3. Data faskes nyata 🔴

`app/src/main/assets/faskes.json` masih MOCK. Minta dari dinkes: nama faskes, **nomor HP resmi**
tiap faskes, dan waktu tempuh dari tiap dusun. Nomor HP ini penting: kabar kapasitas lewat SMS
hanya dipercaya dari nomor HP gateway puskesmas atau dari nomor yang tercatat di file ini.

Formatnya sama persis dengan file sekarang; field `id` (misalnya `PKM1`) maksimal 8 huruf/angka
dan dipakai di SMS kapasitas.

## 4. Pembaruan aturan tanpa rilis ulang APK 🟡

Fiturnya sudah ada, tetapi **mati** sampai dinkes memasang kuncinya. Langkah lengkap:
[RULES_UPDATE.md](RULES_UPDATE.md). Ringkasnya:

1. Putuskan siapa yang memegang kunci (satu orang di dinkes + cadangan di tempat aman).
2. Di laptop yang aman, buat kunci:
   ```bash
   ./gradlew -q :server:rulesTool --args="keygen dinkes-2026 /path/aman/kunci"
   ```
3. Salin **satu baris** yang dicetak ke `app/src/main/assets/rules_trust.txt`, lalu build ulang APK.
   Semua HP yang memakai APK ini akan menerima pembaruan yang ditandatangani kunci itu.
4. Simpan file `*.private.pem` secara offline. **Jangan** di-commit, jangan di server, jangan di chat.

## 5. Kabar kapasitas faskes lewat SMS ⚪

Tidak butuh akun apa pun. Yang perlu diputuskan dan dilatih:

1. Siapa di puskesmas yang memperbarui status (misalnya bidan jaga setiap pergantian shift).
2. Nomor kader dan bidan desa dimasukkan di HP puskesmas: Menu → Kirim kabar kapasitas → Penerima.
   Tombol "Tambahkan semua pengirim rujukan" mengisi otomatis dari rujukan yang pernah masuk.
3. **Anggaran pulsa**: satu kabar = satu SMS per penerima. 30 kader × 3 kali sehari ≈ 90 SMS/hari.
4. Isi materi pelatihan: kabar kapasitas bukan jaminan; untuk kasus MERAH tetap telepon dulu.

## 6. Server dinkes 🟡 (untuk pembaruan aturan dan SATUSEHAT)

1. Siapkan server dengan HTTPS (lihat [DEPLOYMENT.md](DEPLOYMENT.md), bagian "Menjalankan server").
2. Taruh file aturan bertanda tangan di folder `BS_RULES_DIR` (misalnya `nifas.bundle.json`).
3. Isi alamat server di Pengaturan setiap HP (semua peran sekarang punya kolom ini).
4. Halaman `https://<server>/privasi` adalah kebijakan privasi; **isi bagian [kurung siku]** di
   `server/src/main/resources/privasi.html` sebelum dipublikasikan.

## 7. Google Play 🟡 (hanya jika ingin distribusi lewat Play Store)

Langkah lengkap dan teks siap tempel: [PLAY_STORE.md](PLAY_STORE.md). Ringkasnya:

1. Buat akun Google Play Console atas nama **dinkes/lembaga** (bukan pribadi). Biaya pendaftaran berlaku.
2. Buat keystore upload dan `keystore.properties` (petunjuk di PLAY_STORE.md). Simpan cadangannya.
3. Upload `app-play-release.aab` (versi **tanpa izin SMS**, yang ini pasti bisa lolos kebijakan SMS).
4. Opsional: ajukan pengecualian izin SMS untuk versi pilot. Keputusan ada di Google dan **tidak dijamin**.
   Sementara itu, HP gateway puskesmas tetap dipasang versi pilot lewat MDM/adb.

## 8. SATUSEHAT 🟡 (setelah pilot)

Langkah lengkap: [SATUSEHAT.md](SATUSEHAT.md). Yang hanya bisa Anda lakukan:

1. Daftarkan puskesmas/dinkes di platform SATUSEHAT → dapat **Organization ID**, **Client ID**,
   **Client Secret** (sandbox dulu, produksi setelah verifikasi).
2. Siapkan ID **Location** (Poli KIA) dan ID **Practitioner** (bidan/nakes) tiap puskesmas.
3. Minta ahli terminologi mencocokkan kode di `server/src/main/resources/satusehat/terminology.json`
   dengan Panduan Interoperabilitas SATUSEHAT. Semua entri masih `verified: false`.
4. Uji di **sandbox** dengan data dummy dulu. Jangan langsung produksi.
5. Pastikan dasar hukum: menautkan kode pasien ke identitas asli adalah pemrosesan data kesehatan
   (UU 27/2022). Butuh persetujuan ibu atau dasar hukum lain, dan penilaian dampak (DPIA).

## 9. Legal dan tata kelola 🔴

1. Konsultasi dinkes/Kemenkes: apakah aplikasi ini termasuk alat kesehatan (software as medical device).
2. Kepatuhan UU 27/2022: tunjuk pengendali data, tentukan masa simpan, siapkan formulir persetujuan.
3. Isi kebijakan privasi (poin 6.4).

---

## Ringkasan: apa yang sudah jadi vs. yang Anda kerjakan

| Fitur | Kode | Diuji | Yang Anda kerjakan |
|---|---|---|---|
| Mode NIFAS | Selesai | Unit test + emulator | Tinjauan klinis (1), uji HP (2) |
| Pembaruan aturan | Selesai | Unit test + emulator (server → HP, tolak file palsu, fallback) | Buat kunci + isi trust file (4), server (6) |
| Kapasitas via SMS | Selesai | Unit test + emulator (kirim, terima, tolak nomor asing) | Data faskes nyata (3), latih petugas (5) |
| Versi Google Play | Selesai (flavor `play`, `.aab`) | Build + emulator | Akun Play, keystore, formulir (7) |
| SATUSEHAT | Selesai (server) | Unit test dengan SATUSEHAT tiruan | Registrasi, kredensial, uji sandbox, terminologi (8) |
