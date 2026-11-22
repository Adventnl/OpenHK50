# OpenHK50

OpenHK50 connects to HK50 and H59 bands from Android or a Mac. Each client stores its readings locally and can back up heart rate and steps to the Rust sync service.

## Run on a Mac

With Rust stable installed, run this from the repository root in Terminal:

```sh
./desktop/run
```

The command opens the dashboard at [127.0.0.1:8746](http://127.0.0.1:8746). On the first scan, allow **Terminal** in **System Settings → Privacy & Security → Bluetooth**. Disconnect QWatch Pro, OpenHK50 for Android, or any other app currently using the band. Then click **Scan for bands** and choose your device. Keep Terminal running to sync while the browser tab is closed. Press Ctrl+C in Terminal to stop.

**Measure** requests a spot reading. With **Auto-sync** enabled, the Mac reads band history when you connect and about every five minutes while connected. **Band controls** offers a seven-day import, clock setting, and band heart-rate logging. Use the date picker to see past days. Today rolls over at midnight in the Mac’s time zone. Keep the Mac and band clocks in the same time zone.

**Start workout** estimates calories from activity, weight, and elapsed time. Use **End workout** to save it. Sessions survive browser and service restarts, and history files them under their start date. **Band calories** comes directly from the band’s activity intervals. The workout estimate is separate.

**Settings** controls auto-sync, workouts, notification tests, and extra health metrics. Supported bands can sync sleep and hourly oxygen history after you enter the linked QWatch username. **Simulate Android notification** shows a test in the browser. **Send band alert** appears if the device exposes Bluetooth Immediate Alert. The Mac client has no WeChat notification integration.

Records live in `~/Library/Application Support/OpenHK50/health.sqlite3`. See [Mac backup setup](desktop/README.md) for the optional server connection.

## Build and run on Android

Install JDK 17, the Android SDK command-line tools, Rust stable, and the Android Rust targets. Set `JAVA_HOME` and `ANDROID_HOME` to your installations. From the repository root:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358"
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`; keep the Rust tools on `PATH`. The APK supports Android 8+ on 64-bit ARM and x86-64 devices. Open the repository root in VS Code and run the same commands in its terminal. To build a release APK, run `./gradlew :app:assembleRelease` and sign the output with your release key.

## Use the band

1. On the Android phone, open **Band → Scan for bands**, allow nearby device access, and select your band, for example **HK50_3806**. Disconnect it in QWatch Pro or nRF Connect before scanning if either app is using it.
2. Open **Today** for battery and the latest readings. Tap a metric to open **History**. Heart rate, blood oxygen, and stress offer **Measure** for the current day while the band is connected.
3. **Band → Sync now** fetches today. **Sync past 7 days** requests recent history. For firmware exposing sleep and blood oxygen history, **Set up sleep and oxygen** uses the QWatch username already linked to the band. The app sends the username to the band over BLE and stores it on the phone.

The band reconnects and syncs while the app is open. Pair in the time zone used by the band clock. **Set band clock** and **Heart-rate logging** are manual controls.

The original HK50 hardware check confirmed the battery command. Other commands use the [H59 protocol reference](docs/reference/OpenH59.md) and depend on the HK50 firmware. Historical oxygen values are hourly maxima. For a manual nRF Connect check, subscribe to `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` and write the hex battery request `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03` to `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`. In a valid `03` response the next byte is the percentage.

Run `./gradlew :app:testDebugUnitTest :app:lintDebug` in `android/` to check the Android app. Run the Rust codec checks from the repository root with `cargo test --locked --manifest-path protocol/Cargo.toml`.

## Back up to Ubuntu

Build on the server and install the binary and systemd unit. The service listens on `127.0.0.1:8745` and stores its SQLite database under `/var/lib/openh-k50`.

```sh
cargo build --release --locked --manifest-path server/Cargo.toml
sudo install -m 0755 server/target/release/openh-k50-sync /usr/local/bin/
sudo install -d -m 0700 /etc/openh-k50
sudo sh -c 'umask 077; set -C; openssl rand -hex 32 > /etc/openh-k50/backup-key'
sudo install -m 0644 server/deploy/openh-k50-sync.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now openh-k50-sync
```

Expose the service through a phone-reachable HTTPS reverse proxy. Use an Android-trusted certificate and keep the backend listener on loopback. The [Caddy example](server/deploy/Caddyfile.example) forwards the sync route. You can administer the Ubuntu host through Link Tunnel and inspect the backend from a paired computer with `nexus forward <ubuntu-device> 8745:127.0.0.1:8745`. The Android scheduler needs its own route to the HTTPS hostname, such as your home network or an existing private network.

In **Backup → Set up backup**, enter the HTTPS origin and the key from `/etc/openh-k50/backup-key`. Android schedules heart-rate and step backups roughly hourly when network and battery policies permit. To restore those readings after reinstalling, enter the same URL and key and let **Sync now** finish. Sleep, blood oxygen, stress, and heart-rate variability are stored on the phone. Use SQLite’s `.backup` command or stop the service before taking a server database snapshot.

## Source layout

- `android/` Kotlin app, Nordic BLE, SQLite, UI, and scheduled backup.
- `desktop/` Rust macOS Bluetooth service, SQLite store, and browser dashboard. Android does not build or package it.
- `protocol/` Rust BLE codec with a thin Android JNI binding. Run its checks with `cargo test --locked --manifest-path protocol/Cargo.toml`.
- `server/` Rust sync service, SQLite storage, and Ubuntu deployment files.
- `docs/reference/` H59 notes. The [original Python client and dashboard](https://github.com/Adventnl/OpenHK50/tree/95a879f70a023fe3e097e9a33010b9e4eddccec5) remain in Git history.

Phone data stays in app-private storage. Android Keystore protects the backup key. The app uses authenticated HTTPS with your server, with no account, analytics, or vendor cloud.

[MIT License](LICENSE)
