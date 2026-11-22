use crate::{
    App, band,
    model::{self, Activity, Day, Feature, Features, Workout, WorkoutStart},
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, Query, Request, State},
    http::{Method, StatusCode, header},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
};
use chrono::NaiveDate;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, atomic::Ordering};

#[derive(Clone, Default, Serialize)]
pub struct BackupStatus {
    pub running: bool,
    pub message: Option<String>,
}

#[derive(Serialize)]
struct Status {
    today: NaiveDate,
    revision: u64,
    band: band::Status,
    features: Features,
    details_enabled: bool,
    activities: Vec<ActivityOption>,
    active_workout: Option<Workout>,
    backup: BackupStatus,
    backup_configured: bool,
    backup_available: bool,
}

#[derive(Serialize)]
struct ActivityOption {
    id: Activity,
    name: &'static str,
}

pub fn router(app: Arc<App>, origin: String) -> Router {
    Router::new()
        .route(
            "/",
            get(|| async { Html(include_str!("../web/index.html")) }),
        )
        .route(
            "/web/{file}",
            get(|Path(file): Path<String>| async move { asset(&file) }),
        )
        .route(
            "/web/components/{file}",
            get(|Path(file): Path<String>| async move { component(&file) }),
        )
        .route("/api/status", get(status))
        .route("/api/day", get(day))
        .route("/api/band", post(command))
        .route("/api/band/stop", post(stop))
        .route("/api/features", post(features))
        .route("/api/details", post(details))
        .route("/api/workout/start", post(start_workout))
        .route("/api/workout/stop", post(stop_workout))
        .route("/api/backup", post(backup))
        .layer(DefaultBodyLimit::max(4096))
        .layer(middleware::from_fn_with_state(origin, same_origin))
        .with_state(app)
}

