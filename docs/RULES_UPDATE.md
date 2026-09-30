# Pembaruan Aturan Tanpa Rilis Ulang APK

Dinkes bisa mengubah aturan tanda bahaya (hamil atau nifas) dan mengirimnya ke semua HP lewat server
atau lewat file, tanpa memasang ulang aplikasi.

## Cara kerjanya dan kenapa aman

```
Laptop dinkes (offline)            Server dinkes (hanya cermin)        HP kader/bidan/puskesmas
  edit nifas.json                     /api/v1/rules/nifas                 cek tanda tangan
  rulesTool check  ──┐                                                    cek format + versi mesin
  rulesTool sign  ───┴─► nifas.bundle.json ──► (HTTPS, tiap 12 jam) ──►   uji keamanan otomatis
  (kunci privat)                                   atau file/Bluetooth    nomor urut harus lebih besar
                                                                          → baru dipakai
```

HP hanya memakai aturan baru jika **semua** syarat ini terpenuhi. Jika satu saja gagal, HP tetap
memakai aturan yang sekarang dan tidak pernah crash:

1. **Tanda tangan** cocok dengan kunci publik di `app/src/main/assets/rules_trust.txt`
   (ECDSA P-256). Server yang dibobol tidak bisa membuat aturan palsu karena tidak memegang kunci privat.
2. **Format** lolos pemeriksaan ketat: kode pertanyaan dikenal, tidak ada aturan yang mustahil terpicu,
   aturan khusus kehamilan tidak dipakai di nifas, dan versi mesin (`engine`) tidak lebih baru dari aplikasi.
3. **Uji keamanan otomatis** (`RuleSelfTest`) pada ribuan pemeriksaan acak: menambah tanda bahaya tidak
   pernah menurunkan level, data kurang tidak pernah MERAH/HIJAU, semua jawaban TIDAK memberi HIJAU,
   panduan stabilisasi tidak menyebut obat/dosis. Alat tanda tangan menjalankan uji yang sama dengan
   20.000 input dan **menolak menandatangani** aturan yang gagal.
4. **Nomor urut (`seq`) lebih besar** dari yang sedang dipakai dan dari bawaan APK (tidak bisa
   diturunkan ke versi lama).

File yang diterima disimpan apa adanya dan diperiksa ulang setiap aplikasi dibuka. Setiap pemeriksaan
aturan memakai versi yang sama dari awal sampai akhir (pembaruan di tengah pemeriksaan tidak mengacaukan
pertanyaan).

## Langkah pertama kali (sekali saja)

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home

# 1. Buat pasangan kunci. keyId: huruf kecil, angka, tanda '-', maks. 32.
./gradlew -q :server:rulesTool --args="keygen dinkes-2026 /Volumes/USB-AMAN/kunci-aturan"
```

Hasilnya:
- `dinkes-2026.private.pem`: **kunci privat**. Simpan offline (USB terenkripsi, brankas). Buat cadangan.
  Jangan di-commit (sudah ada di `.gitignore`), jangan di server, jangan dikirim lewat chat.
- `dinkes-2026.trust.txt`: satu baris kunci publik. Ini **bukan** rahasia.

```bash
# 2. Tempel baris kunci publik ke aplikasi, lalu build ulang dan sebarkan APK ini.
cat /Volumes/USB-AMAN/kunci-aturan/dinkes-2026.trust.txt >> app/src/main/assets/rules_trust.txt
./gradlew :app:assemblePilotRelease :app:bundlePlayRelease
```

Selama `rules_trust.txt` kosong, fitur ini **mati** dan layar "Aturan dan pembaruan" mengatakannya.

## Setiap kali mengubah aturan

```bash
# Ambil salinan aturan yang sedang dipakai aplikasi (sekali, lalu edit salinannya)
./gradlew -q :server:rulesTool --args="defaults /path/kerja/aturan"

# Edit /path/kerja/aturan/nifas.json:
#  - ubah aturan/teks
#  - naikkan "version" (misalnya nifas-0.2.0) DAN "seq" (misalnya 1 -> 2). seq wajib naik.

# Periksa (parsing + uji keamanan 20.000 input). Tidak menandatangani apa pun.
./gradlew -q :server:rulesTool --args="check /path/kerja/aturan/nifas.json"

# Tandatangani
./gradlew -q :server:rulesTool --args="sign /path/kerja/aturan/nifas.json dinkes-2026 /Volumes/USB-AMAN/kunci-aturan/dinkes-2026.private.pem /path/kerja/nifas.bundle.json"

# Periksa hasil persis seperti HP memeriksanya
./gradlew -q :server:rulesTool --args="verify /path/kerja/nifas.bundle.json /Volumes/USB-AMAN/kunci-aturan/dinkes-2026.trust.txt"
```

Jalankan perintah dari folder repositori. Jalur relatif dihitung dari folder repositori.

### Menerbitkan

**Lewat server:** salin `nifas.bundle.json` ke folder `BS_RULES_DIR` server (nama file harus
`hamil.bundle.json` atau `nifas.bundle.json`). Tidak perlu restart. HP memeriksa tiap 12 jam saat ada
internet, atau langsung lewat Menu → Aturan dan pembaruan → Periksa pembaruan sekarang.

Jika server dijalankan dengan `BS_RULES_TRUST=/path/dinkes-2026.trust.txt`, server ikut memeriksa
tanda tangan dan **tidak menyajikan** file yang tidak valid. Log saat server mulai menampilkan status
tiap file (`Rules: HAMIL: none; NIFAS: nifas-0.2.0`).

**Lewat file (daerah tanpa internet):** kirim `nifas.bundle.json` lewat kartu memori, Bluetooth,
atau aplikasi chat. Di HP: Menu → Aturan dan pembaruan → Pilih file aturan. Pemeriksaannya sama.

### Mengecek di HP

Menu → Aturan dan pembaruan menampilkan per fase: versi, nomor urut, status, sumber
("Bawaan aplikasi" atau "Pembaruan (server/file), kunci …, dipasang …"), dan jumlah aturan.
Hasil setiap pemeriksaan juga tercatat di Menu → Data, metrik, dan simulasi (`rules_check`, `rules_import`).

## Ganti atau cabut kunci

- **Ganti rutin:** buat kunci baru (`keygen dinkes-2027`), tambahkan barisnya ke `rules_trust.txt`
  (kunci lama tetap ada), sebarkan APK. Setelah semua HP memakai APK baru, hapus baris kunci lama.
- **Kunci privat bocor:** hapus barisnya dari `rules_trust.txt`, buat kunci baru, dan sebarkan APK baru
  secepatnya. HP dengan APK lama masih mempercayai kunci yang bocor sampai diperbarui.

## Batas yang perlu diketahui

- Aturan bawaan APK tetap berlaku sampai ada pembaruan yang lolos. APK baru dengan `seq` bawaan lebih
  tinggi otomatis mengalahkan pembaruan lama yang tersimpan.
- Pembaruan hanya mengubah **isi** aturan (JSON). Operator kondisi baru atau jenis pertanyaan baru
  butuh versi aplikasi baru (`engine` dinaikkan); HP lama akan menolaknya dengan aman.
- Tanda tangan membuktikan asal file, **bukan** kebenaran klinisnya. Tinjauan klinis tetap wajib
  sebelum menandatangani.
