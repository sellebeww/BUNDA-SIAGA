# Tinjauan Klinis Aturan BUNDA SIAGA

**Status semua aturan: `DRAFT_NEEDS_CLINICAL_REVIEW`.** Aturan disusun tim teknis dari pedoman
umum (tanda bahaya Buku KIA Kemenkes / WHO; ambang tekanan darah 140/90 dan 160/110). **Belum ada
satu pun aturan yang divalidasi bidan atau dokter.** Jangan dipakai untuk pasien sungguhan sebelum
tabel ini diisi dan ditandatangani.

- Sumber kebenaran: `rules/src/main/resources/rules/hamil.json` (versi `hamil-0.1.0-draft`) dan
  `rules/src/main/resources/rules/nifas.json` (versi `nifas-0.1.0-draft`, lihat bagian NIFAS di bawah)
- Perilaku yang terkunci oleh tes: `GoldenCasesTest.kt` (38 kasus hamil), `NifasGoldenTest.kt`
  (22 kasus nifas), `PropertyTest.kt`, dan `RuleSelfTest` (uji keamanan untuk setiap versi aturan)
- Cara mengubah aturan: edit JSON → naikkan `version` dan `seq` → jalankan
  `./gradlew -q :server:rulesTool --args="check <file.json>"` → perbarui tabel ini.
  Aplikasi tidak perlu diubah. Untuk mengirim ke HP tanpa APK baru: [RULES_UPDATE.md](RULES_UPDATE.md).

## Kebijakan level (bukan skor)

- Level = level tertinggi dari aturan yang terpicu. Tidak ada penjumlahan skor.
- **HIJAU** hanya jika semua pertanyaan kritis terjawab dan tidak ada aturan terpicu.
- Data kritis yang belum diperiksa → minimal **KUNING** (R-U01), tidak pernah MERAH karena data kurang saja.
- Menambah satu tanda bahaya tidak pernah menurunkan level (diuji dengan 20.000 input acak).
- Pertanyaan kritis: KEJ, KSD, PDR, SKH, PDG, SSK, NPT, KTB; ditambah GJN bila usia ≥ 20 minggu atau tidak diketahui.

## Tabel aturan

| ID | Level | Kondisi (ringkas) | Alasan / catatan untuk peninjau | Disetujui oleh | Tanggal | Catatan peninjau |
|---|---|---|---|---|---|---|
| R-M01 | MERAH | Kejang = YA | Tanda bahaya umum | | | |
| R-M02 | MERAH | Pingsan/tidak sadar = YA | Tanda bahaya umum | | | |
| R-M03 | MERAH | Perdarahan = YA **dan** (UK ≥ 20 atau UK tidak diketahui atau jumlah = banyak) | **Perlu ditinjau:** batas 20 minggu dan kategori bercak/sedang/banyak | | | |
| R-M04 | MERAH | TD ≥ 160 sistolik **atau** ≥ 110 diastolik | Rentang hipertensi berat | | | |
| R-M05 | MERAH | Sakit kepala hebat **dan** (pandangan kabur atau nyeri ulu hati) | Kombinasi tanda preeklamsia | | | |
| R-M06 | MERAH | (Sakit kepala hebat atau pandangan kabur) **dan** UK ≥ 20/tidak diketahui **dan** TD tidak terbukti < 140/90 | **Perlu ditinjau:** sengaja MERAH tanpa TD, agar kader tanpa tensi tetap merujuk. Risiko: rujukan berlebih | | | |
| R-M06K | KUNING | Sama seperti R-M06 tetapi TD terukur < 140/90 | Pengecualian R-M06 (turun ke KUNING, alasan tertulis) | | | |
| R-M07 | MERAH | TD ≥ 140/90 **dan** (bengkak atau sakit kepala hebat) | **Perlu ditinjau** | | | |
| R-M08 | MERAH | Sesak napas berat / nyeri dada = YA | | | | |
| R-M09 | MERAH | Nyeri perut hebat = YA | | | | |
| R-M10 | MERAH | Air ketuban keluar sebelum waktunya = YA | **Perlu ditinjau:** belum membedakan cukup bulan / belum | | | |
| R-M11 | MERAH | Gerakan janin tidak ada **dan** UK ≥ 28 | **Perlu ditinjau:** ambang 28 minggu dan cara menilai gerakan janin | | | |
| R-M12 | MERAH | Demam **dan** salah satu (nyeri perut hebat, cairan berbau, tidak sadar, menggigil) | Tanda infeksi berat | | | |
| R-K01 | KUNING | TD ≥ 140/90 **dan** UK ≥ 20 (atau tidak diketahui) | Usia tidak diketahui diperlakukan seperti ≥ 20 (condong aman) | | | |
| R-K02 | KUNING | Bengkak wajah/tangan **dan** UK ≥ 20/tidak diketahui | Menambah saran "ukur TD hari ini" | | | |
| R-K03 | KUNING | Gerakan janin berkurang | | | | |
| R-K04 | KUNING | Demam = YA | | | | |
| R-K05 | KUNING | Muntah terus, tidak bisa makan/minum | | | | |
| R-K06 | KUNING | Perdarahan bercak **dan** UK < 20 | | | | |
| R-K07 | KUNING | TD ≥ 140/90 **dan** UK < 20 | **Ditambahkan tim teknis** (tidak ada di spesifikasi): TD tinggi tidak boleh berakhir HIJAU | | | |
| R-U01 | KUNING | Ada pertanyaan kritis BELUM DIPERIKSA dan tidak ada aturan MERAH | Invarian: data kurang tidak pernah HIJAU, tidak pernah MERAH | | | |
| R-U02 | KUNING | Ada tanda dijawab YA yang tidak tercakup aturan lain | **Ditambahkan tim teknis:** contoh sakit kepala hebat pada 12 minggu, bengkak pada 14 minggu, gerakan janin tidak ada pada 24 minggu. Tanpa aturan ini hasilnya HIJAU (menenangkan secara keliru) | | | |

