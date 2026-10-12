# PLAN: BUNDA SIAGA (versi pasca-hackathon)

Tujuan: alat bantu keputusan offline untuk kader/bidan desa + rujukan SMS yang tetap sampai tanpa
internet, dengan gateway puskesmas dan dasbor dinkes opsional. Bukan alat diagnosis.

## Keputusan utama (diambil sendiri, bisa diubah)

| Keputusan | Pilihan | Alasan |
|---|---|---|
| Platform lapangan | Android native, Kotlin + Compose, minSdk 26 | Satu-satunya yang andal untuk SMS otomatis + retry di background. Detail: `docs/DEPLOYMENT.md` |
| Penerima rujukan | HP Android mode PUSKESMAS sebagai gateway SMS | Tidak butuh SMS gateway komersial; tetap jalan saat internet puskesmas mati |
| Rekap wilayah | Server Ktor + SQLite + dasbor HTML, **opsional** | Dinkes butuh rekap; aplikasi tidak boleh bergantung padanya |
| Distribusi pilot | MDM/adb oleh dinkes, bukan Play Store | Kebijakan izin SMS Play; pengecualian "emergency alerts" diajukan untuk skala besar |
| Enkripsi lokal | SQLCipher 4.x + kunci dibungkus Android Keystore | Enkripsi seluruh DB, bukan per kolom |
| DI | Tanpa framework (wiring manual di `BundaApp`) | Aplikasi kecil |
| Navigasi | Back stack sederhana di ViewModel | Lebih sedikit dependensi dan kejutan |

## Asumsi yang diambil (perlu dikonfirmasi)

1. `GJN = BELUM` diartikan "belum diperiksa"; pertanyaan gerakan janin baru ditanyakan mulai 20 minggu (atau bila usia tidak diketahui).
2. Usia kehamilan tidak diketahui diperlakukan seperti ≥ 20 minggu (condong aman) di R-M03/M06/K01/K02, tetapi **tidak** di R-M11 (≥ 28, sesuai spesifikasi).
3. Dua aturan tambahan dari tim teknis, ditandai jelas: **R-U02** (tanda YA tak tercakup → minimal KUNING) dan **R-K07** (TD ≥ 140/90 sebelum 20 minggu → KUNING). Keduanya mencegah HIJAU yang menenangkan secara keliru.
4. Kode tanda di SMS: kode spesifikasi + `PDR:C/S/B` (jumlah perdarahan) + `GJB`/`GJT` (gerakan janin berkurang/tidak ada).
5. CK `-` diterima untuk SMS ketik manual dari HP biasa, dan ditandai "tanpa checksum".
6. ACK hanya diterima bila nomor pengirim ACK = nomor tujuan rujukan (dicocokkan pada bagian nasional nomor).
7. Kode pasien = inisial 2–3 huruf + nomor urut per HP (01–999). Bisa bentrok antar-HP; puskesmas membedakan lewat nomor pengirim + MSGID.
8. Eskalasi "telepon sekarang" setelah 10 menit tanpa ACK; percobaan otomatis berhenti setelah 24 jam.
9. Server menerima ringkasan kasus, dengan pengirim di-hash (SHA-256, 16 hex), tanpa nomor HP.

## Status fase

| Fase | Isi | Status | Bukti |
|---|---|---|---|
| 0 | Recon, PLAN | Selesai | file ini |
| 1 | `:rules` + tes | Selesai | `./gradlew :rules:test` (38 kasus emas, 7 property test × 20.000 input) |
| 2 | `:codec` + tes | Selesai | `./gradlew :codec:test` (fuzz 50.000, terburuk 115/160 karakter) |
| 3 | Shell aplikasi, DB terenkripsi, checklist → hasil | Selesai | `assembleDebug` + `lintDebug` 0 error; skenario Yuliana dijalankan di emulator |
| 4 | Transport SMS, antrian, penerima, ACK | Selesai | `./gradlew :outbox:test`; emulator: kirim nyata, mode pesawat → terkirim otomatis 7 dtk setelah radio nyala, ACK, dedupe (`docs/MANUAL_TEST.md`) |
| 5 | Mode PUSKESMAS: kotak masuk, detail, ACK, ringkasan | Selesai | |
| 6 | Faskes (mock), waktu tempuh, stabilisasi, skrip manual | Selesai | |
| 7 | Simulasi, metrik, dokumen kejujuran, APK | Selesai | |
| + | Server dinkes + dasbor | Selesai | `./gradlew :server:test` |
| 8 | Mode NIFAS (`nifas.json`, SMS tipe `N`) | Selesai (draft klinis) | `NifasGoldenTest` 22 kasus; emulator #35, #38 |
| 9 | Pembaruan aturan bertanda tangan (server + file) | Selesai, menunggu kunci dinkes | `RuleBundleTest`; emulator #33, #34 |
| 10 | Kabar kapasitas faskes lewat SMS (tipe `K`) | Selesai | `CapacityTest`; emulator #36, #37 |
| 11 | Versi Google Play tanpa izin SMS (flavor `play`) | Selesai, belum diunggah | `bundlePlayRelease`; emulator #39 |
| 12 | SATUSEHAT (FHIR) di server | Selesai, belum diuji ke sandbox | `FeaturesTest` dengan tiruan |
| – | Uji di 2 HP fisik dengan SIM | **Belum**: perlu perangkat Anda | `docs/MANUAL_TEST.md` |
| – | Validasi klinis (hamil **dan** nifas) | **Belum**: perlu bidan/dokter | `docs/CLINICAL_REVIEW.md` |

## Berikutnya (urut prioritas)

Audit UI dan teknis 30 September 2026: [docs/UI_AUDIT.md](docs/UI_AUDIT.md).
Beranda, pengaturan, formulir, dan kotak masuk diperbarui; draf rujukan bisa dilanjutkan,
navigasi subpertanyaan diperbaiki, dan dasbor diperkuat terhadap injeksi atribut HTML.
Validasi klinis dan uji perangkat fisik tetap belum selesai.

Daftar lengkap tugas non-kode, dengan langkahnya: [docs/LANGKAH_ANDA.md](docs/LANGKAH_ANDA.md).

1. Uji 2 HP fisik (P1–P20 di `docs/MANUAL_TEST.md`), termasuk HP RAM 2 GB dan HP Xiaomi/Oppo (penghemat baterai).
2. Tinjauan klinis aturan hamil **dan nifas** bersama bidan koordinator.
3. Data faskes nyata (nama, nomor HP resmi, waktu tempuh) menggantikan MOCK.
4. Dinkes membuat kunci aturan dan memasang kunci publik di APK (`docs/RULES_UPDATE.md`).
5. Akun Play Console + keystore upload; unggah versi `play` (`docs/PLAY_STORE.md`).
6. Registrasi SATUSEHAT, uji di sandbox, tinjauan terminologi (`docs/SATUSEHAT.md`).
7. Belum dibuat: checklist tanda bahaya bayi baru lahir; pilihan SIM di HP dual SIM.
