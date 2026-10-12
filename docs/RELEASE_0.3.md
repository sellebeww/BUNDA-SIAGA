# BUNDA SIAGA 0.3 — 12 Oktober 2026

## Perubahan

- Beranda dengan ringkasan pemeriksaan, jumlah rujukan dalam antrean, dan akses
  pemeriksaan tertunda serta riwayat.
- Riwayat semua pemeriksaan dengan pencarian kode pasien dan filter prioritas.
  Hasil dapat dibuka meskipun belum dibuatkan rujukan.
- Penyimpanan otomatis setelah data awal dan setiap jawaban. Sesi dipulihkan
  dari database terenkripsi; perubahan versi/urutan aturan mencegah pencampuran aturan.
- Hasil baru menyimpan rincian alasan, saran, data, prioritas, dan versi aturan.
  Membuka arsip tidak mengevaluasi ulang aturan yang mungkin telah berubah.
- Kontak faskes kini dikelola melalui aplikasi: tambah, edit, cari, telepon, hapus.
  Daftar fasilitas tidak diisi dengan nomor fiktif.
- Alur penggunaan memakai transport SMS asli. Pengaturan rekayasa sinyal dan
  pembuatan rujukan buatan telah dihapus. Data pengujian dari versi lama tetap
  dikecualikan dari daftar operasional serta sinkronisasi.
- Formulir menyesuaikan area keyboard. Kolom peran mendukung teks yang membungkus.
- Nomor telepon, kode fasilitas, waktu tempuh, dan URL server diperiksa sebelum disimpan.
- APK rilis tidak memakai kunci debug sebagai pengganti keystore rilis.

## Perbaikan keandalan

- Hasil tampil setelah transaksi penyimpanan selesai. Kegagalan simpan tidak
  menghapus pemeriksaan tertunda atau menampilkan hasil seolah telah disimpan.
- Draf rujukan dan tautannya ke hasil disimpan dalam satu transaksi.
- Nomor tujuan kosong dilengkapi saat mengantrekan pesan, di dalam mutex antrean.
  Pesan yang sudah dikirim tidak tertimpa data draf yang dibaca sebelumnya.
- Ketukan bersamaan saat mengirim/balas ditahan. Balasan identik yang sudah
  tercatat tidak diantrekan lagi, kecuali pesan sebelumnya telah kedaluwarsa.
- Balasan dan referensi kasusnya, serta seluruh siaran kapasitas, disimpan secara atomik.
- Kegagalan tindakan menampilkan umpan balik. Status pemeriksaan pembaruan aturan
  dibersihkan walaupun proses gagal.
- Migrasi database v2 → v3 hanya menambahkan kolom hasil, tabel sesi, dan tabel fasilitas.

## Verifikasi

Hasil akhir: **BUILD SUCCESSFUL**. APK Pilot dan Play versi `0.3.0-debug` berhasil
terbentuk. Lint masing-masing varian: **0 error, 10 warning** (`UseKtx` dan
`OldTargetApi`, sama seperti sebelum perubahan). Tidak ditutupi dengan baseline.

Tes otomatis: **106 lulus**, tanpa failure, error, atau skipped.

| Cakupan | Tes lulus |
|---|---:|
| Aturan | 26 |
| Codec SMS | 20 |
| Antrean pengiriman | 26 |
| Server | 14 |
| Aplikasi Android | 13 |
| SQLite: sinkronisasi dan migrasi | 7 |

Perintah verifikasi:

```bash
./gradlew :rules:test :codec:test :outbox:test :server:test \
  :app:testPilotDebugUnitTest :app:assemblePilotDebug :app:assemblePlayDebug \
  :app:lintPilotDebug :app:lintPlayDebug --offline --no-parallel --max-workers=1
python3 -m unittest discover -s tests -v
```

Tes migrasi menjalankan SQL yang dihasilkan Room terhadap skema v2, memeriksa
kecocokan kolom dengan skema v3, serta memastikan hasil dan tautan rujukan lama
bertahan. Ini bukan uji driver SQLCipher di perangkat.

## Pemeriksaan langsung di emulator

Android API 37.1, Pixel 6, instance read-only terpisah dengan data pengujian lokal.
Tidak mengirim SMS ke nomor fisik.

- Aplikasi terpasang dan membuka layar pengaturan tanpa crash.
- Beranda serta formulir awal ditinjau dari tampilan aktual.
- Formulir menerima inisial dan pilihan usia tidak diketahui.
- Tiga jawaban tersimpan, lalu proses aplikasi dihentikan dengan `am force-stop`.
  Setelah aplikasi dibuka kembali, kartu Lanjutkan pemeriksaan memulihkan
  **pertanyaan 4 dari 15**, sesuai posisi terakhir.
- Pemeriksaan diselesaikan tanpa membuat rujukan. Hasil muncul di Riwayat dan
  dapat dibuka kembali dengan prioritas serta waktu yang sama.
- Kontak fasilitas berhasil ditambahkan, waktu tempuh diperbarui menjadi 30 menit,
  lalu dihapus melalui dialog konfirmasi. Daftar kembali ke keadaan kosong.
- Tata letak ringkasan dipadatkan setelah tinjauan screenshot, dan pilihan
  pemeriksaan ditempatkan sebelum akses riwayat.

## Batas yang masih perlu ditangani sebelum pelayanan pasien

Aturan klinis dan panduan tidak diubah atau dinyatakan telah tervalidasi. Perlu
peninjauan tenaga kesehatan, pengisian kontak resmi, uji SMS/ACK dua perangkat fisik,
uji kegunaan, serta keystore dan konfigurasi server milik pengelola.

Penyimpanan otomatis dimulai setelah formulir data awal dilanjutkan. Satu perangkat
menyimpan satu pemeriksaan tertunda. Waktu tempuh berasal dari input pengguna,
bukan perhitungan rute. Uji otomatis tidak menggantikan uji perangkat fisik.
