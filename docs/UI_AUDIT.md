# Audit UI dan kualitas BUNDA SIAGA

Tanggal: 30 September 2026. Lingkup: kode Android Compose, alur pemeriksaan/rujukan,
penyimpanan, modul aturan/codec/outbox, dan dasbor server. Ini adalah penilaian teknis,
bukan validasi klinis atau sertifikasi keamanan.

## Penilaian

Fondasi aplikasi cukup baik untuk pengembangan pilot: mesin aturan terpisah dari UI,
bekerja offline, alasan hasil bisa dibaca, database memakai SQLCipher/Keystore, SMS
mempunyai antrean/retry/ACK, dan data simulasi diberi label. Tes otomatis sudah mencakup
banyak kasus aturan, format SMS, dan transport.

Aplikasi **belum siap dinyatakan layak untuk operasional klinis**. Aturan masih DRAFT,
data faskes masih MOCK, dan verifikasi pada dua HP fisik dengan SIM serta validasi
bidan/dokter masih diperlukan. Keberhasilan build tidak menggantikan verifikasi tersebut.

## Perubahan yang dilakukan

- Sistem visual: latar putih kehijauan, teal untuk aksi utama, aksen dengan kontras lebih
  tinggi, hierarki tipografi, kartu dan jarak yang konsisten. Status merah/kuning/hijau
  tetap disertai ikon dan teks.
- Beranda: kartu pemeriksaan hamil/nifas dengan penjelasan, keterangan kemampuan offline,
  akses langsung faskes/panduan, keadaan kosong, dan riwayat dengan identitas/status terpisah.
- Pengaturan: kelompok peran, identitas, kontak; pengaturan dusun/server opsional dapat
  dibuka sesuai kebutuhan. Token server disamarkan dengan tombol tampil/sembunyikan.
- Formulir pemeriksaan: informasi dikelompokkan dalam kartu. Seluruh baris pilihan dapat
  disentuh; pilihan radio tidak lagi mempunyai dua target aksesibilitas yang sama.
- Kotak masuk: pencarian kode pasien/lokasi, filter Semua/Belum dibalas/Merah, jumlah
  rujukan, serta pesan khusus untuk hasil pencarian kosong.
- Beranda/kotak masuk memakai daftar lazy agar tidak merender semua kartu sekaligus.
- Lebar konten Android dibatasi 720 dp pada layar lebar. Top bar menghindari inset status
  bar ganda dan memakai elipsis untuk judul panjang.
- Layar pembukaan mempunyai penjelasan proses dan tombol coba lagi bila pembukaan data gagal.
- Dasbor web: tipografi, kartu ringkasan, tabel, jarak, dan fokus keyboard diperbaiki.

## Bug yang diperbaiki

| Temuan | Perbaikan |
|---|---|
| Draf rujukan tidak terlihat di beranda, termasuk versi Play | Draf muncul di riwayat dan membuka konfirmasi, bukan status seolah sudah terkirim |
| Ketukan cepat berpotensi membuat beberapa draf | Pembuatan diserialkan, aksi pembukaan dijaga, dan navigasi identik tidak digandakan |
| Draf dibuat sebelum pemeriksaan selesai tersimpan | Pembuatan draf menunggu penyimpanan pemeriksaan dan memakai identitas pasien yang ditangkap saat selesai |
| Tombol kembali dari pilihan jumlah perdarahan melewati pertanyaan | Kembali menutup subpertanyaan dahulu; berlaku untuk tombol atas dan tombol sistem |
| Posisi gulir/state lokal pertanyaan dapat terbawa ke pertanyaan berikutnya | Komposisi layar diberi identitas berdasarkan tujuan navigasi |
| Usia yang dipilih tidak diketahui hilang saat kembali ke formulir | Pilihan dipulihkan dari pemeriksaan aktif |
| Aplikasi SMS/telepon gagal dibuka tanpa umpan balik | Pesan kegagalan ditampilkan; handoff hanya dikonfirmasi jika aplikasi berhasil dibuka |
| Fallback SMS memakai nomor draf kosong meskipun pengaturan berisi nomor | Fallback memakai nomor tujuan yang sudah di-resolve |
| Versi Play dapat menawarkan retry SMS langsung pada data lama | Aksi retry langsung dibatasi pada varian pilot |
| File aturan dibaca seluruhnya sebelum batas ukuran diperiksa | Pembacaan dibatasi 512 KiB + satu byte untuk mendeteksi file berlebih |
| Nilai tingkat dari server dimasukkan ke atribut HTML tanpa pembatasan | Kelas CSS dibatasi pada tingkat yang dikenal; nilai tampil tetap di-escape; ditambah tes regresi |
| Waktu ACK negatif mengacaukan median dasbor | Durasi negatif dikeluarkan dari perhitungan median; ditambah tes regresi |

