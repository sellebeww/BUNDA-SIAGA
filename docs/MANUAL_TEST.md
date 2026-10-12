# Uji Manual dan Status Verifikasi

Istilah yang dipakai di dokumen ini:
**unit test** = lulus di JVM · **build** = APK/lint berhasil · **emulator** = dijalankan di emulator
Android 17 (Pixel_6, API 37) · **perangkat** = HP fisik dengan SIM (**belum dilakukan**).

## Sudah diverifikasi

| # | Skenario | Tingkat | Hasil |
|---|---|---|---|
| 1 | Aturan: 38 kasus emas, monoton, deterministik, invarian HIJAU/MERAH | unit test | Lulus |
| 2 | Codec: round-trip + fuzz 50.000, panjang ≤ 160, charset GSM-7, checksum, versi tak dikenal | unit test | Lulus (terburuk 115 karakter) |
| 3 | State machine: gagal → retry backoff → sukses, timeout, duplikat, ACK salah nomor, kedaluwarsa, eskalasi 10 menit, sinyal kembali | unit test | Lulus |
| 4 | Server: token wajib, upsert idempoten, dasbor ber-sandi, escape HTML | unit test | Lulus |
| 5 | `assembleDebug` + `lintDebug` | build | 0 error |
| 6 | Setup peran Bidan, dialog izin SMS/notifikasi berbahasa Indonesia | emulator | OK |
| 7 | FLAG_SECURE: tangkapan layar hitam saat aktif | emulator | OK |
| 8 | Skenario Ibu Yuliana (34 mgg, SKH + bengkak, TD 168/112) → MERAH, 5 aturan dengan alasan | emulator | OK (R-M04, R-M06, R-M07, R-K01, R-K02) |
| 9 | Layar konfirmasi menampilkan isi persis SMS (53 karakter) | emulator | OK |
| 10 | Kirim SMS nyata lewat `SmsManager`: Antrian → Terkirim → Sampai (delivery report) | emulator | OK |
| 11 | SMS masuk (`adb emu sms send`) → kasus MERAH di kotak masuk, notifikasi | emulator | OK |
| 12 | Tekan "Siap menerima" → SMS ACK terkirim | emulator | OK |
| 13 | ACK masuk → status kader "Diterima puskesmas: Siap menerima" | emulator | OK |
| 14 | ACK dengan MSGID tak dikenal / dari nomor lain → diabaikan | emulator | OK (status tidak berubah) |
| 15 | Kader tanpa alat tensi, ketuban pecah → MERAH (R-M10) + saran "minta bidan ukur TD" | emulator | OK |
| 16 | **Mode pesawat nyata**: rujukan antri, status jujur "Belum terkirim, penyebab: radio HP mati", retry backoff 10→20→40 dtk | emulator | OK (3 percobaan saat radio mati) |
| 17 | **Mode pesawat dimatikan tanpa menyentuh aplikasi** → terkirim otomatis | emulator | OK: 7 detik setelah radio nyala (listener status sinyal, tidak menunggu backoff 40 dtk) |
| 18 | SMS rujukan yang sama masuk 2× | emulator | OK: 1 kasus |
| 19 | SMS ketik manual huruf kecil tanpa checksum (`-`) | emulator | OK: diterima, ditandai "tanpa checksum" |
| 20 | SMS berawalan BS1 tapi rusak → "FORMAT?" di kotak masuk; SMS lain (promo) diabaikan | emulator | OK |
| 21 | Gateway PUSKESMAS → server di laptop (`http://10.0.2.2:8080`) setelah ACK | emulator + server lokal | OK: 5 kasus tersinkron, dasbor tampil |
| 22 | Server: tanpa token 401, dasbor tanpa sandi 401, upload idempoten | server lokal (curl) | OK |

### Fitur v0.2 (30 Sep 2026)

