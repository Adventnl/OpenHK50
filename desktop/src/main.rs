mod backup;
mod band;
mod model;
mod store;
mod web;

use anyhow::{Context, Result, ensure};
use backup::Backup;
use band::{Status, link::Radio};
use std::{
    env,
    fs::{self, DirBuilder},
    net::Ipv4Addr,
    os::unix::fs::{DirBuilderExt, PermissionsExt},
    path::PathBuf,
    process::Command,
    sync::{
        Arc, Mutex, MutexGuard,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use store::Store;
use tokio::{net::TcpListener, sync::Mutex as AsyncMutex};

const DEFAULT_PORT: u16 = 8746;

struct App {
    store: Mutex<Store>,
    status: Mutex<Status>,
    radio: Arc<AsyncMutex<Radio>>,
    revision: AtomicU64,
    backup: Option<Backup>,
    backup_status: Mutex<web::BackupStatus>,
}

impl App {
    fn db(&self) -> MutexGuard<'_, Store> {
        self.store.lock().expect("health database lock")
    }

    fn band(&self) -> MutexGuard<'_, Status> {
        self.status.lock().expect("band state lock")
    }

    fn changed(&self) {
        self.revision.fetch_add(1, Ordering::Relaxed);
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let mut args = env::args().skip(1);
    let browser = match args.next().as_deref() {
        None => false,
        Some("--open") if args.next().is_none() => true,
        _ => anyhow::bail!("Usage: openh-k50-desktop [--open]"),
    };
    let port = env::var("OPENHK50_DESKTOP_PORT")
        .ok()
        .map(|s| s.parse())
        .transpose()
        .context("Invalid OPENHK50_DESKTOP_PORT")?
        .unwrap_or(DEFAULT_PORT);
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port))
        .await
        .context("Open local dashboard port")?;
    let origin = format!("http://{}", listener.local_addr()?);
    let data = data_dir()?;
    let store = Store::open(&data.join("health.sqlite3"))?;
    let status = Status {
        device: store.device()?,
        ..Status::default()
    };
    let (backup, backup_status) = match Backup::from_env() {
        Ok(backup) => (backup, web::BackupStatus::default()),
        Err(error) => {
            eprintln!("Backup configuration: {error:#}");
            (
                None,
                web::BackupStatus {
                    message: Some(error.to_string()),
                    ..Default::default()
                },
            )
        }
    };
    let app = Arc::new(App {
        store: Mutex::new(store),
        status: Mutex::new(status),
        radio: Arc::new(AsyncMutex::new(Radio::new())),
        revision: AtomicU64::new(0),
        backup,
        backup_status: Mutex::new(backup_status),
    });
    let monitor = tokio::spawn(app.clone().monitor_band());
    println!("OpenHK50 is running at {origin}");
    if browser && let Err(error) = Command::new("open").arg(&origin).spawn() {
        eprintln!("Open {origin} in your browser ({error}).");
    }
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    let result = axum::serve(listener, web::router(app.clone(), origin))
        .with_graceful_shutdown(async move {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {},
                _ = terminate.recv() => {},
            }
        })
        .await;
    if let Some(stop) = &app.band().stop {
        stop.notify_one();
    }
    if let Ok(mut radio) = tokio::time::timeout(Duration::from_secs(4), app.radio.lock()).await {
        let _ = radio.disconnect().await;
    }
    monitor.abort();
    result?;
    Ok(())
}

fn data_dir() -> Result<PathBuf> {
    let data = match env::var_os("OPENHK50_DESKTOP_DATA_DIR") {
        Some(dir) => PathBuf::from(dir),
        None => PathBuf::from(env::var_os("HOME").context("Home directory unavailable")?)
            .join("Library/Application Support/OpenHK50"),
    };
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&data)
        .context("Create local health directory")?;
    let mode = fs::metadata(&data)?.permissions().mode() & 0o077;
    ensure!(
        mode == 0,
        "The health directory must be private (chmod 700): {}",
        data.display()
    );
    Ok(data)
}
