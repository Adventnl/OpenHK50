mod store;

use openh_k50_protocol::backup as api;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Request, State},
    http::{StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::post,
};
use std::{
    env, fs,
    net::SocketAddr,
    os::unix::fs::OpenOptionsExt,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use store::{Exchange, Store};
use subtle::ConstantTimeEq;
use tokio::{net::TcpListener, signal::unix, sync::Semaphore};

struct App {
    key: String,
    store: Mutex<Store>,
    capacity: Arc<Semaphore>,
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let key_file = env::var_os("OPENHK50_KEY_FILE")
        .map(PathBuf::from)
        .or_else(|| {
            env::var_os("CREDENTIALS_DIRECTORY")
                .map(|directory| PathBuf::from(directory).join("backup-key"))
        })
        .ok_or("Set OPENHK50_KEY_FILE to the backup key file")?;
    let key = fs::read_to_string(key_file)?.trim().to_owned();
    if !api::is_hex(&key, 64) {
        return Err("The key file must contain 64 lowercase hex characters".into());
    }
    let bind: SocketAddr = env::var("OPENHK50_BIND")
        .unwrap_or_else(|_| "127.0.0.1:8745".to_owned())
        .parse()?;
    if !bind.ip().is_loopback() {
        return Err("Bind to loopback and terminate HTTPS with a reverse proxy".into());
    }
    let path =
        env::var_os("OPENHK50_DB").map_or_else(|| PathBuf::from("health.sqlite3"), PathBuf::from);
    fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(&path)?;
    let app = Arc::new(App {
        key,
        store: Mutex::new(Store::open(&path)?),
        capacity: Arc::new(Semaphore::new(4)),
    });
    let router = Router::new()
        .route("/v1/exchange", post(exchange))
        .layer(DefaultBodyLimit::max(128 * 1024))
        .layer(middleware::from_fn_with_state(app.clone(), authorize))
        .with_state(app);

    let listener = TcpListener::bind(bind).await?;
    eprintln!("OpenHK50 sync listening on {bind}");
    let mut terminate = unix::signal(unix::SignalKind::terminate())?;
    axum::serve(listener, router)
        .with_graceful_shutdown(async move {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {},
                _ = terminate.recv() => {},
            }
        })
        .await?;
    Ok(())
}

async fn authorize(State(app): State<Arc<App>>, request: Request, next: Next) -> Response {
    let key = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.as_bytes().strip_prefix(b"Bearer "));
    if !key.is_some_and(|key| bool::from(key.ct_eq(app.key.as_bytes()))) {
        return (
            StatusCode::UNAUTHORIZED,
            [(header::WWW_AUTHENTICATE, "Bearer")],
        )
            .into_response();
    }
    let Ok(_permit) = app.capacity.clone().try_acquire_owned() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let mut response = next.run(request).await;
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    response
}

async fn exchange(State(app): State<Arc<App>>, Json(request): Json<api::Request>) -> Response {
    if !request.valid() {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let result = tokio::task::spawn_blocking(move || {
        app.store
            .lock()
            .map_err(|_| rusqlite::Error::InvalidQuery)?
            .exchange(request)
    })
    .await;
    match result {
        Ok(Ok(Exchange::Ready(response))) => Json(response).into_response(),
        Ok(Ok(Exchange::Reset(response))) => (StatusCode::CONFLICT, Json(response)).into_response(),
        _ => {
            eprintln!("Backup database transaction failed");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}
