# ProjectorMirrorLab 1.1

Jeden kombajn testowy zamiast kolejnych jednorazowych APK.

## Co można zmieniać w aplikacji

- Rozdzielczość: `800x480`, `640x360`, `480x270`
- FPS: `30`, `24`, `15`, `10`
- Bitrate: `1200000`, `800000`, `500000`, `300000`
- Czekanie na handshake: `3500`, `7000`, `1000`, `0` ms
- Heartbeat:
  - `ACK_ZERO`
  - `IGNORE`
  - `ACK_ZERO_ONCE`
- Pakietowanie:
  - `RAW`
  - `LEN_LE_TOTAL`
  - `LEN_LE_PAYLOAD`
  - `LEN_BE_TOTAL`
  - `LEN_BE_PAYLOAD`
- CSD / SPS/PPS:
  - `AS_PACKET_MODE`
  - `RAW_ALWAYS`
  - `SKIP_CSD`
- Echo pierwszego niezerowego pakietu USB
- Opóźnienie wideo do handshake / timeout
- Wysyłanie SPS/PPS przed klatkami

## Budowanie

Wrzuć zawartość projektu do root repozytorium GitHub.

`Actions → Build debug APK → Run workflow`

Artefakt:

`ProjectorMirrorLab-v11-debug-apk`

## Log

```bash
adb pull /sdcard/Android/data/pl.test1.projectormirrorlab/files/projector_mirror_lab_log.txt
```

## Sensowna pierwsza sekwencja testów

Zostaw większość domyślnie i zmieniaj tylko jedną rzecz naraz.

1. `ACK_ZERO`, `LEN_LE_TOTAL`, echo pierwszego pakietu ON, wait `3500`, `800x480`, `30 fps`.
2. Jeśli EIO szybko: `LEN_LE_PAYLOAD`.
3. Jeśli EIO szybko: `RAW`.
4. Jeśli trwa dłużej, zmniejsz bitrate i fps.
5. Jeśli brak handshake: wait `7000`.


## Zmiany w 1.1

Wersja 1.0 otwierała USB dopiero po zgodzie MediaProjection, przez co często nie było już heartbeatu/handshake’u.

Wersja 1.1 działa w dwóch krokach:
1. `Przygotuj USB` / `START` uruchamia foreground service i otwiera USB od razu.
2. Dopiero potem pojawia się zgoda na przechwytywanie ekranu.
3. Po zgodzie ekranowej ta sama usługa dostaje MediaProjection i zaczyna wysyłanie wideo bez ponownego otwierania USB.

To powinno lepiej naśladować oryginalną aplikację, która najpierw obsługuje accessory, a dopiero potem tworzy VirtualDisplay.
