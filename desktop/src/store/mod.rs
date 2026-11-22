pub mod backup;
#[cfg(test)]
mod tests;
mod workouts;

use crate::model::{Day, Device, Features, Metric, Reading, Sleep};
use anyhow::{Context, Result};
use chrono::{DateTime, Local, NaiveDate, TimeZone, Timelike, Utc};
use openh_k50_protocol::Interval;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Serialize, de::DeserializeOwned};
use std::{fs::OpenOptions, os::unix::fs::OpenOptionsExt, path::Path, time::Duration};
use uuid::Uuid;

pub struct Store {
    db: Connection,
}

const SCHEMA: &str = "
    CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE readings(
        band TEXT NOT NULL, kind TEXT NOT NULL, origin TEXT NOT NULL, slot TEXT NOT NULL,
        day TEXT NOT NULL, minute INTEGER NOT NULL, time_ms INTEGER NOT NULL,
        value INTEGER NOT NULL, revised_at INTEGER NOT NULL, revision TEXT NOT NULL,
        PRIMARY KEY(band, kind, origin, slot)
    ) WITHOUT ROWID;
    CREATE INDEX readings_day ON readings(band, day, time_ms);
    CREATE TABLE sleep(band TEXT NOT NULL, day TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(band, day));
    CREATE TABLE workouts(
        id INTEGER PRIMARY KEY, day TEXT NOT NULL, activity TEXT NOT NULL, kg REAL NOT NULL,
        started_at INTEGER NOT NULL, ended_at INTEGER
    );
    CREATE UNIQUE INDEX workout_active ON workouts((1)) WHERE ended_at IS NULL;
    CREATE INDEX workout_day ON workouts(day, started_at);
    CREATE TABLE backup_progress(scope TEXT PRIMARY KEY, store TEXT NOT NULL, cursor INTEGER NOT NULL);
    CREATE TABLE backup_ack(
        scope TEXT NOT NULL, kind TEXT NOT NULL, origin TEXT NOT NULL, slot TEXT NOT NULL,
        revision TEXT NOT NULL, PRIMARY KEY(scope, kind, origin, slot)
    ) WITHOUT ROWID;
    PRAGMA user_version=1;
