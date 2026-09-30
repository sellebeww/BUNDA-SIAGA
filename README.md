# BUNDA SIAGA

<img src="docs/logo.png" width="160" align="right" alt="Logo BUNDA SIAGA">

Alat bantu offline untuk kader posyandu dan bidan desa: checklist tanda bahaya kehamilan dan
masa nifas →
**sinyal prioritas rujukan** HIJAU / KUNING / MERAH dengan alasan yang bisa dibaca → rujukan
lewat **SMS biasa** (tanpa internet) dengan antrian dan kirim ulang otomatis → HP puskesmas
menerima, membalas (ACK), memberi kabar kapasitas faskes lewat SMS, dan meneruskan rekap ke
server dinkes (dan opsional ke SATUSEHAT) jika ada internet. Aturan bisa diperbarui dinkes lewat file
bertanda tangan tanpa memasang ulang aplikasi.

**Mulai dari sini:** [docs/LANGKAH_ANDA.md](docs/LANGKAH_ANDA.md) (yang masih harus dikerjakan manusia).

> **Bukan alat diagnosis.** Semua aturan klinis masih DRAFT dan belum divalidasi bidan/dokter.
> Lihat [docs/CLINICAL_REVIEW.md](docs/CLINICAL_REVIEW.md) dan [docs/DEMO_HONESTY.md](docs/DEMO_HONESTY.md).

## Isi repositori

| Modul | Isi |
|---|---|
| `rules/` | Mesin aturan deterministik + `hamil.json`, `nifas.json` (semua ambang klinis ada di sini), paket aturan bertanda tangan, uji keamanan |
| `codec/` | Format SMS `BS1` (≤ 160 karakter GSM-7, CRC-8) + skrip baca manual |
| `outbox/` | State machine pengiriman, retry/backoff, pencocokan ACK, dedupe pesan masuk |
| `app/` | Aplikasi Android (peran KADER / BIDAN / PUSKESMAS), versi `pilot` dan `play` |
| `server/` | Server dinkes opsional: API, dasbor, cermin aturan, SATUSEHAT, alat tanda tangan aturan |

## Cepat mulai

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :rules:test :codec:test :outbox:test :server:test   # semua tes JVM
./gradlew :app:assemblePilotDebug                             # APK: app/build/outputs/apk/pilot/debug/
adb install -r app/build/outputs/apk/pilot/debug/app-pilot-debug.apk
./gradlew :app:bundlePlayRelease                              # .aab untuk Google Play (tanpa izin SMS)
```

Dokumen: [PLAN.md](PLAN.md) · [docs/LANGKAH_ANDA.md](docs/LANGKAH_ANDA.md) ·
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) (kenapa Android native, server) · [docs/RULES_UPDATE.md](docs/RULES_UPDATE.md) ·
[docs/PLAY_STORE.md](docs/PLAY_STORE.md) · [docs/SATUSEHAT.md](docs/SATUSEHAT.md) ·
[docs/CLINICAL_REVIEW.md](docs/CLINICAL_REVIEW.md) · [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) ·
[docs/MANUAL_TEST.md](docs/MANUAL_TEST.md)

## Format SMS

```
BS1;R;A7K2;YL07;M;34;168/112;SKH.BKK;DSA12;301545;3F     rujukan ibu hamil (34 = minggu)
BS1;N;Q3M8;SR04;M;03;150/96;PDR.PMD;DSB;301100;64        rujukan ibu nifas (03 = hari ke-3)
BS1;A;A7K2;S;301552;2A                                    balasan: S siap, P penuh, H hubungi
BS1;K;PKM1;T;2;301600;66                                  kapasitas: S siap, T terbatas, P penuh; 2 tempat tidur
```

Aplikasi lama yang belum mengenal `N`/`K` menampilkannya sebagai "format tidak dikenal", tidak membuangnya.