async fn same_origin(State(origin): State<String>, request: Request, next: Next) -> Response {
    let headers = request.headers();
    let expected_host = origin.trim_start_matches("http://").as_bytes();
    let forbidden = headers.get(header::HOST).map(|host| host.as_bytes()) != Some(expected_host)
        || headers
            .get(header::ORIGIN)
            .is_some_and(|value| value != &origin)
        || headers
            .get("sec-fetch-site")
            .is_some_and(|value| value == "cross-site" || value == "same-site")
        || (request.method() == Method::POST
            && headers
                .get("x-openhk50-local")
                .is_none_or(|value| value != "1"));
    if forbidden {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    for (key, value) in [
        ("cache-control", "no-store"),
        ("x-content-type-options", "nosniff"),
        ("referrer-policy", "no-referrer"),
        (
            "content-security-policy",
            "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        ),
    ] {
        headers.insert(key, header::HeaderValue::from_static(value));
    }
    response
}

async fn status(State(app): State<Arc<App>>) -> Result<Json<Status>, ApiError> {
    let band = app.band().clone();
    let db = app.db();
    let details_enabled = band
        .device
        .as_ref()
        .map(|device| db.detail_user(device.id))
        .transpose()?
        .flatten()
        .is_some();
    let backup_available = band.device.as_ref().is_some_and(|device| {
        app.backup
            .as_ref()
            .is_some_and(|backup| backup.matches(device))
    });
    let backup = app.backup_status.lock().expect("backup state lock").clone();
    Ok(Json(Status {
        today: model::today(),
        revision: app.revision.load(Ordering::Relaxed),
        band,
        details_enabled,
        features: db.features()?,
        active_workout: db.active_workout()?,
        activities: Activity::ALL
            .into_iter()
            .map(|id| ActivityOption {
                id,
                name: id.label(),
            })
            .collect(),
        backup,
        backup_configured: app.backup.is_some(),
        backup_available,
    }))
}

#[derive(Deserialize)]
struct DayQuery {
    date: NaiveDate,
}

async fn day(
    State(app): State<Arc<App>>,
    Query(query): Query<DayQuery>,
) -> Result<Json<Day>, ApiError> {
    if query.date > model::today() {
        return Err(ApiError::bad("Choose today or an earlier date."));
    }
    let band = app.band().device.as_ref().map(|device| device.id);
    Ok(Json(app.db().day(band, query.date)?))
}

async fn command(
    State(app): State<Arc<App>>,
    Json(command): Json<band::Command>,
) -> Result<StatusCode, ApiError> {
    let radio = app
        .radio
        .clone()
        .try_lock_owned()
        .map_err(|_| ApiError::conflict("A band action is already running."))?;
    app.begin_band(radio, command)
        .map_err(|error| ApiError::conflict(error.to_string()))?;
    Ok(StatusCode::ACCEPTED)
}

async fn stop(State(app): State<Arc<App>>) -> Result<StatusCode, ApiError> {
    let state = app.band();
    let stop = state
        .stop
        .as_ref()
        .ok_or_else(|| ApiError::conflict("No live measurement is running."))?;
    stop.notify_one();
    Ok(StatusCode::ACCEPTED)
}

#[derive(Deserialize)]
struct FeatureSetting {
    feature: Feature,
    enabled: bool,
}

async fn features(
    State(app): State<Arc<App>>,
    Json(setting): Json<FeatureSetting>,
) -> Result<StatusCode, ApiError> {
    let db = app.db();
    if matches!(setting.feature, Feature::Workouts)
        && !setting.enabled
        && db.active_workout()?.is_some()
    {
        return Err(ApiError::conflict(
            "End your workout before turning this off.",
        ));
    }
    let mut features = db.features()?;
    features.set(setting.feature, setting.enabled);
    db.set("features", &features)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct DetailSetting {
    username: Option<String>,
}

async fn details(
    State(app): State<Arc<App>>,
    Json(setting): Json<DetailSetting>,
) -> Result<StatusCode, ApiError> {
    let state = app.band();
    let device = state
        .device
        .as_ref()
        .ok_or_else(|| ApiError::conflict("Connect a band first."))?;
    let username = setting
        .username
        .as_deref()
        .map(|input| input.trim().split('@').next().unwrap_or(""));
    if let Some(username) = username {
        if !state.supports_details {
            return Err(ApiError::conflict(
                "Connect a band that supports sleep and oxygen history.",
            ));
        }
        if !(1..=48).contains(&username.encode_utf16().count())
            || username
                .chars()
                .any(|c| c.is_whitespace() || c.is_control())
        {
            return Err(ApiError::bad(
                "Enter the QWatch username linked to the band.",
            ));
        }
    }
    app.db().set(&format!("detail.{}", device.id), &username)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn start_workout(
    State(app): State<Arc<App>>,
    Json(start): Json<WorkoutStart>,
) -> Result<StatusCode, ApiError> {
    if !(20.0..=400.0).contains(&start.kg) {
        return Err(ApiError::bad("Enter a weight from 20 to 400 kg."));
    }
    let db = app.db();
    if !db.features()?.workouts {
        return Err(ApiError::conflict("Enable workouts first."));
    }
    if !db.start_workout(start)? {
        return Err(ApiError::conflict("A workout is already running."));
    }
    app.changed();
    Ok(StatusCode::CREATED)
}

async fn stop_workout(State(app): State<Arc<App>>) -> Result<StatusCode, ApiError> {
    if !app.db().stop_workout()? {
        return Err(ApiError::conflict("No workout is running."));
    }
    app.changed();
    Ok(StatusCode::NO_CONTENT)
}

async fn backup(State(app): State<Arc<App>>) -> Result<StatusCode, ApiError> {
    {
        let backup = app
            .backup
            .as_ref()
            .ok_or_else(|| ApiError::conflict("Set up a backup server first."))?;
        if !app
            .band()
            .device
            .as_ref()
            .is_some_and(|device| backup.matches(device))
        {
            return Err(ApiError::conflict("Select the band linked to backup."));
        }
        let mut status = app.backup_status.lock().expect("backup state lock");
        if status.running {
            return Err(ApiError::conflict("Backup is already running."));
        }
        status.running = true;
        status.message = None;
    }
    tokio::spawn(async move {
        let result = app
            .backup
            .as_ref()
            .expect("configured backup")
            .sync(&app)
            .await;
        let mut status = app.backup_status.lock().expect("backup state lock");
        status.running = false;
        status.message = Some(match result {
            Ok(message) => message.to_owned(),
            Err(error) => {
                eprintln!("Backup exchange: {error:#}");
                error.to_string()
            }
        });
    });
    Ok(StatusCode::ACCEPTED)
}

struct ApiError(StatusCode, String);

impl ApiError {
    fn bad(message: impl Into<String>) -> Self {
        Self(StatusCode::BAD_REQUEST, message.into())
    }
    fn conflict(message: impl Into<String>) -> Self {
        Self(StatusCode::CONFLICT, message.into())
    }
}

impl From<anyhow::Error> for ApiError {
    fn from(error: anyhow::Error) -> Self {
        eprintln!("Dashboard request: {error:#}");
        Self(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Couldn’t access local records. Check the terminal and retry.".to_owned(),
        )
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({ "message": self.1 }))).into_response()
    }
}

fn js(source: &'static str) -> Response {
    (
        [(header::CONTENT_TYPE, "text/javascript; charset=utf-8")],
        source,
    )
        .into_response()
}

fn asset(file: &str) -> Response {
    match file {
        "style.css" => (
            [(header::CONTENT_TYPE, "text/css; charset=utf-8")],
            include_str!("../web/style.css"),
        )
            .into_response(),
        "app.js" => js(include_str!("../web/app.js")),
        "api.js" => js(include_str!("../web/api.js")),
        "format.js" => js(include_str!("../web/format.js")),
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

fn component(file: &str) -> Response {
    match file {
        "health.js" => js(include_str!("../web/components/health.js")),
        "band.js" => js(include_str!("../web/components/band.js")),
        "workout.js" => js(include_str!("../web/components/workout.js")),
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}
