# Keputusan Platform dan Jalur Distribusi

## Kenapa Android native (bukan PWA, Flutter, USSD, atau bot WhatsApp)

| Opsi | Offline penuh | Kirim SMS otomatis + retry | Cocok untuk desa 3T | Keputusan |
|---|---|---|---|---|
| **Android native (Kotlin)** | Ya | Ya (dengan syarat distribusi, lihat bawah) | Ya | **Dipakai** untuk kader, bidan, gateway puskesmas |
| PWA / web | Sebagian | Tidak (hanya membuka aplikasi SMS) | Kurang: data bisa dihapus browser, tidak ada retry di background | Tidak |
| Flutter / React Native | Ya | Lewat plugin, lebih rapuh | Ya | Tidak ada keuntungan: pengguna hampir semuanya Android |
| USSD | Tidak | Tidak relevan | Buruk: butuh kerja sama operator dan sesi stabil | Tidak |
| Bot WhatsApp | Tidak | Tidak relevan | Buruk: butuh internet | Tidak |
| **Server + dasbor web** | Tidak perlu | Tidak relevan | Untuk dinkes/puskesmas yang punya internet | **Dipakai, opsional** |

Aplikasi tetap bekerja penuh tanpa server. Server hanya menambah rekap wilayah.

## Arsitektur yang dibangun

```
HP Kader/Bidan (Android)            HP Puskesmas = gateway (Android, mode PUSKESMAS)
  rule engine lokal                   terima SMS BS1 → kotak masuk lokal → ACK
  antrian SMS + retry  ── SMS ──►     tetap bekerja walau internet puskesmas mati
  skrip baca manual (radio/HT)  ◄── SMS ACK ──
                                            │ (saat ada internet, WorkManager)
                                            ▼
                                     Server dinas (Ktor + SQLite)
                                       POST /api/v1/cases (Bearer token per gateway)
                                       GET /  → dasbor (Basic auth)
```

Kader tanpa smartphone tetap bisa mengirim SMS ketik manual ke HP gateway, misalnya
`BS1;R;K7M2;SR03;M;30;-;PDR;DSB01;301420;-`. CK `-` artinya tanpa checksum. Pesan diterima
tetapi ditandai "tanpa kode pengecekan, pastikan lewat telepon".

## Kebijakan izin SMS Google Play (dicek 30 Sep 2026)

Google Play hanya mengizinkan `SEND_SMS`/`RECEIVE_SMS` untuk aplikasi SMS default, atau untuk
kategori pengecualian yang disetujui lewat formulir deklarasi. Dua kategori yang **mungkin** cocok:

- *Physical safety / emergency alerts*: "Apps that send SMS alerts in emergency situations"
- *Enterprise / CRM*: manajemen perangkat untuk karyawan, wajib login korporat

Persetujuan **tidak dijamin**, dan penerimaan SMS (`RECEIVE_SMS`) lebih sulit dibenarkan.
Sumber: Google Play Console Help, "Use of SMS or Call Log permission groups" (answer/10208820).

### Jalur yang disarankan

1. **Pilot (disarankan sekarang): distribusi oleh dinkes/NGO di luar Play Store.**
   - Pasang lewat MDM (Android Enterprise) atau `adb install` saat pelatihan kader.
   - Semua fitur jalan: kirim otomatis, retry, terima SMS, ACK otomatis.
   - **Risiko:** Android 13+ memblokir izin SMS ("restricted setting") untuk APK yang dipasang
     dari browser/file manager. Instal via `adb`, MDM, atau aplikasi installer berbasis
     session tidak terkena. Jika terkena: Pengaturan → Aplikasi → BUNDA SIAGA → ⋮ →
     "Izinkan setelan terbatas", lalu beri izin SMS.
2. **Skala besar lewat Play Store:** ajukan pengecualian "emergency alerts". Jika ditolak, build
   versi Play tanpa izin SMS. Aplikasi sudah punya jalur cadangan: tombol "Kirim lewat aplikasi
   SMS biasa" (membuka aplikasi SMS dengan teks terisi). Kekurangannya, status kirim tidak bisa
   dipantau, dan aplikasi mengatakannya dengan jujur di layar. ACK lalu dikonfirmasi lewat telepon.
   Gateway puskesmas tetap memakai build pilot (dikelola dinkes).

