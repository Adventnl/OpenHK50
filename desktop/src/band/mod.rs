mod details;
mod frames;
pub mod link;
mod sync;

use crate::{
    App,
    model::{Device, Metric},
};
use anyhow::{Context, Result, ensure};
use chrono::{Datelike, Local, Timelike};
use frames::{HEART_LOG, LIVE, LIVE_STOP};
use link::{Channel, Link, Radio};
use openh_k50_protocol::{encode, valid};
use serde::{Deserialize, Serialize};
use std::{sync::Arc, time::Duration};
use tokio::{
    sync::{Notify, OwnedMutexGuard},
    time::Instant,
};
use uuid::Uuid;

#[derive(Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Operation {
    Scan,
    Connect,
    Disconnect,
    Sync,
    Measure,
    Logging,
    Clock,
    Alert,
}

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Command {
    Scan,
    Connect { id: Uuid },
    Disconnect,
    Sync { history: bool },
    Measure { metric: Metric },
    Logging { enabled: bool },
    Clock,
    Alert,
}

impl Command {
    pub fn operation(&self) -> Operation {
        match self {
            Self::Scan => Operation::Scan,
            Self::Connect { .. } => Operation::Connect,
            Self::Disconnect => Operation::Disconnect,
            Self::Sync { .. } => Operation::Sync,
            Self::Measure { .. } => Operation::Measure,
            Self::Logging { .. } => Operation::Logging,
            Self::Clock => Operation::Clock,
            Self::Alert => Operation::Alert,
        }
    }
}

#[derive(Clone, Serialize)]
pub struct Live {
    pub metric: Metric,
    pub value: Option<u16>,
}

#[derive(Default, Clone, Serialize)]
pub struct Status {
    pub device: Option<Device>,
    pub nearby: Vec<Device>,
    pub connected: bool,
    pub battery: Option<u8>,
    pub charging: bool,
    pub supports_details: bool,
    pub supports_alert: bool,
    pub operation: Option<Operation>,
    pub last_sync: Option<i64>,
    pub message: Option<String>,
    pub alert_message: Option<String>,
    pub live: Option<Live>,
    #[serde(skip)]
    pub stop: Option<Arc<Notify>>,
}

impl Status {
    fn disconnected(&mut self) {
        self.connected = false;
        self.battery = None;
        self.charging = false;
        self.supports_details = false;
        self.supports_alert = false;
        self.alert_message = None;
    }
}

impl App {
    pub fn begin_band(
        self: &Arc<Self>,
        radio: OwnedMutexGuard<Radio>,
        command: Command,
    ) -> Result<()> {
        let mut status = self.band();
        let operation = command.operation();
        match operation {
            Operation::Scan | Operation::Connect => {
                ensure!(!status.connected, "Disconnect the current band first.")
            }
            _ => ensure!(status.connected, "Connect a band first."),
        }
        let stop = if let Command::Measure { metric } = &command {
            ensure!(
                metric.live_type().is_some(),
                "This metric does not support live measurements."
            );
            ensure!(
                *metric == Metric::Heart || self.db().features()?.extra_metrics,
                "Enable extra health metrics first."
            );
            status.live = Some(Live {
                metric: *metric,
                value: None,
            });
            Some(Arc::new(Notify::new()))
        } else {
            if operation == Operation::Alert {
                ensure!(
                    self.db().features()?.notifications,
                    "Enable the vibration test in Settings."
                );
                ensure!(
                    status.supports_alert,
                    "This band does not support the vibration test."
                );
                status.alert_message = None;
            }
            None
        };
        if operation == Operation::Scan {
            status.nearby.clear();
        }
        status.stop = stop.clone();
        status.operation = Some(operation);
        if operation != Operation::Alert {
            status.message = None;
        }
        drop(status);
        let app = self.clone();
        tokio::spawn(async move {
            let mut radio = radio;
            let result = run(&app, &mut radio, command, stop).await;
            app.finish_band(&mut radio, result).await;
        });
        Ok(())
    }