## Pertanyaan terbuka untuk peninjau klinis

1. Apakah level KUNING berarti "diperiksa bidan paling lambat 24 jam"? Angka 24 jam masih asumsi.
2. Suhu terukur (°C) belum dipakai aturan mana pun. Perlukah ambang suhu (mis. ≥ 38 °C = demam)?
3. Pertanyaan gerakan janin baru ditanyakan mulai 20 minggu. Apakah batas ini tepat?
4. Perdarahan dengan jumlah "tidak tahu" pada < 20 minggu → KUNING (R-U02). Apakah seharusnya MERAH?
5. Apakah R-M06 (MERAH tanpa TD) menyebabkan terlalu banyak rujukan di lapangan? Perlu data pilot.
6. Panduan stabilisasi (`stabilization` di JSON, status `needs_review`): apakah langkah-langkahnya aman dan cukup untuk kader?
7. Mode NIFAS (pasca persalinan) sekarang ada sebagai draft terpisah; lihat bagian NIFAS di bawah.

## Aturan NIFAS (ibu setelah melahirkan, hari ke-0 sampai ke-42)

**Disusun tim teknis, bukan tenaga kesehatan.** Sumber umum: tanda bahaya ibu nifas di Buku KIA
Kemenkes dan WHO postnatal danger signs. Kebijakan level sama dengan kehamilan (level tertinggi,
data kurang → KUNING, jaring pengaman N-U02).

Perbedaan dengan kehamilan:
- Tidak ada usia kehamilan dan gerakan janin; yang dicatat adalah **hari setelah melahirkan**.
- Pertanyaan perdarahan menanyakan perdarahan **banyak** ("pembalut cepat penuh atau gumpalan besar"),
  karena darah nifas biasa itu normal. Tidak ada pertanyaan jumlah (bercak/sedang/banyak).
- Pertanyaan kritis: KEJ, KSD, PDR, SKH, PDG, SSK, NPT, **DMM (demam)**.
- Pertanyaan baru: PYD (payudara bengkak, merah, nyeri), BTS (nyeri/bengkak satu betis),
  SDH (sangat sedih/murung), PMD (berpikir menyakiti diri atau bayi).

