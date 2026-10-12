# Panduan penggunaan BUNDA SIAGA

## Menyiapkan perangkat

Pilih peran kader, bidan, atau puskesmas. Peran puskesmas tersedia pada varian Pilot.
Isi nama petugas, kode lokasi, dan nomor tujuan rujukan. Varian Pilot meminta izin
SMS dan notifikasi sesuai versi Android. Pengaturan server dapat dikosongkan bila
hanya menggunakan pemeriksaan dan SMS.

Buka **Fasilitas kesehatan → Tambah fasilitas**. Masukkan kode yang disepakati dengan
puskesmas, nama, jenis, nomor telepon resmi, dan waktu tempuh bila diketahui.
Kontak dapat diedit, dicari, ditelepon, atau dihapus. Tidak ada kontak bawaan yang
boleh dianggap sebagai fasilitas wilayah pengguna.

## Pemeriksaan dan riwayat

Pilih pemeriksaan hamil atau nifas, isi data awal, lalu jawab pertanyaan. Tombol
kembali memungkinkan perbaikan jawaban. Setelah data awal diterima, setiap langkah
tersimpan otomatis dalam database terenkripsi sebelum pindah ke pertanyaan berikutnya.

Jika aplikasi tertutup, pilih **Lanjutkan pemeriksaan** di beranda. Pemeriksaan baru
meminta konfirmasi bila masih ada pemeriksaan tertunda. Pemulihan tidak mencampurkan
versi aturan; bila aturan berubah, mulai pemeriksaan baru.

Hasil hanya ditampilkan setelah berhasil disimpan. **Riwayat pemeriksaan** memuat
seluruh hasil, termasuk yang belum dibuatkan SMS. Cari kode pasien atau pilih filter
prioritas. Arsip baru menyimpan alasan, data, saran, dan versi aturan saat pemeriksaan.
Arsip dari aplikasi lama tetap dapat dibuka, dengan keterangan bila rincian tidak tersedia.

## Rujukan dan balasan

1. Dari hasil, buka rujukan dan periksa kode pasien, data, serta nomor tujuan.
2. Pada Pilot, kirim SMS dan pantau antrean. Pada Play, lanjutkan pengiriman di aplikasi
   SMS bawaan; aplikasi tidak dapat memantau hasil pengiriman tersebut.
3. Petugas puskesmas membuka rujukan masuk dan mengirim balasan sesuai kesiapan fasilitas.
4. Bedakan status dalam antrean, diterima jaringan, laporan sampai, dan balasan petugas.
   Laporan jaringan bergantung pada operator.

Gunakan tombol telepon atau skrip komunikasi bila diperlukan. Jangan menunggu balasan
aplikasi untuk mengambil tindakan yang telah diarahkan tenaga kesehatan.

## Kapasitas fasilitas

Petugas puskesmas membuka **Kirim kapasitas**, memilih fasilitas dari daftar yang sudah
diisi, memilih status, lalu mengonfirmasi penerima. Status lokal dan seluruh pesan
siaran disimpan dalam satu transaksi sebelum diproses untuk pengiriman.

Penerima mempercayai kabar hanya dari nomor tujuan puskesmas atau kontak fasilitas
terdaftar. Nomor telepon merupakan mekanisme pembatasan pengirim, bukan autentikasi
kriptografis. Selalu konfirmasikan kapasitas sebelum merujuk.

## Privasi dan pengelolaan

- Aktifkan proteksi tangkapan layar pada **Privasi dan aktivitas**.
- SMS berisi kode pasien dan data minimal; SMS biasa tidak terenkripsi.
- Database lokal menggunakan enkripsi dan tidak termasuk cadangan otomatis.
- Pembaruan aturan hanya diterima dari paket bertanda tangan yang dipercaya.
- Validasi klinis dan uji lapangan diperlukan sebelum penggunaan dalam pelayanan pasien.
