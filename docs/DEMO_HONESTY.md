# Kejujuran Demo: apa yang NYATA, MOCK, dan BELUM DIVALIDASI

| Bagian | Status | Keterangan |
|---|---|---|
| Aturan tanda bahaya (HIJAU/KUNING/MERAH) | **BELUM DIVALIDASI** | Draft dari pedoman umum. Semua aturan `DRAFT_NEEDS_CLINICAL_REVIEW`. Lihat `CLINICAL_REVIEW.md`. |
| Mesin aturan (logika) | **NYATA** | Deterministik, offline, diuji dengan 38 kasus emas dan property test (monoton, deterministik, invarian HIJAU/MERAH). |
| Pengiriman SMS | **NYATA** | `SmsManager` biasa lewat SIM, tanpa internet dan tanpa gateway komersial. |
| Status "Terkirim ke jaringan" | **NYATA** | Hanya muncul setelah radio mengembalikan hasil OK. |
| Status "Sampai di HP tujuan" | **TIDAK PASTI** | Bergantung laporan operator (delivery report). Bisa terlambat atau tidak pernah datang. |
| Balasan puskesmas (ACK) | **NYATA** | SMS balasan berformat `BS1;A;...` yang dikirim petugas dengan menekan tombol. Dicocokkan berdasarkan nomor pengirim + nomor pesan. |
| Kirim ulang otomatis | **NYATA, dengan batas** | Foreground service + WorkManager. Mode Doze dan penghemat baterai OEM (Xiaomi, Oppo, Vivo, dll.) bisa menunda percobaan saat aplikasi lama tertutup. Tidak dijamin instan. |
| Keamanan SMS | **SMS TIDAK TERENKRIPSI** | Mitigasi: hanya kode pasien (inisial + nomor), tanpa nama, tanpa alamat. Siapa pun yang membaca SMS bisa melihat tanda bahaya dan kode lokasi. |
| Penyimpanan di HP | **NYATA** | SQLCipher (AES-256), kunci dibungkus Android Keystore. `allowBackup=false`, dikecualikan dari transfer perangkat. FLAG_SECURE aktif (bisa dimatikan untuk demo). |
| Data faskes, kontak, kapasitas, waktu tempuh | **MOCK** | `app/src/main/assets/faskes.json`: contoh, bukan data nyata. Diberi label di layar. |
| Mode simulasi | **SIMULASI** | Hanya berpura-pura "tidak ada sinyal". Pita ungu "MODE SIMULASI" selalu tampil. Setelah sinyal "dipulihkan", SMS yang terkirim adalah SMS sungguhan. |
| Rujukan simulasi di kotak masuk | **SIMULASI** | Diberi label SIMULASI, tidak dihitung di ringkasan, tidak dikirim ke server, dan ACK-nya tidak mengirim SMS. |
| Server dinas + dasbor | **NYATA (opsional)** | Ktor + SQLite. Menerima ringkasan kasus dari HP puskesmas saat ada internet. Tanpa nama dan nomor HP (pengirim di-hash). Belum diaudit keamanannya. |
| Metrik waktu | **NYATA, lokal** | Hanya dari pemakaian di HP itu. **Belum ada angka hasil uji lapangan**; jangan dikutip sebagai hasil. |
| Uji lapangan | **BELUM ADA** | Belum diuji dengan kader, bidan, atau pasien sungguhan. |
| Validasi klinis | **BELUM ADA** | |
| Aturan nifas (ibu setelah melahirkan) | **BELUM DIVALIDASI** | Draft tim teknis dari pedoman umum, `nifas-0.1.0-draft`. Lihat bagian NIFAS di `CLINICAL_REVIEW.md`. |
| Pembaruan aturan dari server/file | **NYATA, belum aktif** | Tanda tangan ECDSA + uji keamanan otomatis + nomor urut. Mati sampai dinkes memasang kunci publik di APK (`RULES_UPDATE.md`). |
| Kabar kapasitas lewat SMS | **NYATA, manual** | Diisi petugas puskesmas. Hanya diterima dari nomor tepercaya. Bisa terlambat; > 24 jam ditandai "mungkin tidak berlaku". Tidak ada jaminan tempat tersedia. |
| Versi Google Play | **NYATA, belum diunggah** | Tanpa izin SMS: status kirim tidak bisa dipantau, dan layar mengatakannya. Pengecualian izin SMS belum diajukan. |
| SATUSEHAT | **BELUM DIUJI KE SATUSEHAT SUNGGUHAN** | Diuji dengan tiruan. Pemetaan kode belum diverifikasi. Perlu registrasi dan kredensial (`SATUSEHAT.md`). |
| Uji di HP fisik | Lihat `MANUAL_TEST.md` | Yang sudah dijalankan di emulator dan yang belum ditulis terpisah di sana. |
| Distribusi Play Store | **BELUM** | Izin SMS dibatasi kebijakan Google Play. Lihat `DEPLOYMENT.md`. |