## Verifikasi otomatis

- `:rules:test`: 26 metode tes, termasuk golden cases/property tests.
- `:codec:test`: 18 tes.
- `:outbox:test`: 20 tes.
- `:server:test`: 14 tes, termasuk dua tes regresi baru.
- `:app:testPilotDebugUnitTest`: 9 tes.
- Total **87 tes**, tanpa kegagalan/error/skipped pada laporan hasil yang diperiksa.
- APK debug **pilot dan play** berhasil dibangun.
- Lint **pilot dan play**: 0 error, 10 warning per varian. Warning tersisa berkaitan
  target SDK dan saran penggunaan ekstensi KTX; tidak disembunyikan dengan baseline baru.
- Sejumlah tugas yang inputnya tidak berubah menggunakan cache Gradle.

APK: `app/build/outputs/apk/pilot/debug/app-pilot-debug.apk` dan
`app/build/outputs/apk/play/debug/app-play-debug.apk`.

## Verifikasi langsung di emulator

Pixel 6, Android API 37.1; pengaturan demo sementara dipulihkan setelah pengujian.

- Beranda, formulir awal, dan layar pertanyaan diperiksa dari screenshot aktual.
- Mengisi inisial, memilih usia tidak diketahui, memilih tanpa alat tensi, lalu membuka
  pertanyaan pertama berhasil.
- Pertanyaan 1 → 2 → 3 berjalan; tombol sistem kembali dari rincian perdarahan kembali
  ke pertanyaan 3, bukan pertanyaan sebelumnya.
- Kembali ke data ibu lalu melanjutkan tetap mempertahankan pilihan usia tidak diketahui.
- Filter Belum dibalas menghilangkan baris yang telah dibalas.
- Pencarian yang tidak cocok menampilkan keadaan kosong khusus.
- Beranda diperiksa pada ukuran teks sistem 150%; teks membungkus di dalam kartu.
- Tidak mengirim SMS nyata atau menyelesaikan pemeriksaan pasien pada uji visual ini.
- Emulator awal sempat mengalami ANR pada System UI. Uji diulang dengan GPU host dan
  RAM 2 GB; ini bukan bukti performa aplikasi pada perangkat fisik RAM 2 GB.

Contoh tampilan saat uji: [pertanyaan](screenshots/pemeriksaan.png) dan
[pencarian tanpa hasil](screenshots/pencarian-kosong.png).

Batas pengujian: belum menjalankan keseluruhan alur hamil/nifas sampai ACK, TalkBack,
rotasi pada semua layar, atau verifikasi visual varian Play dan dasbor web pada sesi ini.
Keduanya tetap diperiksa melalui build/lint atau tes server sesuai daftar di atas.

## Pekerjaan berikutnya sebelum rilis

1. Validasi aturan hamil/nifas dan bahasa instruksi bersama bidan/dokter, sesuai
   [CLINICAL_REVIEW.md](CLINICAL_REVIEW.md). Audit ini tidak mengubah ambang klinis.
2. Ganti faskes contoh dengan data resmi, siapkan kunci kepercayaan aturan dan prosedur
   pembaruannya. Jangan menganggap daftar contoh sebagai fasilitas yang siap menerima.
3. Jalankan [MANUAL_TEST.md](MANUAL_TEST.md) pada dua HP fisik: SMS nyata, ACK, sinyal
   hilang, baterai hemat, reboot, dual SIM, dan perangkat RAM rendah.
4. Uji kegunaan dengan kader/bidan: TalkBack, ukuran teks besar, penggunaan satu tangan,
   dan pemahaman perbedaan dikirim/diterima jaringan/dibalas puskesmas.
5. Pemulihan pemeriksaan yang belum selesai setelah proses Android dimatikan masih perlu
   ditambahkan. ViewModel mempertahankan sesi saat rotasi, bukan setelah process death.
6. Perketat konfigurasi produksi: keystore rilis resmi (build saat ini masih dapat memakai
   debug key sebagai fallback), token server yang saat ini disimpan di SharedPreferences,
   HTTPS, kebijakan retensi data, dan pengikatan identitas faskes ke token gateway.
7. SATUSEHAT perlu uji sandbox dan tinjauan pemetaan terminologi dengan akun resmi.
8. Checklist bayi baru lahir dan pemilihan SIM belum tersedia; keduanya fitur lanjutan,
   bukan fitur yang sudah diuji pada perubahan ini.