## Batas kirim ulang otomatis (jujur)

- Saat SMS dalam antrian, foreground service mencoba ulang dengan backoff 10 dtk → 20 → 40 → … maks 2 menit.
- WorkManager sebagai cadangan jika service dimatikan sistem. Setelah reboot, antrian dilanjutkan (`BOOT_COMPLETED`).
- **Doze** dan penghemat baterai OEM bisa menunda percobaan saat layar mati lama. Untuk HP pilot:
  matikan optimasi baterai untuk BUNDA SIAGA (Pengaturan → Baterai). Ini masuk materi pelatihan.
- Setelah 24 jam tanpa berhasil, status menjadi "percobaan otomatis berhenti". Tombol "Coba lagi" tetap ada.
- Setelah 10 menit tanpa ACK, layar meminta kader **menelepon** atau memakai skrip baca manual.

## Menjalankan server

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
BS_TOKENS="<token-acak-min-16-karakter>" BS_DASH_PASS="<sandi-dasbor>" \
  BS_HOST=0.0.0.0 BS_PORT=8080 BS_DB=/var/lib/bundasiaga/bunda.db ./gradlew :server:run
```

- Tanpa `BS_DASH_PASS`, server menolak berjalan di selain 127.0.0.1.
- Wajib di belakang HTTPS (reverse proxy, mis. Caddy/nginx). Build rilis Android memblokir HTTP polos.
- Token per gateway: buat satu token acak per puskesmas, isi di Pengaturan HP puskesmas.

### Variabel lingkungan server

| Variabel | Wajib | Isi |
|---|---|---|
| `BS_TOKENS` | Ya (untuk upload) | Token gateway dipisah koma, masing-masing ≥ 16 karakter |
| `BS_DASH_PASS`, `BS_DASH_USER` | Ya jika bukan 127.0.0.1 | Login dasbor (user bawaan `dinkes`) |
| `BS_HOST`, `BS_PORT`, `BS_DB` | Tidak | Bawaan `127.0.0.1`, `8080`, `data/bunda.db` |
| `BS_RULES_DIR` | Tidak | Folder berisi `hamil.bundle.json` / `nifas.bundle.json` (bawaan `data/rules`). Lihat [RULES_UPDATE.md](RULES_UPDATE.md) |
| `BS_RULES_TRUST` | Disarankan | File kunci publik; server menolak menyajikan bundle yang tidak valid |
| `BS_SS_ENV`, `BS_SS_CLIENT_ID`, `BS_SS_CLIENT_SECRET`, `BS_SS_ORG_ID`, `BS_SS_MAP` | Hanya untuk SATUSEHAT | Lihat [SATUSEHAT.md](SATUSEHAT.md) |

Alamat yang disediakan: `GET /` dasbor, `GET /satusehat` (login dasbor), `GET /privasi` (publik),
`GET /api/v1/rules[/hamil|/nifas]` (publik, file bertanda tangan), `POST /api/v1/cases` (token gateway),
`GET /health`.

## Dua versi APK

`pilot` (izin SMS, untuk distribusi dinkes, wajib untuk gateway puskesmas) dan `play` (tanpa izin SMS,
untuk Google Play). Perbandingan dan langkah Play Store: [PLAY_STORE.md](PLAY_STORE.md).

## Sebelum dipakai ke pasien sungguhan

1. Tinjauan klinis semua aturan (`CLINICAL_REVIEW.md`), ditandatangani.
2. Konsultasi Dinkes/Kemenkes: apakah perangkat lunak ini termasuk kategori alat kesehatan.
3. Kepatuhan UU 27/2022 tentang Pelindungan Data Pribadi (data kesehatan = data pribadi spesifik):
   dasar pemrosesan, persetujuan, batas masa simpan, penunjukan pengendali data.
4. Kontak fasilitas resmi dari dinkes diisi melalui menu Fasilitas kesehatan.
5. Uji lapangan terbatas bersama bidan koordinator.
