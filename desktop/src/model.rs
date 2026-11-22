use chrono::{Local, NaiveDate};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub fn today() -> NaiveDate {
    Local::now().date_naive()
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    pub id: Uuid,
    pub name: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Features {
    pub auto_sync: bool,
    pub workouts: bool,
    pub notifications: bool,
    pub extra_metrics: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Feature {
    AutoSync,
    Workouts,
    Notifications,
    ExtraMetrics,
}

impl Features {
    pub fn set(&mut self, feature: Feature, enabled: bool) {
        let setting = match feature {
            Feature::AutoSync => &mut self.auto_sync,
            Feature::Workouts => &mut self.workouts,
            Feature::Notifications => &mut self.notifications,
            Feature::ExtraMetrics => &mut self.extra_metrics,
        };
        *setting = enabled;
    }
}

impl Default for Features {
    fn default() -> Self {
        Self {
            auto_sync: true,
            workouts: true,
            notifications: true,
            extra_metrics: true,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Metric {
    Heart,
    Steps,
    Calories,
    Distance,
    Oxygen,
    Stress,
    Hrv,
}

impl Metric {
    pub const fn key(self) -> &'static str {
        match self {
            Self::Heart => "HEART",
            Self::Steps => "STEPS",
            Self::Calories => "CALORIES",
            Self::Distance => "DISTANCE",
            Self::Oxygen => "OXYGEN",
            Self::Stress => "STRESS",
            Self::Hrv => "HRV",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        [
            Self::Heart,
            Self::Steps,
            Self::Calories,
            Self::Distance,
            Self::Oxygen,
            Self::Stress,
            Self::Hrv,
        ]
        .into_iter()
        .find(|metric| metric.key() == key)
    }

    pub fn accepts(self, value: u16) -> bool {
        match self {
            Self::Heart | Self::Hrv => (1..=254).contains(&value),
            Self::Oxygen | Self::Stress => (1..=100).contains(&value),
            Self::Steps | Self::Calories | Self::Distance => value < u16::MAX,
        }
    }

    pub fn live_type(self) -> Option<u8> {
        match self {
            Self::Heart => Some(1),
            Self::Oxygen => Some(3),
            Self::Stress => Some(8),
            _ => None,
        }
    }
}

#[derive(Serialize)]
pub struct Reading {
    pub metric: Metric,
    pub minute: u16,
    pub time: i64,
    pub value: u16,
    pub live: bool,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Sleep {
    pub day: NaiveDate,
    pub start: u16,
    pub stages: Vec<SleepStage>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct SleepStage {
    pub stage: Stage,
    pub minutes: u16,
}

#[derive(Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Light,
    Deep,
    Rem,
    Awake,
}

#[derive(Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Activity {
    Gym,
    Weights,
    Elliptical,
}

impl Activity {
    pub const ALL: [Self; 3] = [Self::Gym, Self::Weights, Self::Elliptical];

    pub const fn label(self) -> &'static str {
        match self {
            Self::Gym => "Gym",
            Self::Weights => "Weight training",
            Self::Elliptical => "Elliptical",
        }
    }

    pub const fn met(self) -> f64 {
        match self {
            Self::Gym => 5.5,
            Self::Weights => 3.5,
            Self::Elliptical => 5.0,
        }
    }
}

#[derive(Deserialize)]
pub struct WorkoutStart {
    pub activity: Activity,
    pub kg: f64,
}

#[derive(Serialize)]
pub struct Workout {
    pub day: NaiveDate,
    pub activity: Activity,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub duration_seconds: u64,
    pub estimated_kcal: u32,
}

impl Workout {
    pub fn from_times(
        day: NaiveDate,
        activity: Activity,
        kg: f64,
        start: i64,
        end: Option<i64>,
        now: i64,
    ) -> Self {
        let elapsed = end.unwrap_or(now).saturating_sub(start).max(0);
        let kcal = activity.met() * 3.5 * kg / 200.0 * (elapsed as f64 / 60_000.0);
        Self {
            day,
            activity,
            started_at: start,
            ended_at: end,
            duration_seconds: (elapsed / 1000) as u64,
            estimated_kcal: kcal.round() as u32,
        }
    }
}

#[derive(Serialize)]
pub struct Day {
    pub readings: Vec<Reading>,
    pub sleep: Option<Sleep>,
    pub workouts: Vec<Workout>,
}
