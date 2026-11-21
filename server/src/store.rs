use crate::api::{Kind, Origin, PAGE, Reading, Request, Response, StoreChanged};
use rusqlite::{Connection, Result, Row, TransactionBehavior, params};
use std::{path::Path, time::Duration};

pub struct Store {
    db: Connection,
    id: String,
}

pub enum Exchange {
    Ready(Response),
    Reset(StoreChanged),
}

const SELECT: &str =
    "SELECT band,kind,origin,slot,time_ms,value,revised_at,revision,seq FROM readings";
const UPSERT: &str = "
    INSERT INTO readings(band,kind,origin,slot,time_ms,value,revised_at,revision,seq)
    VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
    ON CONFLICT(band,kind,origin,slot) DO UPDATE SET
        time_ms=excluded.time_ms,value=excluded.value,revised_at=excluded.revised_at,
        revision=excluded.revision,seq=excluded.seq
    WHERE (excluded.revised_at,excluded.revision) > (readings.revised_at,readings.revision)";

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        let mut db = Connection::open(path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let version: u32 = tx.pragma_query_value(None, "user_version", |row| row.get(0))?;
        match version {
            0 => {
                tx.execute_batch(
                    "CREATE TABLE meta(id INTEGER PRIMARY KEY CHECK(id=1), store TEXT NOT NULL, seq INTEGER NOT NULL);
                     CREATE TABLE readings(
                        band TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('HEART','STEPS')),
                        origin TEXT NOT NULL CHECK(origin IN ('HISTORY','LIVE')), slot TEXT NOT NULL,
                        time_ms INTEGER NOT NULL, value INTEGER NOT NULL, revised_at INTEGER NOT NULL,
                        revision TEXT NOT NULL, seq INTEGER NOT NULL UNIQUE,
                        PRIMARY KEY(band,kind,origin,slot)
                     ) WITHOUT ROWID;
                     PRAGMA user_version=1;",
                )?;
                let id = uuid::Uuid::new_v4().simple().to_string();
                tx.execute("INSERT INTO meta VALUES(1,?1,0)", [&id])?;
            }
            1 => {}
            _ => return Err(rusqlite::Error::InvalidQuery),
        }
        let id = tx.query_row("SELECT store FROM meta WHERE id=1", [], |row| row.get(0))?;
        tx.commit()?;
        Ok(Self { db, id })
    }

    pub fn exchange(&mut self, request: Request) -> Result<Exchange> {
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut newest: i64 =
            tx.query_row("SELECT seq FROM meta WHERE id=1", [], |row| row.get(0))?;
        if request.store.as_deref() != Some(&self.id) || request.after > newest {
            return Ok(Exchange::Reset(StoreChanged {
                store: self.id.clone(),
            }));
        }

        let mut accepted = Vec::with_capacity(request.readings.len());
        if !request.readings.is_empty() {
            let mut upsert = tx.prepare_cached(UPSERT)?;
            let mut canonical = tx.prepare_cached(&format!(
                "{SELECT} WHERE band=?1 AND kind=?2 AND origin=?3 AND slot=?4"
            ))?;
            for value in &request.readings {
                let seq = newest.checked_add(1).ok_or(rusqlite::Error::InvalidQuery)?;
                let changed = upsert.execute(params![
                    value.band,
                    value.kind.as_str(),
                    value.origin.as_str(),
                    value.slot,
                    value.time,
                    value.value,
                    value.revised_at,
                    value.revision,
                    seq,
                ])?;
                if changed > 0 {
                    newest = seq;
                }
            }
            // Reply with the winning version even if its sequence predates the client's cursor.
            for value in &request.readings {
                accepted.push(
                    canonical
                        .query_row(
                            params![
                                value.band,
                                value.kind.as_str(),
                                value.origin.as_str(),
                                value.slot
                            ],
                            decode_row,
                        )?
                        .0,
                );
            }
            tx.execute("UPDATE meta SET seq=?1 WHERE id=1", [newest])?;
        }

        let mut rows = {
            let sql = format!("{SELECT} WHERE seq>?1 ORDER BY seq LIMIT ?2");
            let mut query = tx.prepare_cached(&sql)?;
            query
                .query_map(params![request.after, PAGE + 1], decode_row)?
                .collect::<Result<Vec<_>>>()?
        };
        let more = rows.len() > PAGE;
        rows.truncate(PAGE);
        let cursor = rows.last().map_or(request.after, |row| row.1);
        tx.commit()?;
        Ok(Exchange::Ready(Response {
            store: self.id.clone(),
            cursor,
            more,
            accepted,
            readings: rows.into_iter().map(|row| row.0).collect(),
        }))
    }
}

fn decode_row(row: &Row<'_>) -> Result<(Reading, i64)> {
    let kind = match row.get_ref(1)?.as_str()? {
        "HEART" => Kind::Heart,
        "STEPS" => Kind::Steps,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    let origin = match row.get_ref(2)?.as_str()? {
        "HISTORY" => Origin::History,
        "LIVE" => Origin::Live,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    Ok((
        Reading {
            band: row.get(0)?,
            kind,
            origin,
            slot: row.get(3)?,
            time: row.get(4)?,
            value: row.get(5)?,
            revised_at: row.get(6)?,
            revision: row.get(7)?,
        },
        row.get(8)?,
    ))
}