| # | Skenario | Tingkat | Hasil |
|---|---|---|---|
| 23 | Aturan nifas: 22 kasus emas; uji keamanan `RuleSelfTest` 20.000 input untuk hamil **dan** nifas; config yang salah ditolak saat dimuat | unit test | Lulus |
| 24 | Paket aturan bertanda tangan: round-trip, kunci tak dipercaya, isi diubah, amplop berbohong, aturan tidak aman ditolak saat ditandatangani | unit test | Lulus |
| 25 | Codec: tipe `N` (nifas) dan `K` (kapasitas), fuzz campuran, encoding lama tidak berubah | unit test | Lulus (terburuk 131 karakter) |
| 26 | Kapasitas: waktu DDHHMM → waktu nyata (termasuk bulan sebelumnya), pengirim asing ditolak, kabar lama tidak menimpa yang baru | unit test | Lulus |
| 27 | Server: kasus nifas, buka database v0.1 tanpa kehilangan data, cermin aturan (hanya file valid disajikan), halaman privasi | unit test | Lulus |
| 28 | SATUSEHAT dengan server tiruan: bentuk Bundle, cache token, cari NIK (NIK tidak tersimpan), kirim sekali saja, CSRF ditolak | unit test | Lulus |
| 29 | `assemblePilotDebug`, `assemblePlayDebug`, `assemblePilotRelease`, `bundlePlayRelease`, lint kedua flavor | build | 0 error |
| 30 | APK `play`: tanpa `SEND_SMS`/`RECEIVE_SMS`, tanpa SmsReceiver/OutboxService | build (`aapt2`) | OK |
| 31 | **Upgrade** v0.1.0 → v0.2.0 di HP berisi data (database v1 → v2) | emulator | OK: kotak masuk lama utuh |
| 32 | Alat CLI: keygen (kunci privat `rw-------`), defaults, check, sign, verify; bundle yang diubah ditolak | laptop | OK |
| 33 | Server menyajikan `nifas.bundle.json`; HP memasang **otomatis lewat WorkManager** tanpa disentuh; layar Aturan: "nifas-0.1.1-draft, seq 2, Pembaruan (server), kunci dinkes-uji" | emulator + server lokal | OK |
| 34 | APK tanpa kunci tepercaya + file pembaruan tersimpan → kembali ke aturan bawaan, tanpa crash, layar menyatakan "belum aktif" | emulator | OK |
| 35 | SMS `BS1;N;…` masuk ke HP PUSKESMAS → kasus MERAH berlabel NIFAS, detail "Ibu nifas, Hari ke-3" | emulator | OK |
| 36 | SMS `BS1;K;…` dari nomor faskes terdaftar → notifikasi + layar Faskes "SMS dari …, 2 menit lalu"; dari nomor asing → diabaikan (metrik `capacity_rejected UNTRUSTED_SENDER`) | emulator | OK |
| 37 | PUSKESMAS: tambah penerima, kirim kabar kapasitas (dialog biaya pulsa) → Terkirim → Sampai | emulator | OK |
| 38 | Pemeriksaan NIFAS lengkap (BIDAN): validasi hari > 42, 16 pertanyaan, perdarahan tanpa pertanyaan jumlah, hasil MERAH N-M03 + N-K01 + N-K05, SMS `BS1;N;…` 51 karakter terkirim dan sampai | emulator | OK |
| 39 | Versi `play`: HP bekas gateway diarahkan ke Pengaturan tanpa pilihan PUSKESMAS; kader → rujukan → membuka Google Messages dengan teks terisi → kartu "status tidak bisa dipantau, telepon" | emulator | OK |

Catatan: `adb emu gsm voice unregistered` **tidak** memblokir SMS di emulator (SMS tetap
terkirim), jadi uji tanpa sinyal memakai mode pesawat (`cmd connectivity airplane-mode enable`).

Catatan: emulator tidak memantulkan SMS ke nomornya sendiri, jadi SMS masuk disuntikkan lewat
konsol emulator (`adb emu sms send <nomor> "<teks>"`). Ini tetap memakai jalur penerimaan
Android yang sama (`SMS_RECEIVED`).

## Belum diverifikasi: wajib diuji di 2 HP fisik

