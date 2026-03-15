# Tugas Besar 1 IF2211 Strategi Algoritma
## Battlecode 2025 - Kelompok 17 Bo-bot

## Deskripsi Singkat
Repository ini berisi implementasi bot Battlecode 2025 berbasis **algoritma greedy** untuk menyelesaikan tugas besar IF2211 Strategi Algoritma.

Secara umum, seluruh bot yang dibuat menggunakan prinsip yang sama, yaitu:
1. membangkitkan himpunan aksi yang mungkin dilakukan
2. menyaring aksi yang valid atau feasible
3. memberi nilai heuristic pada setiap kandidat aksi
4. memilih aksi dengan nilai tertinggi pada kondisi saat itu

Dengan pendekatan ini, bot selalu berusaha mengambil **keputusan lokal terbaik** pada setiap giliran, sehingga tetap sesuai dengan paradigma **algoritma greedy**.

---

## Penjelasan Singkat Algoritma Greedy untuk Setiap Bot

### 1. Sentinel
Sentinel adalah bot berbasis greedy yang berfokus pada **combat, movement, dan ekspansi area**.

Pada setiap giliran, Sentinel mengevaluasi aksi-aksi yang tersedia seperti:
- bergerak ke salah satu arah yang legal
- menyerang target musuh yang tersedia
- mengecat tile yang menguntungkan
- membangun tower
- refill paint
- melakukan aksi support lain

Setiap aksi diberi skor berdasarkan beberapa faktor, seperti:
- prioritas target musuh
- HP target
- keuntungan posisi
- nilai area yang bisa dikontrol
- risiko stuck
- efisiensi penggunaan paint

Sentinel kemudian memilih aksi dengan skor tertinggi. Dengan demikian, Sentinel bersifat greedy karena selalu mengambil **aksi lokal paling menguntungkan** pada turn tersebut.

#### Intuisi strategi Sentinel
Bot ini dirancang untuk:
- bereaksi cepat terhadap musuh
- memilih target combat yang bernilai tinggi
- memperluas area sambil tetap menjaga tekanan
- menghindari pergerakan yang berulang atau tidak produktif

---

### 2. TerraFirma
TerraFirma adalah bot berbasis greedy yang menitikberatkan pada **ekspansi ruin, eksplorasi terarah, dan tekanan pada satu front utama**.

Pada setiap turn, TerraFirma mengevaluasi aksi-aksi seperti:
- bergerak menuju ruin
- menjelajah frontier
- mendekati sektor map yang lama tidak dikunjungi
- menyerang musuh
- membangun tower
- upgrade tower
- spawn robot
- refill paint

Setiap aksi dinilai menggunakan heuristic yang mempertimbangkan:
- economic value
- peluang konversi ruin menjadi tower
- nilai area
- peluang memperbesar front pressure
- efisiensi posisi
- perkembangan kontrol map

TerraFirma lalu memilih aksi dengan skor terbesar, sehingga tetap mengikuti prinsip greedy.

#### Intuisi strategi TerraFirma
Bot ini dirancang untuk:
- mengamankan ruin secepat mungkin pada early game
- memperluas kontrol wilayah secara sistematis
- menjaga eksplorasi tetap aktif
- menekan lawan melalui satu front utama yang terfokus

#### Pembagian fase TerraFirma
- **Early game**: fokus pada capture tower dan pertumbuhan ekonomi
- **Mid game**: fokus pada ekspansi terarah, frontier, dan support
- **Late game**: fokus pada single-front pressure dan percepatan coverage map

---

### 3. Bot Alternatif Ketiga
#### Penjelasan greedy
...

#### Intuisi strategi
...

---

## Requirement Program
Program ini membutuhkan:
- **Java Development Kit (JDK) versi 21 atau lebih rendah**
- **Gradle** atau **Gradle Wrapper**
- **Battlecode 2025 engine / dependency** sesuai template proyek
- Sistem operasi:
  - Windows
  - Linux
  - macOS

### Instalasi
1. Install **JDK**
2. Pastikan `java` dan `javac` sudah dapat dijalankan dari terminal
3. Pastikan project menggunakan template Battlecode yang benar
4. Letakkan source code bot pada struktur folder yang sesuai

Contoh struktur folder:

```text
src/
├── main-bot/
├── alternative-bot-1/
└── alternative-bot-2/
```

## Cara Compile / Build Program

### Windows
```bash
./gradlew.bat build
./gradlew.bat run
```

### Linux / macOS
```bash
./gradlew build
./gradlew run
```

## Cara Menjalankan Program

### Windows
```bash
./gradlew.bat build
./gradlew.bat run
```

### Linux / macOS
```bash
./gradlew build
./gradlew run
```

### Langkah-Langkah Menjalankan Program
1. Buka terminal pada root folder repository.
2. Jalankan proses build dengan command yang sesuai.
3. Pastikan tidak ada error kompilasi.
4. Jalankan simulator atau match Battlecode.
5. Pilih bot yang ingin diuji.
6. Amati performa bot melalui viewer atau simulator.

## Author
Kelompok 17 - Bo-bot
1. Farrell Limjaya - 13524046
2. Hakam Avicena Mustain - 13524075
3. Valentino Daniel Kusumo - 13524104
