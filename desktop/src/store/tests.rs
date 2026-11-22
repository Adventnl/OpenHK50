use super::{Connection, SCHEMA, Store};
use crate::model::{Activity, Metric, WorkoutStart, today};
use chrono::Days;
use openh_k50_protocol::Interval;
use uuid::Uuid;

fn empty_store() -> Store {
    let db = Connection::open_in_memory().unwrap();
    db.execute_batch(SCHEMA).unwrap();
    Store { db }
}

#[test]
fn repeated_sync_updates_the_interval_and_keeps_days_and_bands_separate() {
    let mut store = empty_store();
    let first = Uuid::new_v4();
    let other = Uuid::new_v4();
    let day = today() - Days::new(2);
    let steps = |value| [(Metric::Steps, vec![Interval { minute: 15, value }])];
    assert!(store.save_intervals(first, day, &steps(0)).unwrap());
    assert!(!store.save_intervals(first, day, &steps(0)).unwrap());
    assert!(store.save_intervals(first, day, &steps(20)).unwrap());
    assert!(store.save_intervals(other, day, &steps(99)).unwrap());
    let readings = store.day(Some(first), day).unwrap().readings;
    assert_eq!(readings.len(), 1);
    assert_eq!(readings[0].value, 20);
    assert!(store.day(Some(first), today()).unwrap().readings.is_empty());
}

#[test]
fn starting_twice_keeps_one_session_and_stopping_adds_one_history_row() {
    let store = empty_store();
    let start = || WorkoutStart {
        activity: Activity::Gym,
        kg: 75.0,
    };
    let day = today();
    assert!(store.start_workout(start()).unwrap());
    assert!(!store.start_workout(start()).unwrap());
    let active = store.active_workout().unwrap().unwrap();
    assert_eq!(active.day, day);
    assert!(store.stop_workout().unwrap());
    assert!(!store.stop_workout().unwrap());
    assert!(store.active_workout().unwrap().is_none());
    let saved = store.workouts(day).unwrap();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].started_at, active.started_at);
}
