use super::Store;
use crate::model::{Workout, WorkoutStart};
use anyhow::Result;
use chrono::{Local, NaiveDate, Utc};
use rusqlite::{OptionalExtension, params};

impl Store {
    pub fn active_workout(&self) -> Result<Option<Workout>> {
        Ok(self.db.query_row(
            "SELECT day, activity, kg, started_at, ended_at FROM workouts WHERE ended_at IS NULL", [], decode,
        ).optional()?)
    }

    pub fn start_workout(&self, start: WorkoutStart) -> Result<bool> {
        let now = Local::now();
        Ok(self.db.execute(
            "
            INSERT INTO workouts(day,activity,kg,started_at)
            SELECT ?1,?2,?3,?4 WHERE NOT EXISTS (SELECT 1 FROM workouts WHERE ended_at IS NULL)
        ",
            params![
                now.date_naive().to_string(),
                serde_json::to_string(&start.activity)?,
                start.kg,
                now.timestamp_millis()
            ],
        )? == 1)
    }

    pub fn stop_workout(&self) -> Result<bool> {
        Ok(self.db.execute(
            "
            UPDATE workouts SET ended_at=max(started_at,?1) WHERE ended_at IS NULL
        ",
            [Utc::now().timestamp_millis()],
        )? == 1)
    }

    pub fn workouts(&self, day: NaiveDate) -> Result<Vec<Workout>> {
        let mut query = self.db.prepare_cached(
            "
            SELECT day,activity,kg,started_at,ended_at FROM workouts
            WHERE day=?1 AND ended_at IS NOT NULL ORDER BY started_at DESC
        ",
        )?;
        Ok(query
            .query_map([day.to_string()], decode)?
            .collect::<rusqlite::Result<_>>()?)
    }
}

fn decode(row: &rusqlite::Row<'_>) -> rusqlite::Result<Workout> {
    let day = row
        .get_ref(0)?
        .as_str()?
        .parse()
        .map_err(|_| rusqlite::Error::InvalidQuery)?;
    let activity = serde_json::from_str(row.get_ref(1)?.as_str()?)
        .map_err(|_| rusqlite::Error::InvalidQuery)?;
    Ok(Workout::from_times(
        day,
        activity,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        Utc::now().timestamp_millis(),
    ))
}
