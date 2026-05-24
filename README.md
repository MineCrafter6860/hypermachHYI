# HypermachHYI

> Android ground station for **Hypermach Roket Takımı** — receives, validates, and displays live HYİ telemetry over USB OTG in real time.

---

## Overview

HypermachHYI is a native Android application built for TEKNOFEST rocketry competitions. It connects to a custom RF receiver board (Raspberry Pi Pico + E22-900T30D LoRa module) via a USB-to-TTL adapter and OTG cable, then decodes a strict 78-byte binary telemetry protocol and presents flight data on a mission-control-style dashboard.

The app is designed to be used at the launch site — it keeps the screen on, recovers from packet corruption automatically, and tracks link quality in real time so the ground team always knows the health of the telemetry stream.

---

## Features

- **USB OTG serial** — auto-detects CP210x, CH340, FTDI, and PL2303 adapters; requests permission and opens the port automatically on plug-in
- **ROKETSAN HYİ protocol** — full 78-byte packet validation: header (`FF FF 54 52`), modulo-256 checksum, and footer (`0D 0A`) before any data is accepted
- **Stream re-sync** — byte-by-byte accumulator that discards noise and re-locks on the next valid header after corruption
- **Live dashboard** — altitude, rocket angle, 3-axis accelerometer, 3-axis gyroscope, GPS coordinates, and GPS altitude
- **Link quality strip** — valid packet count, bad packet count, and live packets-per-second rate with a colour-coded LINK OK / DEGRADED / POOR indicator
- **Max altitude tracker** — session peak altitude displayed alongside the live reading
- **Tilt bar** — animated visual indicator on the angle card that turns amber and red as the rocket deviates from vertical
- **CSV export** — one-tap dump of the current telemetry snapshot and session stats to `Downloads/hyi_log_<timestamp>.csv`
- **Screen-on lock** — display stays awake for the duration of a session

---

## Protocol

| Field | Bytes | Format |
|---|---|---|
| Header | 0–3 | `FF FF 54 52` (fixed) |
| Team ID | 4 | `uint8` |
| Packet counter | 5 | `uint8`, wraps 0–255 |
| Altitude | 6–9 | `float32` LE, metres |
| *(reserved)* | 10–45 | GPS and other fields |
| Gyro X/Y/Z | 46–57 | `float32` LE each, dps |
| Accel X/Y/Z | 58–69 | `float32` LE each, g |
| Rocket angle | 70–73 | `float32` LE, degrees |
| *(reserved)* | 74 | — |
| Checksum | 75 | `(sum of bytes 4–74) % 256` |
| Footer | 76–77 | `0D 0A` (fixed) |

---

## Hardware Setup

```
[Pico RX board]
  Serial2 TX  ──►  USB-TTL adapter RX
  GND         ──►  USB-TTL adapter GND
  
[USB-TTL adapter] ──► OTG cable ──► Android phone
```

Serial parameters: **19200 baud · 8 data bits · 1 stop bit · no parity**

Tested adapters: CP2102, CH340G. Any adapter supported by [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) will work.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Serial driver | `com.github.mik3y:usb-serial-for-android` |
| Async | Kotlin Coroutines + StateFlow |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |

---

## Project Structure

```
app/src/main/java/com/kerem/hyi/
├── MainActivity.kt        # UI — Compose dashboard, session stats, CSV export
├── UsbSerialManager.kt    # USB device discovery, port lifecycle, read loop
├── TelemetryParser.kt     # 78-byte packet validation and float decoding
└── TelemetryData.kt       # Parsed telemetry data model
```

---

## Building

1. Clone the repo
2. Open in Android Studio Hedgehog or newer
3. Let Gradle sync — JitPack will resolve the serial driver dependency automatically
4. Connect your Android device, enable USB debugging, and run

No API keys or external services required.

---

## Competition Context

Built for **TEKNOFEST** by **Hypermach Roket Takımı** (Ö-UKB division). The receiver firmware runs on a Raspberry Pi Pico paired with an E22-900T30D LoRa module. The transmitter is the rocket's onboard flight computer, also Pico-based, reading from an MPU6050 (IMU) and BME280 (barometric altitude).

---

## License

MIT