    async fn finish_band(&self, radio: &mut Radio, result: Result<Option<String>>) {
        let disconnected = match radio.link.as_mut() {
            Some(link) => !link.is_connected().await,
            None => true,
        };
        if disconnected {
            let _ = radio.disconnect().await;
        }
        let mut status = self.band();
        if disconnected {
            status.disconnected();
        }
        let message = match result {
            Ok(message) => message,
            Err(error) => {
                eprintln!("Band operation: {error:#}");
                Some(error.to_string())
            }
        };
        if status.operation == Some(Operation::Alert) {
            status.alert_message = message;
        } else {
            status.message = message;
        }
        status.operation = None;
        status.stop = None;
        status.live = None;
    }

    pub async fn monitor_band(self: Arc<Self>) {
        let mut interval = tokio::time::interval(Duration::from_secs(10));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut was_connected = false;
        let mut last_attempt = Instant::now();
        let mut last_day = crate::model::today();
        loop {
            interval.tick().await;
            let Ok(mut radio) = self.radio.try_lock() else {
                continue;
            };
            let Some(link) = radio.link.as_mut() else {
                was_connected = false;
                continue;
            };
            if !link.is_connected().await {
                let _ = radio.disconnect().await;
                let mut status = self.band();
                status.disconnected();
                status.message =
                    Some("Band disconnected. Bring it closer and reconnect.".to_owned());
                was_connected = false;
                continue;
            }
            let today = crate::model::today();
            if !was_connected {
                was_connected = true;
                last_day = today;
                last_attempt = Instant::now();
            }
            if today == last_day && last_attempt.elapsed() < Duration::from_secs(300) {
                continue;
            }
            let enabled = self
                .db()
                .features()
                .is_ok_and(|features| features.auto_sync);
            if !enabled {
                continue;
            }
            let days = (today.signed_duration_since(last_day).num_days() + 1).clamp(1, 7) as u8;
            last_attempt = Instant::now();
            // Retry a rollover import if the previous date remains unsaved due to a transport failure.
            let device = self.band().device.clone();
            let Some(device) = device else {
                continue;
            };
            {
                let mut status = self.band();
                status.operation = Some(Operation::Sync);
                status.message = None;
            }
            let result = sync::history(&self, link, device.id, days).await;
            if result.is_ok() {
                last_day = today;
            }
            self.finish_band(&mut radio, result).await;
        }
    }
}

