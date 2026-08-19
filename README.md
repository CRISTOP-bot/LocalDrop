# LocalDrop

**Fast, private and direct file transfers between devices over the local network.**

LocalDrop transfers files directly between devices connected to the same local network. It does not require accounts, cloud storage, a relay server or an external backend.

> Current status: MVP hardened for local Android use. The project is intentionally limited to Android and IPv4 private networks.

## Features

- Automatic device discovery with Android NSD/mDNS (`_localdrop._tcp`).
- Manual discovery refresh that cancels the previous NSD listener before restarting it.
- Direct HTTP streaming in 64 KiB buffers; files are never loaded completely into RAM.
- Multiple selected files and Android Share Sheet support for `content://` URIs.
- Incoming requests are shown individually and can be accepted, rejected or allowed to expire.
- Storage Access Framework destination selection, default Downloads destination and duplicate-safe names.
- Room history containing file, size, date, device, direction, state, error and SHA-256.
- Optional SHA-256 integrity verification; mismatches become `CORRUPTED` and partial files are removed.
- Local QR connection with protocol version, device ID, private IPv4 host, port and name.
- QR validation rejects unknown versions, malformed ports, invalid names and public hosts.
- Foreground Service and transfer notification for long sender-side transfers.
- Settings for device name, port, default folder, automatic discovery, incoming confirmation and integrity checks.
- Material 3 UI with empty, progress, error, history and incoming-request states.

## Architecture

```text
presentation/  Compose screens + MainViewModel
      ↓
domain/        models, repository contract, use cases
      ↓
data/         Room, NSD/mDNS, local HTTP server, HTTP streaming client
      ↓
Android APIs   ContentResolver, SAF, MediaStore, Foreground Service, NotificationManager
```

The domain layer exposes interfaces. The data layer owns the network and persistence implementations. Lifecycle cleanup is explicit: the repository closes NSD, the ServerSocket, transfer jobs, pending request decisions, notifications and Room when the ViewModel is cleared.

## Requirements

- Android Studio current stable.
- JDK 17.
- Android SDK 35.
- Android 8.0 (API 26) or newer.
- Two devices on the same Wi-Fi/LAN. Client isolation on some guest networks can prevent NSD discovery.

## Build and test

Open `LocalDrop/` in Android Studio or run:

```bash
./gradlew clean
./gradlew test
./gradlew assembleDebug
./gradlew lint
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### GitHub Releases

Every push to `main` runs the complete build, tests and lint. If everything passes, GitHub Actions creates a prerelease automatically with a tag such as `v0.1.12` and attaches:

- `LocalDrop-debug.apk`
- `LocalDrop-debug.apk.sha256`

Pull requests only run verification and do not publish releases. You can also start the workflow manually from the **Actions** tab.

## Usage

1. Install LocalDrop on both devices and connect them to the same Wi-Fi.
2. Grant notification permission when Android requests it.
3. Keep LocalDrop open once on each device so NSD can advertise the local service.
4. Select files, choose a discovered device and press **Enviar**.
5. On the destination, review each request, choose a folder and press **Aceptar**.
6. For manual pairing, show the QR on one device and scan it from the other.

For Android Share Sheet, choose **Compartir → LocalDrop**. The app reads the supplied `content://` URI through `ContentResolver`; it does not assume a filesystem path.

## Security and network boundaries

The app binds a local `ServerSocket`, advertises it through NSD and connects only to private IPv4 addresses. Public Internet addresses, malformed QR data and unvalidated ports are rejected. Incoming transfers require visible user consent by default and show the origin device and file list before writing anything.

Every transfer gets a temporary UUID session. The code keeps discovered and paired devices distinct: NSD discovery alone does not mark a device as paired; QR pairing stores the explicit pairing state in Room.

The MVP uses cleartext HTTP because traffic is local and the protocol is implemented over a private LAN. This is not a substitute for authenticated encryption on hostile networks. The architecture leaves room for a future `Device → Public Key → Fingerprint → Pairing → Authenticated Session` flow and does not use homemade cryptography.

## Project structure

```text
app/src/main/java/com/cristopher/localdrop/
├── data/
│   ├── discovery/       NSD/mDNS discovery
│   ├── local/           Room database and DAOs
│   ├── network/         local HTTP server and active address selection
│   └── transfer/        HTTP streaming and Foreground Service
├── domain/
│   ├── model/           transfer/device/settings models
│   ├── repository/      data boundary interfaces
│   └── usecase/         application actions
├── presentation/        Compose UI and ViewModel
└── utils/               hashing, QR validation and file helpers
```

## Testing

Pure JVM tests cover duplicate naming, SHA-256, private-network validation, QR protocol parsing, invalid QR input, zero-byte progress and transfer state/error preservation. Network and Android framework integration should be tested on physical/emulated devices because NSD, SAF, MediaStore and notification permission require Android runtime APIs.

## Roadmap

- Authenticated public-key pairing and session authentication.
- Resumable chunked transfers and controlled retries.
- Persistent multi-transfer queue shared by the Foreground Service.
- Receiver-side background service and richer network-change recovery.
- Optional theme system (light/dark/system) and tablet-optimized layouts.
- Instrumented tests for NSD, HTTP streaming, cancellation, duplicates and hash corruption.

## Screenshots

No screenshots are included yet. They can be added under `docs/screenshots/` after testing on a real device.

## License

No license has been selected yet. Add a license file before distributing the project publicly.