| # | Skenario | Langkah | Yang diharapkan |
|---|---|---|---|
| P1 | End-to-end nyata | Ikuti `PANDUAN_PENGGUNAAN.md` dengan Wi-Fi dan data mati | Status kader menjadi "Diterima" |
| P2 | Mode pesawat → kembali | HP-KADER mode pesawat, kirim rujukan, tunggu 1 menit, matikan mode pesawat, **jangan sentuh HP** | Terkirim otomatis ≤ ± 1 menit setelah sinyal kembali |
| P3 | Aplikasi ditutup saat antrian | Seperti P2, tetapi geser tutup aplikasi dari Recents sebelum sinyal kembali | Notifikasi "Mengirim rujukan" tetap ada; terkirim otomatis |
| P4 | Layar mati lama (Doze) | Seperti P2, layar mati 30 menit sebelum sinyal kembali | Catat berapa lama sampai terkirim. **Ini batas yang diketahui** |
| P5 | HP Xiaomi/Oppo/Vivo (penghemat baterai OEM) | P3 dan P4 dengan optimasi baterai aktif, lalu nonaktif | Catat perbedaannya untuk materi pelatihan |
| P6 | Pesan ganda | Kirim SMS rujukan yang sama 2× dari HP-KADER (aplikasi SMS biasa, salin teks mentah) | Satu kasus, "Pesan ganda diterima: 1" |
| P7 | Izin SMS ditolak | Tolak izin SMS di HP-KADER, coba kirim | Muncul penjelasan + tombol "Kirim lewat aplikasi SMS biasa" |
| P8 | APK dari file manager (Android 13+) | Pasang APK lewat file manager, bukan adb | Kemungkinan izin SMS terkunci. Coba "Izinkan setelan terbatas" |
| P9 | HP RAM 2 GB, layar 360 dp | Jalankan alur lengkap | Tidak ada teks terpotong, tombol ≥ 48 dp, tidak ada crash |
| P10 | TalkBack | Aktifkan TalkBack, jalankan checklist | Level dibacakan "MERAH. Prioritas rujuk segera" |
| P11 | Dual SIM | HP dengan 2 SIM | SMS keluar lewat SIM default SMS. Belum ada pilihan SIM di aplikasi |
| P12 | Server | `./gradlew :server:run` di laptop, HP-PUSKESMAS isi alamat + token, nyalakan data | Kasus muncul di dasbor, "Belum terkirim: 0" |
| P13 | Reboot saat antrian | Mode pesawat, kirim, restart HP, matikan mode pesawat | Terkirim setelah boot (BOOT_COMPLETED) |
| P14 | Nifas end-to-end | HP-KADER: Periksa Ibu Nifas, hari ke-3, perdarahan banyak → kirim | HP-PUSKESMAS: kasus MERAH berlabel NIFAS, "Hari ke-3"; ACK sampai ke kader |
| P15 | Kapasitas ke kader | HP-PUSKESMAS: tambah nomor HP-KADER sebagai penerima, kirim "Penuh" | HP-KADER: notifikasi; Menu → Faskes menampilkan "Penuh, SMS dari <nomor puskesmas>" |
| P16 | Kapasitas dari nomor asing | Dari HP ketiga, kirim manual `BS1;K;PKM1;S;5;<DDHHMM>;-` ke HP-KADER | Diabaikan; status faskes tidak berubah |
| P17 | Kabar kapasitas lama | Tunggu > 24 jam setelah P15 | Layar Faskes: "lebih dari 24 jam, mungkin sudah tidak berlaku" |
| P18 | Pembaruan aturan dari server | Kunci dinkes di APK, bundle di server HTTPS, data seluler nyala | Menu → Aturan: sumber "Pembaruan (server)"; pemeriksaan berikutnya memakai versi baru |
| P19 | Pembaruan aturan dari file | Kirim `nifas.bundle.json` lewat Bluetooth/WA → Menu → Aturan → Pilih file | "Terpasang: …"; file yang diedit sedikit pun → "Ditolak" |
| P20 | Versi Play | Pasang `app-play-release` (atau dari jalur uji Play) di HP kader | Tidak ada permintaan izin SMS; "Kirim lewat aplikasi SMS biasa" membuka aplikasi SMS dengan teks terisi |

## Perintah emulator yang berguna

```bash
adb emu sms send 5551234 "BS1;R;A7K2;YL07;M;34;168/112;SKH.BKK;DSA12;301545;3F"   # SMS masuk
adb emu gsm voice unregistered   # sinyal hilang
adb emu gsm voice home           # sinyal kembali
adb logcat -b crash              # crash
```

## Pemeriksaan tambahan versi 0.3

- Tutup proses aplikasi setelah menjawab beberapa pertanyaan. Buka kembali dan
  lanjutkan pemeriksaan dari beranda; jawaban sebelumnya tetap tersedia lewat Kembali.
- Akhiri pemeriksaan tanpa membuat rujukan. Pastikan hasil muncul di Riwayat dan
  dapat dicari memakai kode pasien serta filter prioritas.
- Buka hasil lama setelah aturan diperbarui. Prioritas, alasan, dan versi hasil
  harus tetap sesuai arsip; pemeriksaan tertunda dengan versi berbeda tidak dilanjutkan.
- Tambahkan fasilitas, edit kontaknya, rotasikan perangkat saat mengedit, cari nama,
  dan hapus setelah konfirmasi. Nomor tidak valid serta kode duplikat harus ditolak.
- Buka keyboard pada formulir dengan ukuran teks 150%; tombol dan kolom terakhir
  harus tetap dapat dijangkau dengan menggulir.
- Ketuk Kirim dan tombol balasan berulang. Satu tindakan tidak membuat beberapa
  pesan identik; status yang sudah terkirim tidak kembali menjadi antrean baru.
- Tingkatkan APK dari skema database v2 ke v3 tanpa menghapus data. Hasil, rujukan,
  balasan, dan penerima lama harus tetap ada.
