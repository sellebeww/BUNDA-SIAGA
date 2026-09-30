# Skrip Demo (±5 menit, 2 HP Android nyata)

## Persiapan (sebelum audiens datang)

1. Dua HP Android dengan SIM aktif dan pulsa SMS: **HP-KADER** dan **HP-PUSKESMAS**.
2. Pasang APK versi **pilot** di keduanya lewat `adb install -r app/build/outputs/apk/pilot/debug/app-pilot-debug.apk`. Instal via adb menghindari blokir
   "restricted setting" Android 13+.
3. HP-PUSKESMAS: Pengaturan → peran **Puskesmas**, nama "Puskesmas Contoh", kode lokasi `PKM01`.
   Beri semua izin.
4. HP-KADER: peran **Kader posyandu**, nama "Bu Mince", kode lokasi `DSA12`, Dusun A, nomor tujuan =
   nomor HP-PUSKESMAS. Beri semua izin.
5. Di kedua HP: Menu → Data, metrik, dan simulasi → matikan "Blokir tangkapan layar" hanya jika
   layar akan diproyeksikan.
6. **Matikan Wi-Fi dan data seluler di kedua HP. SIM tetap aktif.** Tunjukkan ini ke audiens.
7. Kirim satu SMS uji dari HP-KADER ke HP-PUSKESMAS lewat aplikasi SMS biasa, untuk memastikan
   sinyal dan pulsa ada.

## Alur

**0:00, cerita.** Bu Mince di pegunungan memeriksa Ibu Yuliana, 34 minggu: sakit kepala hebat,
wajah bengkak. Tidak ada internet.

**0:40, periksa (HP-KADER).** Tekan **Periksa Ibu Hamil** → inisial "Yuliana L", usia 34 →
tekanan darah pilih **Tidak ada alat tensi** (kader tidak punya tensi) → jawab:
kejang TIDAK, pingsan TIDAK, perdarahan TIDAK, **sakit kepala hebat YA**, pandangan kabur TIDAK,
sesak TIDAK, nyeri perut TIDAK, ketuban TIDAK, gerakan janin normal, **bengkak YA**, sisanya TIDAK.

**1:30, hasil.** Muncul **MERAH, Prioritas rujuk segera**. Tunjukkan bagian "Kenapa hasilnya
begini?": aturan R-M06 terpicu **tanpa tekanan darah**. Tunjukkan saran "minta bidan mengukur
tekanan darah" dan tulisan "sinyal prioritas, bukan diagnosis". Jelaskan: hasilnya dari aturan tetap yang tertulis di file JSON,
bukan tebakan program. Bisa diaudit baris per baris.

**2:00, kirim.** **Kirim Rujukan** → layar konfirmasi memperlihatkan isi persis SMS (±60 karakter,
1 SMS) → **Kirim sekarang**. Status berubah: *Dalam antrian → Sedang dikirim → Terkirim ke
jaringan*. Jelaskan bahwa "Terkirim" belum berarti "Diterima".

**2:40, puskesmas (HP-PUSKESMAS).** Notifikasi "Rujukan MERAH masuk". Buka → detail: kode YL01,
34 minggu, tanda bahaya, lokasi DSA12. Tekan **Siap menerima**.

**3:10, ACK (HP-KADER).** Status berubah menjadi **Diterima puskesmas: Siap menerima**, plus
notifikasi. Semua tanpa internet.

**3:30, tanpa sinyal.** HP-KADER: Menu → Data, metrik, dan simulasi → aktifkan **Mode simulasi**
dan **Simulasikan tidak ada sinyal**. Pita ungu MODE SIMULASI muncul. Lakukan pemeriksaan cepat
(perdarahan YA pada 36 minggu) → kirim. Status: *Belum terkirim: tidak ada sinyal. Dicoba lagi
otomatis dalam N detik*. Lalu matikan "Simulasikan tidak ada sinyal". Tanpa menekan apa pun,
SMS **terkirim sungguhan** dari antrian.
*(Alternatif lebih nyata: aktifkan mode pesawat, kirim, lalu matikan mode pesawat. Retry otomatis
berjalan dengan backoff hingga 2 menit, jadi siapkan waktu.)*

**4:10, skrip manual.** Tunjukkan **Skrip baca manual**: kalimat siap dibacakan lewat radio/HT
saat sinyal nol sama sekali.

**4:30, penutup jujur.** Buka Menu → **Yang nyata dan yang belum**: aturan belum divalidasi klinis,
data faskes mock, SMS tidak terenkripsi, belum uji lapangan. Tiga langkah berikutnya: tinjauan
bidan, konsultasi dinkes, pilot terbatas.

## Opsional: dasbor dinkes

Jika ada laptop dengan internet: jalankan server (`docs/DEPLOYMENT.md`), isi alamat server dan token
di Pengaturan HP-PUSKESMAS, nyalakan data sebentar, lalu tunjukkan dasbor web yang terisi kasus
YL01 beserta waktu sampai dibalas.

## Jika ada yang gagal saat demo

- SMS tidak sampai: tekan **Telepon tujuan** atau **Skrip baca manual**. Ini justru bagian dari desain.
- Izin SMS ditolak: tombol "Kirim lewat aplikasi SMS biasa" muncul di layar konfirmasi.
- Delivery report tidak muncul: normal. Laporan operator tidak dijamin; yang pasti adalah ACK.