async fn run(
    app: &App,
    radio: &mut Radio,
    command: Command,
    stop: Option<Arc<Notify>>,
) -> Result<Option<String>> {
    let command = match command {
        Command::Scan => {
            let found = radio.scan().await?;
            let message = found.is_empty().then(|| {
                "No bands found. Wake the band and disconnect it from other apps.".to_owned()
            });
            app.band().nearby = found;
            return Ok(message);
        }
        Command::Connect { id } => {
            let saved = app.band().device.clone().filter(|device| device.id == id);
            let device = radio.connect(id, saved).await?;
            let saved = app.db().set("device", &device);
            if let Err(error) = saved {
                let _ = radio.disconnect().await;
                return Err(
                    error.context("Couldn’t save the band. Check local storage and reconnect.")
                );
            }
            {
                let link = radio.link.as_ref().context("Connection ended.")?;
                let mut status = app.band();
                status.connected = true;
                status.supports_details = link.has_details();
                status.supports_alert = link.has_alert();
                if status.device.as_ref().map(|device| device.id) != Some(id) {
                    status.last_sync = None;
                }
                status.device = Some(device);
                status.nearby.clear();
            }
            app.changed();
            let features = app.db().features()?;
            if features.auto_sync {
                Command::Sync { history: false }
            } else {
                let link = radio.link.as_mut().context("Connection ended.")?;
                sync::battery(app, link).await?;
                return Ok(None);
            }
        }
        Command::Disconnect => {
            radio.disconnect().await?;
            return Ok(None);
        }
        command => command,
    };
    let device = app.band().device.clone().context("Connect a band first.")?;
    let link = radio.link.as_mut().context("Connect a band first.")?;
    match command {
        Command::Sync { history } => {
            app.band().operation = Some(Operation::Sync);
            sync::history(app, link, device.id, if history { 7 } else { 1 }).await
        }
        Command::Measure { metric } => {
            measure(
                app,
                link,
                device.id,
                metric,
                stop.expect("measurement stop signal"),
            )
            .await
        }
        Command::Logging { enabled } => {
            let reply = link
                .request(
                    HEART_LOG,
                    &[2, if enabled { 1 } else { 2 }, 5],
                    Duration::from_secs(3),
                )
                .await?;
            ensure!(
                reply.answered(),
                "Band didn’t confirm the heart-rate logging change."
            );
            Ok(Some(
                if enabled {
                    "Heart-rate logging enabled."
                } else {
                    "Heart-rate logging paused."
                }
                .to_owned(),
            ))
        }
        Command::Clock => {
            let now = Local::now();
            ensure!(
                (2000..2100).contains(&now.year()),
                "Mac date is outside the band’s supported range."
            );
            let fields = [
                now.year() as u32 % 100,
                now.month(),
                now.day(),
                now.hour(),
                now.minute(),
                now.second(),
            ];
            let mut bytes = fields.map(|n| (((n / 10) << 4) | (n % 10)) as u8).to_vec();
            bytes.push(1);
            let reply = link.request(1, &bytes, Duration::from_secs(3)).await?;
            ensure!(reply.answered(), "Band didn’t confirm the clock update.");
            Ok(Some("Band clock updated.".to_owned()))
        }
        Command::Alert => {
            link.alert().await?;
            Ok(Some("Vibration command sent.".to_owned()))
        }
        Command::Scan | Command::Connect { .. } | Command::Disconnect => unreachable!(),
    }
}

async fn measure(
    app: &App,
    link: &mut Link,
    id: Uuid,
    metric: Metric,
    stop: Arc<Notify>,
) -> Result<Option<String>> {
    let sensor = metric.live_type().context("Unsupported measurement.")?;
    link.drain();
    let start = link.write_uart(&encode(LIVE, &[sensor, 1]).unwrap()).await;
    let mut samples = Vec::with_capacity(6);
    let result = async {
        start?;
        let until = Instant::now() + Duration::from_secs(30);
        while samples.len() < 6 {
            let frame = tokio::select! {
                _ = stop.notified() => return Ok(true),
                frame = link.packet(Channel::Uart, until) => frame?,
            };
            let Some(frame) = frame else {
                break;
            };
            if !valid(&frame) {
                continue;
            }
            if frame[0] == LIVE | 128 || (frame[0] == LIVE && frame[1] == sensor && frame[2] != 0) {
                break;
            }
            let value = u16::from(frame[3]);
            if frame[0] == LIVE && frame[1] == sensor && metric.accepts(value) {
                samples.push(value);
                app.band().live = Some(Live {
                    metric,
                    value: Some(value),
                });
            }
        }
        Ok::<bool, anyhow::Error>(false)
    }
    .await;
    let stopped = link
        .write_uart(&encode(LIVE_STOP, &[sensor, 0, 0]).unwrap())
        .await;
    if !samples.is_empty() {
        samples.sort_unstable();
        let value = (samples[(samples.len() - 1) / 2] + samples[samples.len() / 2]) / 2;
        app.db().save_live(id, metric, value)?;
        app.changed();
    }
    stopped.context("Couldn’t stop measurement on the band. Reconnect before measuring again.")?;
    if result? && samples.is_empty() {
        return Ok(None);
    }
    ensure!(
        !samples.is_empty(),
        "No reading. Wear the band snugly, hold still, and try again."
    );
    Ok(Some("Measurement saved.".to_owned()))
}
