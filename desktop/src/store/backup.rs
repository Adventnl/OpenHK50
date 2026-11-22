use super::{Store, local_time, minute_of_day};
use anyhow::{Context, Result};
use chrono::NaiveDateTime;
use openh_k50_protocol::backup::{Kind, Origin, PAGE, Reading};
use rusqlite::{OptionalExtension, Row, params};
use uuid::Uuid;

pub struct Checkpoint {
    pub store: String,
    pub cursor: i64,
}

impl Store {
    pub fn checkpoint(&self, scope: &str) -> Result<Option<Checkpoint>> {
        Ok(self
            .db
            .query_row(
                "SELECT store,cursor FROM backup_progress WHERE scope=?1",
                [scope],
                |row| {
                    Ok(Checkpoint {
                        store: row.get(0)?,
                        cursor: row.get(1)?,
                    })
                },
            )
            .optional()?)
    }

    pub fn pending(&self, scope: &str, device: Uuid, mac: &str) -> Result<Vec<Reading>> {
        let mut query = self.db.prepare_cached("
            SELECT r.kind,r.origin,r.slot,r.time_ms,r.value,r.revised_at,r.revision FROM readings r
            LEFT JOIN backup_ack a ON a.scope=?1 AND a.kind=r.kind AND a.origin=r.origin AND a.slot=r.slot
            WHERE r.band=?2 AND r.kind IN ('HEART','STEPS') AND r.revision IS NOT a.revision
            ORDER BY r.kind,r.origin,r.slot LIMIT ?3
        ")?;
        Ok(query
            .query_map(params![scope, device.to_string(), PAGE], |row| {
                decode(row, mac)
            })?
            .collect::<rusqlite::Result<_>>()?)
    }

    pub fn reset_backup(&mut self, scope: &str, store: &str) -> Result<()> {
        let tx = self.db.transaction()?;
        tx.execute("DELETE FROM backup_ack WHERE scope=?1", [scope])?;
        tx.execute(
            "INSERT OR REPLACE INTO backup_progress VALUES (?1,?2,0)",
            params![scope, store],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn apply_page(
        &mut self,
        scope: &str,
        device: Uuid,
        mac: &str,
        checkpoint: Checkpoint,
        rows: impl Iterator<Item = Reading>,
    ) -> Result<bool> {
        let tx = self.db.transaction()?;
        let device = device.to_string();
        let mut changed = false;
        for row in rows.filter(|row| row.band == mac) {
            let (day, minute) = match row.origin {
                Origin::History => {
                    let slot = NaiveDateTime::parse_from_str(&row.slot, "%Y%m%dT%H%M")
                        .context("Invalid backup slot")?;
                    use chrono::Timelike;
                    (slot.date().to_string(), slot.hour() * 60 + slot.minute())
                }
                Origin::Live => {
                    let time = local_time(row.time).context("Invalid backup time")?;
                    (
                        time.date_naive().to_string(),
                        u32::from(minute_of_day(&time)),
                    )
                }
            };
            let kind = row.kind.as_str();
            let origin = row.origin.as_str();
            changed |= tx.prepare_cached("
                INSERT INTO readings VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)
                ON CONFLICT(band,kind,origin,slot) DO UPDATE SET
                    value=excluded.value,time_ms=excluded.time_ms,day=excluded.day,minute=excluded.minute,
                    revised_at=excluded.revised_at,revision=excluded.revision
                WHERE (excluded.revised_at,excluded.revision) > (readings.revised_at,readings.revision)
            ")?.execute(params![device, kind, origin, row.slot, day, minute, row.time, row.value, row.revised_at, row.revision])? > 0;
            tx.prepare_cached(
                "
                INSERT INTO backup_ack SELECT ?1,kind,origin,slot,revision FROM readings
                WHERE band=?2 AND kind=?3 AND origin=?4 AND slot=?5 AND revision=?6
                ON CONFLICT(scope,kind,origin,slot) DO UPDATE SET revision=excluded.revision
            ",
            )?
            .execute(params![scope, device, kind, origin, row.slot, row.revision])?;
        }
        tx.execute(
            "INSERT OR REPLACE INTO backup_progress VALUES (?1,?2,?3)",
            params![scope, checkpoint.store, checkpoint.cursor],
        )?;
        tx.commit()?;
        Ok(changed)
    }
}

fn decode(row: &Row<'_>, mac: &str) -> rusqlite::Result<Reading> {
    let kind = match row.get_ref(0)?.as_str()? {
        "HEART" => Kind::Heart,
        "STEPS" => Kind::Steps,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    let origin = match row.get_ref(1)?.as_str()? {
        "HISTORY" => Origin::History,
        "LIVE" => Origin::Live,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    Ok(Reading {
        band: mac.to_owned(),
        kind,
        origin,
        slot: row.get(2)?,
        time: row.get(3)?,
        value: row.get(4)?,
        revised_at: row.get(5)?,
        revision: row.get(6)?,
    })
}
