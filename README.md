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

**Band alert → Test vibration** sends a short alert on supported firmware. **Settings** controls auto-sync, workouts, the vibration test, and extra health metrics. Supported bands can sync sleep and hourly oxygen history after you enter the linked QWatch username.

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
2. Open **Today** for steps, band calories, workout time, distance, yesterday’s steps, and last night’s sleep. Steps open in Day history. **Average sleep** opens the previous seven complete days. Tap **Goals** to set daily targets.
3. Open **Workouts**, tap **Start workout**, and choose an activity. Use **Pause**, **Resume**, or **End workout**. The session survives app restarts. Saved sessions show active duration and average heart rate when the band supplies readings during the workout. A configured personal build shows estimated active calories during and after Strength sessions.
4. To check your pulse, open **Health → Measure → Heart rate**. Keep your wrist still and watch for a live BPM reading. Tap **Stop measurement** to end it early. Automatic heart-rate logging defaults to **On demand** and can be enabled under **Band → Heart-rate logging**.
5. **Band → Sync now** fetches today and checks for yesterday’s activity. **Sync past 7 days** requests recent history. HK50 devices with the detail channel try sleep and oxygen sync directly. For H59 or firmware that requires sign-in, enter the linked QWatch username under Band settings. The app stores the username on the phone and sends it over BLE.
6. Open **Band → Phone vibration**. Grant notification access and switch on the apps you want to buzz. The app uses the package name for matching. **Incoming calls** uses phone access and repeats the buzz while a cellular call rings.
7. **Band → Tools** includes **Find band** and **Phone clock**. **Camera remote** takes a photo when you move your wrist with the band connected or tap **Take photo**. It offers a three-second timer and front or rear camera selection when available. Photos save to `Pictures/OpenHK50` on Android 10 or later and to app storage on Android 8–9. **Turn on band call alerts** relays incoming cellular calls while the band service is running.

The band reconnects and syncs while the app is open or band call alerts are running. Pair in the time zone used by the band clock.

### Personal Strength estimates

Set `openhk50.workout.weightKg` and `openhk50.workout.bodyFatPercent` in `android/local.properties`, then rebuild. Use a percentage such as `20` for 20% body fat. The file is ignored by Git. The personal values are embedded in the resulting APK.

Android estimates active calories from logged duration excluding pauses. It uses the [Adult Compendium’s 3.5 MET value for resistance training](https://pacompendium.com/conditioning-exercise/) and subtracts your estimated basal energy for the same time using the [Cunningham equation](https://pubmed.ncbi.nlm.nih.gov/7435418/). Saved Strength sessions use the current build’s profile. Today’s **Band calories** displays the band’s own estimate, whose calculation and accuracy are unverified.

The [HK50 Bluetooth reference](docs/reference/HK50.md) records verified features, gesture and vibration commands, and the GATT inventory. The [H59 protocol reference](docs/reference/OpenH59.md) covers the related firmware. Historical oxygen values are hourly maxima. For a manual nRF Connect check, subscribe to `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` and write the hex battery request `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03` to `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`. In a valid `03` response the next byte is the percentage.

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

In **Band → Backup → Set up backup**, enter the HTTPS origin and the key from `/etc/openh-k50/backup-key`. Android schedules heart-rate and step backups roughly hourly when network and battery policies permit. To restore those readings after reinstalling, enter the same URL and key and let **Sync now** finish. Other health readings and workouts are stored on the phone. Use SQLite’s `.backup` command or stop the service before taking a server database snapshot.

## Source layout

- `android/` Kotlin app, Nordic BLE, SQLite, UI, and scheduled backup.
- `desktop/` Rust macOS Bluetooth service, SQLite store, and browser dashboard. Android does not build or package it.
- `protocol/` Rust BLE codec with a thin Android JNI binding. Run its checks with `cargo test --locked --manifest-path protocol/Cargo.toml`.
- `server/` Rust sync service, SQLite storage, and Ubuntu deployment files.
- `docs/reference/` HK50 and H59 Bluetooth notes. The [original Python client and dashboard](https://github.com/Adventnl/OpenHK50/tree/95a879f70a023fe3e097e9a33010b9e4eddccec5) remain in Git history.

Phone data stays in app-private storage. Android Keystore protects the backup key. The app uses authenticated HTTPS with your server, with no account, analytics, or vendor cloud.

[MIT License](LICENSE)
