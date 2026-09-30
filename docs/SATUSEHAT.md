# Integrasi SATUSEHAT (FHIR)

Server dinkes dapat mengirim kasus rujukan ke platform SATUSEHAT Kementerian Kesehatan. Integrasi ini
**opsional**, **mati secara default**, dan dijalankan per kasus oleh petugas dari dasbor.

## Status jujur

| Bagian | Status |
|---|---|
| Pembentukan data FHIR (Bundle transaksi) | Selesai, diuji unit |
| Klien OAuth2 + kirim Bundle + cari pasien lewat NIK | Selesai, diuji dengan **SATUSEHAT tiruan** |
| Uji ke sandbox SATUSEHAT sungguhan | **Belum**: perlu kredensial Anda |
| Pemetaan kode (LOINC/SNOMED) | **Belum diverifikasi** (`terminology.json`, semua `verified: false`) |
| Kesesuaian dengan Panduan Interoperabilitas (modul ANC/nifas) | **Belum dicek** oleh ahli terminologi |

Endpoint yang dipakai (dicek 30 Sep 2026 dari dokumentasi SATUSEHAT):
- Sandbox: `https://api-satusehat-stg.dto.kemkes.go.id/oauth2/v1` dan `.../fhir-r4/v1`
- Produksi: `https://api-satusehat.kemkes.go.id/oauth2/v1` dan `.../fhir-r4/v1`
- Token: `POST {auth}/accesstoken?grant_type=client_credentials` dengan `client_id`, `client_secret`
  (form-urlencoded). Satu set kredensial per Organization ID.

## Apa yang dikirim per kasus

Satu Bundle `transaction` berisi:

| Resource | Isi |
|---|---|
| Encounter | Pasien (ID IHS), Practitioner, Location puskesmas penerima, waktu SMS diterima, identifier `http://sys-ids.kemkes.go.id/encounter/{OrgID}` = `BUNDASIAGA-{caseKey}` |
| Observation × 2 | Tekanan darah sistolik (LOINC 8480-6) dan diastolik (8462-4), jika diukur |
| Observation | Usia kehamilan (minggu) **atau** hari setelah melahirkan (nifas) |
| Observation × n | Satu per tanda bahaya YA, kode lokal `https://bundasiaga.id/fhir/CodeSystem/tanda-bahaya` |
| ServiceRequest | Permintaan rujukan: prioritas `stat`/`urgent`/`routine` dari MERAH/KUNING/HIJAU, catatan bahwa ini **sinyal prioritas dari aturan draft, bukan diagnosis** |

Tidak dikirim: nama, NIK, nomor HP, diagnosis (tidak ada `Condition`). Tekan **Lihat FHIR** di
halaman `/satusehat` untuk melihat JSON persis sebelum dikirim.

## Yang perlu Anda siapkan

1. **Registrasi** organisasi (puskesmas/dinkes) di platform SATUSEHAT → Organization ID, Client ID,
   Client Secret untuk **sandbox**. Produksi diberikan setelah verifikasi.
2. **Location** (misalnya Poli KIA) tiap puskesmas di SATUSEHAT → ID Location.
3. **Practitioner**: ID IHS bidan/nakes penanggung jawab tiap puskesmas.
4. **File pemetaan faskes** (`BS_SS_MAP`). Kuncinya adalah **nama faskes persis** seperti yang diisi
   di Pengaturan HP gateway puskesmas (kolom "Nama"):
   ```json
   {
     "Puskesmas Contoh": {
       "locationId": "b017aa54-f1df-4ec2-9d84-8823815d7228",
       "locationName": "Poli KIA Puskesmas Contoh",
       "practitionerId": "N10000001",
       "practitionerName": "Bidan Contoh, A.Md.Keb"
     }
   }
   ```
5. **Tinjauan terminologi**: salin `server/src/main/resources/satusehat/terminology.json`, cocokkan
   dengan Panduan Interoperabilitas SATUSEHAT, tambahkan kode SNOMED untuk tanda bahaya di
   `dangerSignExtraCodings` jika ada padanannya, lalu arahkan `BS_SS_TERMINOLOGY` ke file itu.
   Keputusan terbuka: kelas Encounter (`AMB` atau `HH`), atau apakah pemeriksaan kader perlu dikirim
   sebagai Encounter sama sekali.
6. **Dasar hukum**: menautkan kode pasien (inisial + nomor) ke ID pasien SATUSEHAT berarti
   menghubungkan data pseudonim ke identitas asli. Perlu dasar pemrosesan (UU 27/2022), persetujuan
   ibu jika itu dasarnya, dan penilaian dampak (DPIA).

## Menjalankan

```bash
BS_TOKENS="<token-gateway>" BS_DASH_PASS="<sandi-dasbor>" \
BS_SS_ENV=sandbox \
BS_SS_CLIENT_ID="<client id>" BS_SS_CLIENT_SECRET="<client secret>" BS_SS_ORG_ID="<organization id>" \
BS_SS_MAP=/etc/bundasiaga/satusehat-faskes.json \
  ./gradlew :server:run
```

Log saat mulai: `SATUSEHAT: ON https://api-satusehat-stg..., 1 facility mapping(s)`. Tanpa semua
variabel itu: `SATUSEHAT: off` (halaman tetap bisa dipakai untuk melihat FHIR).

Opsional: `BS_SS_TERMINOLOGY` (file pemetaan kode sendiri), `BS_SS_AUTH_URL` / `BS_SS_FHIR_URL`
(mengganti alamat bawaan).

## Alur kerja petugas

1. Buka `https://<server>/satusehat` (login dasbor yang sama).
2. Per kasus: isi **ID pasien SATUSEHAT (IHS)**, atau **NIK** untuk dicari otomatis → Tautkan.
   NIK hanya dipakai untuk pencarian ke SATUSEHAT: tidak disimpan dan tidak dicatat di log.
3. Tekan **Kirim ke SATUSEHAT**. Hasil: "Terkirim (Encounter …)" atau "Gagal: <pesan dari SATUSEHAT>".
4. Kasus yang sudah terkirim tidak bisa dikirim lagi (mencegah Encounter ganda). Kasus yang gagal bisa
   diperbaiki (misalnya tautan pasien) lalu dikirim ulang.

Formulir dilindungi token CSRF dan pengecekan asal permintaan, di atas login dasbor.

## Uji di sandbox (disarankan urutannya)

1. Jalankan tanpa kredensial, buka **Lihat FHIR** untuk beberapa kasus, minta ahli terminologi memeriksa.
2. Isi kredensial sandbox. Pakai pasien dan nakes **dummy dari sandbox** (lihat Postman collection
   SATUSEHAT), bukan data asli.
3. Kirim 1 kasus hamil dan 1 kasus nifas. Cek hasilnya di SATUSEHAT (GET Encounter/Observation).
4. Catat penolakan (pesan OperationOutcome tampil di layar) dan perbaiki `terminology.json`.
5. Baru setelah itu ajukan kredensial produksi.

## Batas yang diketahui

- Encounter dikirim dengan status `arrived` dan tidak pernah ditutup (`finished`). Perlu diputuskan
  bersama pengelola SIMPUS.
- Belum ada pengiriman otomatis/berkala; setiap kasus dikirim manual.
- Belum ada pembatalan/koreksi resource yang sudah terkirim dari dasbor.
- Diuji dengan tiruan, bukan SATUSEHAT sungguhan. Asumsi bahwa SATUSEHAT menerima Bundle transaksi di
  alamat dasar FHIR harus dipastikan di sandbox; jika tidak, pengiriman perlu diubah menjadi per resource.
