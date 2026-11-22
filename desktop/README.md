# Mac backup setup

The dashboard pairs through macOS CoreBluetooth. macOS gives each band an opaque Bluetooth ID. The sync server uses the Android Bluetooth MAC. Pair the same physical band on both clients before mapping those IDs.

1. Deploy the sync service using [Back up to Ubuntu](../README.md#back-up-to-ubuntu). Obtain its backup key in a private local file with mode `600`.
2. On the Mac dashboard, open **Band controls → Copy Bluetooth ID**. Read the physical band’s MAC from an Android BLE scanner. Check both clients are using the same band.
3. For LinkTunnel access, open a forward on the Mac and leave it running. Use an unused local port:

   ```sh
   nexus forward <ubuntu-device> 18745:127.0.0.1:8745
   ```

4. Restart the desktop service with the backup settings. Set `OPENHK50_BACKUP_URL` to a trusted HTTPS origin if you already have a reverse proxy. Plain HTTP uses a numeric loopback address for a local forward.

   ```sh
   export OPENHK50_BACKUP_URL=http://127.0.0.1:18745
   export OPENHK50_BACKUP_KEY_FILE=/path/to/private/backup-key
   export OPENHK50_BACKUP_DESKTOP_ID='your-macos-bluetooth-id'
   export OPENHK50_BACKUP_BAND_MAC='your-android-band-mac'
   ./desktop/run
   ```

5. Open **Settings → Server backup → Back up heart rate & steps**. The exchange uploads and restores heart rate and steps for the mapped band. Workouts and other metrics stay in the Mac database.

## Development

```sh
cargo test --locked --manifest-path desktop/Cargo.toml
cargo clippy --locked --manifest-path desktop/Cargo.toml --all-targets -- -D warnings
```

Use `OPENHK50_DESKTOP_PORT` to select a different loopback port. To test with a separate database, set `OPENHK50_DESKTOP_DATA_DIR` to a private directory. Browser assets are plain HTML, CSS, and ES modules embedded in the desktop binary. Workout calorie factors come from the [Adult Compendium of Physical Activities](https://pacompendium.com/conditioning-exercise/), activities 02060, 02054, and 02048.

On HK50 firmware `HK50_1.00.09_260827`, Test vibration sends one QWatch-style message and checks the firmware reply. The owner confirmed a single buzz on this firmware. See the [HK50 Bluetooth reference](../docs/reference/HK50.md#vibration-and-phone-notifications) for the packet trace. Bands that expose the [Bluetooth Immediate Alert Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/IAS_v1.0/out/en/index-en.html) use that service.
