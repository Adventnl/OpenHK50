# OpenHK50

A local-first Android companion for the HK50 screenless band. OpenHK50 pairs directly over Bluetooth LE and keeps heart-rate and step readings on the phone. The app follows Android’s light and dark themes. Home-server backup is optional.

## Build and run

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

1. Open **Band**, scan, and select **HK50_3806**. Close other apps connected to the band if it doesn’t appear.
2. Open **Today** for battery status, the latest heart rate, and step totals from downloaded intervals. Use **Measure heart rate** for a spot check.
3. Open **History** and pick a date. The app imports today on connect and refreshes while it’s open. **Import recent history** requests up to seven days, subject to what the band retains. Pair in the time zone used by the band’s clock. **Set band clock** and **Heart-rate logging** are explicit band settings.

The HK50 battery round trip is confirmed by the original device check. Live heart rate, history, clock, and logging commands use the [H59 protocol reference](docs/reference/OpenH59.md) and still require confirmation on an HK50. Empty history stays empty. Phone-side BLE collection runs while the app is open. The HK50 may continue filling its own history between connections if logging is enabled and supported.

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

In **Backup → Set up backup**, enter the HTTPS origin and the key from `/etc/openh-k50/backup-key`. Keep that key in a password manager. Android schedules sync roughly hourly when network and battery policies permit. To restore after reinstalling, enter the same URL and key and let **Sync now** finish. The app merges backed-up readings into SQLite. Sync failures leave the phone’s data intact. Use SQLite’s `.backup` command or stop the service before taking a server database snapshot.

## Source layout

- `android/` Kotlin app, Android BLE, SQLite, UI, and scheduled backup.
- `protocol/` Rust BLE codec with a thin Android JNI binding. Run its checks with `cargo test --locked --manifest-path protocol/Cargo.toml`.
- `server/` Rust sync service, SQLite storage, and Ubuntu deployment files.
- `docs/reference/` H59 notes. The [original Python client and dashboard](https://github.com/Adventnl/OpenHK50/tree/95a879f70a023fe3e097e9a33010b9e4eddccec5) remain in Git history.

Phone data stays in app-private storage. Android Keystore protects the backup key. The app uses authenticated HTTPS with your server, with no account, analytics, or vendor cloud.

[MIT License](LICENSE)
