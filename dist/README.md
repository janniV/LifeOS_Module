# LifeOS Scrobbler — v1.1

Ein datenschutzfreundlicher Scrobbler als LifeOSnext-Modul. Die laufende
Wiedergabe wird über die systemeigenen Medien-Schnittstellen abgefragt
(SMTC unter Windows, MPRIS unter Linux) und an dein eigenes
[Maloja](https://github.com/krateng/maloja) auf dem NAS geschickt.

## Installation

1. `scrobbler.jar` in den `modules`-Ordner deiner LifeOSnext-Installation kopieren.
   - Standardpfad: `<LifeOS-Root>/modules/scrobbler.jar`
2. LifeOSnext starten. In der Sidebar erscheint **Scrobbler** (Kopfhörer-Icon).
3. Im Modul auf den Tab **Einstellungen** wechseln und Maloja-URL + API-Key eintragen,
   dann **Einstellungen speichern**. Über **Verbindung testen** kannst du die Verbindung prüfen.

## Voraussetzungen

### Linux
Der Scrobbler nutzt das CLI-Tool `playerctl` (MPRIS-Wrapper). Auf nahezu jeder
Distribution paketiert:

```
sudo apt install playerctl     # Debian / Ubuntu / Mint
sudo dnf install playerctl     # Fedora
sudo pacman -S playerctl       # Arch / Manjaro
```

### Windows
Es ist keine Installation nötig. Der Scrobbler verwendet PowerShell mit der
WinRT-API `Windows.Media.Control` (verfügbar ab Windows 10).

## Datenschutz / Anonymität

- **Maloja-Verbindung** läuft direkt im LAN, niemals über einen Proxy. HTTP wird
  akzeptiert, weil Maloja so ausgeliefert wird.
- **MusicBrainz-Lookups** (zur Korrektur von Titel/Künstler) und **Cover-Art-Downloads**
  können über einen Proxy geführt werden:
  - **SOCKS5** (z. B. Tor unter `127.0.0.1:9050`) — verwendet die alte
    `HttpURLConnection`-API, da `java.net.http.HttpClient` SOCKS nicht unterstützt.
    Funktioniert für HTTPS, weil TLS auf dem proxied Socket aufgesetzt wird.
  - **HTTP-Proxy** (z. B. Privoxy) — verwendet den modernen `HttpClient` direkt.
- Der Maloja-API-Key wird ausschließlich lokal in `settings.json` gespeichert.
- Eine `Authorization: Token <key>`-Header wird zusätzlich zum Form-Field gesendet,
  damit sowohl alte als auch neue Maloja-Versionen unterstützt sind.

## Smarte Künstler-Erkennung

Wenn ein Browser/YouTube den Kanalnamen (z. B. „Daft Punk - Topic" oder
„DJ XYZ Channel") als Künstler meldet, wird die Titelzeile per Regex zerlegt
(`Künstler — Titel`, `[Künstler] Titel`, `Künstler: Titel`) und der echte Künstler
extrahiert. Nachsätze wie `(Official Video)`, `[HD]`, `(Lyrics)` werden entfernt.

Optional verifiziert MusicBrainz die Trefferquote (Score ≥ 90) und holt zugleich
das Front-Cover aus dem Cover-Art-Archive.

## Scrobble-Regeln

Standardmäßig wird ein Track erst dann an Maloja gemeldet, wenn:

- mindestens 50 % seiner Länge oder
- 240 absolute Sekunden gespielt wurden

und der Track mindestens 30 Sekunden lang ist (Last.fm-Konvention).

Pause/Resume und Vor-/Zurückspulen verfälschen den Akkumulator nicht — gezählt
wird nur tatsächlich vergangene Zeit, gedeckelt auf den Polling-Takt.

## Skip-Erkennung

Wenn ein Track gewechselt wird, **bevor** er die Skip-Schwelle (Standard 5 s)
überschritten hat, wird er als `SKIPPED` mit Detail markiert — du siehst direkt,
welche Songs durchgesprungen wurden.

## Persistente Retry-Queue

Misslungene Scrobbles (NAS aus, Timeout, 503) landen in
`retry-queue.json` im Modul-Datenordner — überlebt Neustarts. Nach jedem
Fehlversuch verdoppelt sich die Wartezeit (Exponential Backoff bis 5 Minuten).

## AI-Tools

Wenn dein Modul mit einer LifeOS-AI verbunden ist, werden folgende Tools registriert:

- `scrobbler_status` — kurze Statusbeschreibung
- `scrobbler_toggle` — `on`/`off`
- `scrobbler_history N` — letzte N Scrobbles
- `scrobbler_top_artists N` — Top-Künstler der Sitzung
- `scrobbler_retry_queue` — Anzahl ausstehender Retries

## Manuelle Scrobbles

Im Tab **Manuell** kannst du Künstler/Titel/Album/Länge eintragen und sofort
an Maloja senden (z. B. für Plays von einem Offline-Player).

## Dashboard-Widget

Liefert auf der LifeOS-Startseite eine kompakte Karte mit dem aktuellen Track
(inkl. Cover) und dem zuletzt erfolgreichen Scrobble.

## Fehlersuche

- **Status-Zeile am unteren Rand** zeigt aktuelle Engine-Meldungen.
- **Verlauf-Tab** listet jeden Versuch (`OK`, `RETRY`, `FAILED`, `SKIPPED`) plus Detail.
- Vollständiges Log in `<Modul-Datenordner>/scrobbler.log` (rotiert bei 1 MB, 3 Dateien).

## Build aus dem Quellcode

```
cd scrobbler-module
./build.sh
```

Das Ergebnis liegt unter `target/scrobbler.jar` und `dist/lifeos-scrobbler-1.1.0.zip`.
Tests: `mvn test` — JUnit 5, Schwerpunkt auf `TitleParser` und `RetryStore`.