| ID | Level | Kondisi (ringkas) | Alasan / catatan untuk peninjau | Disetujui oleh | Tanggal | Catatan peninjau |
|---|---|---|---|---|---|---|
| N-M01 | MERAH | Kejang = YA | | | | |
| N-M02 | MERAH | Pingsan/tidak sadar = YA | | | | |
| N-M03 | MERAH | Perdarahan banyak = YA | **Perlu ditinjau:** definisi "banyak" di teks petunjuk | | | |
| N-M04 | MERAH | TD ≥ 160/110 | Hipertensi berat pascapersalinan | | | |
| N-M05 | MERAH | Sakit kepala hebat **dan** (pandangan kabur atau nyeri ulu hati) | Preeklamsia pascapersalinan | | | |
| N-M06 | MERAH | (Sakit kepala hebat atau pandangan kabur) **dan** TD tidak terbukti < 140/90 | Sama seperti R-M06: MERAH tanpa tensi. Risiko rujukan berlebih | | | |
| N-M06K | KUNING | Sama seperti N-M06 tetapi TD terukur < 140/90 | Pengecualian N-M06 | | | |
| N-M07 | MERAH | TD ≥ 140/90 **dan** (bengkak atau sakit kepala hebat) | | | | |
| N-M08 | MERAH | Sesak napas berat / nyeri dada = YA | | | | |
| N-M09 | MERAH | Nyeri perut hebat = YA | | | | |
| N-M10 | MERAH | Demam **dan** salah satu (nyeri perut hebat, cairan berbau, tidak sadar, menggigil) | Tanda infeksi berat masa nifas | | | |
| N-M11 | MERAH | Berpikir menyakiti diri atau bayi = YA | **Perlu ditinjau** bidan + tenaga kesehatan jiwa: level dan cara bertanya | | | |
| N-K01 | KUNING | TD ≥ 140/90 | | | | |
| N-K02 | KUNING | Bengkak wajah/tangan | Saran "ukur TD hari ini" | | | |
| N-K03 | KUNING | Demam = YA | **Perlu ditinjau:** Buku KIA menyebut demam > 2 hari; di sini demam apa pun | | | |
| N-K04 | KUNING | Cairan berbau dari jalan lahir | | | | |
| N-K05 | KUNING | Payudara bengkak, merah, nyeri | | | | |
| N-K06 | KUNING | Nyeri/bengkak satu betis | **Perlu ditinjau:** apakah seharusnya MERAH | | | |
| N-K07 | KUNING | Sangat sedih atau murung | Saran: keluarga mendampingi | | | |
| N-U01 | KUNING | Ada pertanyaan kritis belum diperiksa, tidak ada aturan MERAH | Invarian | | | |
| N-U02 | KUNING | Ada tanda YA yang tidak tercakup aturan lain (mis. menggigil saja) | Jaring pengaman tim teknis | | | |

Panduan stabilisasi nifas (`stabilization` di `nifas.json`, status `needs_review`): menambah langkah
"baringkan miring jika tidak sadar", "dampingi dan jauhkan benda berbahaya jika berpikir menyakiti diri",
dan "bawa bayi bersama ibu". Tidak ada obat, dosis, atau tindakan seperti pijat rahim.

### Pertanyaan terbuka untuk peninjau (NIFAS)

1. Apakah semua perdarahan yang ditanyakan sebagai "banyak" langsung MERAH (N-M03)?
2. Demam: apakah KUNING sejak hari pertama, atau baru bila lebih dari 2 hari (N-K03)? Perlu ambang suhu?
3. Nyeri/bengkak satu betis (kemungkinan trombosis): KUNING atau MERAH (N-K06)?
4. Pertanyaan PMD (pikiran menyakiti diri atau bayi): apakah pantas ditanyakan kader, bagaimana kalimatnya,
   dan apakah perlu menjadi pertanyaan kritis? Apa yang harus dilakukan kader setelah jawaban YA?
5. Apakah perlu pertanyaan tambahan: nyeri/panas saat buang air kecil, luka jahitan bernanah, tidak bisa BAK?
6. Batas 42 hari: apakah aplikasi harus menolak (sekarang) atau mengizinkan di atas 42 hari?
7. Apakah tanda bahaya **bayi baru lahir** perlu checklist terpisah? (Belum ada di aplikasi.)

## Tanda tangan

| Nama | Profesi / STR | Fasilitas | Tanggal | Fase (hamil/nifas) dan versi aturan yang ditinjau |
|---|---|---|---|---|
| | | | | |