";

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .open(path)
            .context("Open the local health database file")?;
        let mut db = Connection::open(path)?;
        db.busy_timeout(Duration::from_secs(2))?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        let tx = db.transaction()?;
        let version: u32 = tx.pragma_query_value(None, "user_version", |row| row.get(0))?;
        match version {
            0 => tx.execute_batch(SCHEMA)?,
            1 => {}
            _ => anyhow::bail!("Unsupported health database version"),
        }
        tx.commit()?;
        Ok(Self { db })
    }

    fn setting<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>> {
        let value: Option<String> = self
            .db
            .query_row("SELECT value FROM settings WHERE key=?1", [key], |row| {
                row.get(0)
            })
            .optional()?;
        value
            .map(|json| serde_json::from_str(&json).context("Read saved setting"))
            .transpose()
    }

    pub fn set<T: Serialize>(&self, key: &str, value: &T) -> Result<()> {
        self.db.execute(
            "INSERT OR REPLACE INTO settings VALUES (?1, ?2)",
            params![key, serde_json::to_string(value)?],
        )?;
        Ok(())
    }

    pub fn device(&self) -> Result<Option<Device>> {
        self.setting("device")
    }

    pub fn features(&self) -> Result<Features> {
        Ok(self.setting("features")?.unwrap_or_default())
    }

    pub fn detail_user(&self, band: Uuid) -> Result<Option<String>> {
        Ok(self.setting(&format!("detail.{band}"))?.flatten())
    }

    pub fn save_intervals(
        &mut self,
        band: Uuid,
        day: NaiveDate,
        metrics: &[(Metric, Vec<Interval>)],
    ) -> Result<bool> {
        let tx = self.db.transaction()?;
        let mut changed = false;
        {
            let mut insert = tx.prepare_cached(
                "
                INSERT INTO readings VALUES (?1,?2,'HISTORY',?3,?4,?5,?6,?7,?8,?9)
                ON CONFLICT(band,kind,origin,slot) DO UPDATE SET
                    value=excluded.value, revised_at=max(readings.revised_at,excluded.revised_at),
                    revision=excluded.revision
                WHERE readings.value<>excluded.value
            ",
            )?;
            let now = Local::now().timestamp_millis();
            let band = band.to_string();
            for (metric, intervals) in metrics {
                for Interval { minute, value } in intervals {
                    if !metric.accepts(*value) {
                        continue;
                    }
                    let Some(at) =
                        day.and_hms_opt(u32::from(minute / 60), u32::from(minute % 60), 0)
                    else {
                        continue;
                    };
                    let Some(time) = Local
                        .from_local_datetime(&at)
                        .earliest()
                        .map(|dt| dt.timestamp_millis())
                    else {
                        continue;
                    };
                    if time > now {
                        continue;
                    }
                    let slot = at.format("%Y%m%dT%H%M").to_string();
                    changed |= insert.execute(params![
                        band,
                        metric.key(),
                        slot,
                        day.to_string(),
                        minute,
                        time,
                        value,
                        now,
                        revision()
                    ])? > 0;
                }
            }
        }
        tx.commit()?;
        Ok(changed)
    }

    pub fn save_live(&self, band: Uuid, metric: Metric, value: u16) -> Result<()> {
        let now = Local::now();
        let id = revision();
        self.db.execute(
            "INSERT INTO readings VALUES (?1,?2,'LIVE',?3,?4,?5,?6,?7,?6,?3)",
            params![
                band.to_string(),
                metric.key(),
                id,
                now.date_naive().to_string(),
                minute_of_day(&now),
                now.timestamp_millis(),
                value
            ],
        )?;
        Ok(())
    }

    pub fn save_sleep(&mut self, band: Uuid, nights: &[Sleep]) -> Result<bool> {
        let tx = self.db.transaction()?;
        let mut changed = false;
        let band = band.to_string();
        for night in nights {
            changed |= tx.execute("
                INSERT INTO sleep VALUES (?1,?2,?3) ON CONFLICT(band,day) DO UPDATE SET payload=excluded.payload
                WHERE sleep.payload<>excluded.payload
            ", params![band, night.day.to_string(), serde_json::to_string(night)?])? > 0;
        }
        tx.commit()?;
        Ok(changed)
    }

    pub fn day(&self, band: Option<Uuid>, day: NaiveDate) -> Result<Day> {
        let id = band.map(|id| id.to_string());
        let date = day.to_string();
        let mut query = self.db.prepare_cached("
            SELECT kind, minute, time_ms, value, origin='LIVE' FROM readings WHERE band=?1 AND day=?2
            ORDER BY time_ms, origin, slot
        ")?;
        let readings = query
            .query_map(params![id, date], |row| {
                let metric = Metric::from_key(row.get_ref(0)?.as_str()?)
                    .ok_or(rusqlite::Error::InvalidQuery)?;
                Ok(Reading {
                    metric,
                    minute: row.get(1)?,
                    time: row.get(2)?,
                    value: row.get(3)?,
                    live: row.get(4)?,
                })
            })?
            .collect::<rusqlite::Result<_>>()?;
        let sleep: Option<String> = self
            .db
            .query_row(
                "SELECT payload FROM sleep WHERE band=?1 AND day=?2",
                params![id, date],
                |row| row.get(0),
            )
            .optional()?;
        Ok(Day {
            readings,
            sleep: sleep
                .map(|value| serde_json::from_str(&value))
                .transpose()?,
            workouts: self.workouts(day)?,
        })
    }
}

fn revision() -> String {
    Uuid::new_v4().simple().to_string()
}

fn minute_of_day(time: &DateTime<Local>) -> u16 {
    (time.hour() * 60 + time.minute()) as u16
}

fn local_time(millis: i64) -> Option<DateTime<Local>> {
    DateTime::<Utc>::from_timestamp_millis(millis).map(|time| time.with_timezone(&Local))
}
