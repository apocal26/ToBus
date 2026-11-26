<div align="center">

<img src="app/src/main/res/drawable/tobus_icon_fg.png" alt="Logo ToBus" width="96" />

# ToBus — Torino Live

**Mappa in tempo reale dei mezzi GTT di Torino e widget Dark Liquid Glass per la schermata Home Android.**

[![Ultima Release](https://img.shields.io/github/v/release/apocal26/ToBus?label=Release&color=FBBF24&style=for-the-badge)](https://github.com/apocal26/ToBus/releases/latest)
[![Build APK](https://img.shields.io/github/actions/workflow/status/apocal26/ToBus/release-apk.yml?label=CI%20Build&style=for-the-badge)](https://github.com/apocal26/ToBus/actions/workflows/release-apk.yml)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024--36)-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/apocal26/ToBus/releases)
[![Kotlin & Compose](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-38BDF8?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)

<br />

<img src="docs/images/hero_banner.jpg" alt="ToBus Torino Live Banner" width="100%" />

<p>
  <a href="https://github.com/apocal26/ToBus/releases/latest"><strong>Scarica l'APK</strong></a>
  ·
  <a href="#funzionalit%C3%A0-principali">Funzionalità</a>
  ·
  <a href="#widget-per-la-schermata-home">Widget Home</a>
  ·
  <a href="#fonti-dati-e-architettura">Architettura</a>
  ·
  <a href="#compilazione-e-rilascio">Compilazione</a>
</p>

</div>

---

## Panoramica

**ToBus** nasce per seguire autobus e tram **GTT a Torino** in modo immediato, fluido e leggero, senza pubblicità né tracciamento. Combina un motore multi-fonte in tempo reale con un'interfaccia **Dark Liquid Glass** pensata per la lettura rapida in movimento e tre widget nativi pronti sulla schermata Home.

<div align="center">
  <img src="docs/images/app_showcase.jpg" alt="Anteprima mappa live ToBus su smartphone" width="100%" />
  <br />
  <sub><b>Mappa vettoriale live a 90/120 fps</b> — Linee differenziate per direzione, vetture animate con orientamento, stato ritardo/anticipo e scheda dettagli con tutte le fonti verificate.</sub>
</div>

---

## Funzionalità Principali

| Area | Cosa offre ToBus |
| :--- | :--- |
| **Live Tracking Multi-Fonte** | Unisce **MaTO MQTT Live**, feed ufficiali **GTT GTFS-RT**, **BusOne Proxy** e **OTP GraphQL** con deduplicazione intelligente per numero vettura e verifica di coerenza sul tracciato. |
| **Percorsi e Capolinea Reali** | Scarica e indicizza in locale il dataset GTFS di Torino risolvendo i **service ID del giorno** (feriale, festivo, deviazioni) nel fuso `Europe/Rome` per mostrare sempre capolinea e fermate esatti. |
| **Ricerca Linee e Fermate** | Selettore rapido diviso tra **Linee attive** (con stella preferiti) e **Fermate** (ricerca per nome, codice palina o vicinanza GPS) con passaggi live ordinati per direzione. |
| **4 Stili Mappa** | **Dark Obsidian**, **Positron Chiaro**, **Voyager OSM** e **Satellite**, con pannello superiore adattivo per mantenere sempre il massimo contrasto. |
| **Avvisi Servizio GTT** | Badge dedicato agli avvisi correnti e imminenti filtrati automaticamente per la linea selezionata, scartando comunicati storici scaduti. |
| **Aggiornamenti In-App** | Controllo discreto sulle **GitHub Releases**: scarica l'APK verificato in background con barra di avanzamento, apre il prompt Android di conferma e ripulisce il file dopo l'installazione. |

---

## Widget per la Schermata Home

Tutti i widget utilizzano il renderer bitmap nativo di ToBus con cache locale atomica dei tasselli e consumo minimo di batteria e memoria.

<div align="center">
  <img src="docs/images/widgets_showcase.jpg" alt="Widget Home ToBus 2x2, 4x2 e Fermate Live" width="100%" />
</div>

- **Mappa Compatta (2×2)** — Vista quadrata essenziale centrata sul tracciato e sui mezzi attivi della tua linea preferita.
- **Mappa Ampia (4×2 ridimensionabile)** — Panoramica completa della linea con etichette vetture, frecce direzionali e pulsante di aggiornamento rapido.
- **Fermate Live** — Tabellone passaggi in tempo reale alle tue fermate preferite con chip minuti live (`● 3 min`), orari programmati e selettore rapido tra le fermate salvate.

---

## Fonti Dati e Architettura

ToBus opera interamente sul dispositivo tramite chiamate **HTTPS** dirette e parser binari scritti ad hoc (zero dipendenze pesanti per Protobuf o SDK superflui):

```text
┌───────────────────────────┐   ┌────────────────────────────┐
│  MaTO MQTT Live (WebSock) │   │ GTT GTFS-RT & BusOne Proxy │
└─────────────┬─────────────┘   └─────────────┬──────────────┘
              │                               │
              └───────────────┬───────────────┘
                              ▼
             ┌──────────────────────────────────┐
             │  Fusione & Deduplicazione Veicoli│
             │ • Normalizzazione matricole      │
             │ • Filtro coerenza su tracciato   │
             │ • Finestra precedenza live MaTO  │
             └────────────────┬─────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ Mappa MapLibre  │  │ Tabellone Arrivi│  │  Widget Studio  │
│  (Compose UI)   │  │   & Avvisi GTT  │  │  (RemoteViews)  │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

### Stack Tecnico

- **Linguaggio & UI:** Kotlin, Jetpack Compose, Material Design 3, Edge-to-Edge adattivo (smartphone, pieghevoli e tablet)
- **Mappa & Grafica:** MapLibre Android OpenGL (`13.7.1`), `Canvas` GPU per marker e renderer bitmap dedicato ai widget
- **Rete & Parsing:** OkHttp 4, WebSocket MQTT per telemetria in tempo reale, decoder **GTFS-RT Protobuf** personalizzato e cache su disco atomica
- **Privacy & Permessi:** Zero account richiesti, zero analytics, posizione GPS opzionale usata solo su richiesta esplicita dell'utente

---

## Installazione Rapida

1. Scarica il file `ToBus-v*.apk` più recente dalla pagina [**GitHub Releases**](https://github.com/apocal26/ToBus/releases/latest).
2. Apri il file sul tuo dispositivo Android (è richiesto Android 7.0 Nougat o superiore).
3. Se richiesto dal sistema alla prima installazione, consenti l'installazione da questa origine.
4. Dal secondo aggiornamento in poi potrai scaricare e installare le nuove versioni direttamente dall'interno dell'app.

---

## Compilazione e Rilascio

### Requisiti locali

- **JDK 21**
- **Android SDK** (compileSdk `36`, minSdk `24`, targetSdk `36`)
- **Gradle 9.3.1**

### Comandi principali

```bash
# Esegui l'intera suite di test unitari offline
gradle :app:testDebugUnitTest

# Compila l'APK di debug
gradle :app:assembleDebug

# Compila la build release firmata e minificata con R8
KEYSTORE_PATH=/percorso/keystore.jks \
STORE_PASSWORD=*** \
KEY_PASSWORD=*** \
gradle :app:assembleRelease -PtobusVersionName=1.1.0 -PtobusVersionCode=10100000
```

### Rilascio automatico via GitHub Actions

Ogni tag `v*` (oppure l'avvio manuale del workflow **Build & Release APK**) compila l'APK release minificato con R8, verifica allineamento a 16 KB e firma crittografica `apksigner` e pubblica automaticamente l'asset `ToBus-<tag>.apk` nelle **GitHub Releases**.

---

<div align="center">
  <sub>Progettato per chi si muove ogni giorno a Torino · I dati di trasporto pubblico appartengono ai rispettivi operatori e portali open data GTT / Muoversi a Torino.</sub>
</div>
