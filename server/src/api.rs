use serde::{Deserialize, Serialize};

pub const PAGE: usize = 200;
pub const MAX_TIME: i64 = 4_102_444_800_000;

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Kind {
    Heart,
    Steps,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Heart => "HEART",
            Self::Steps => "STEPS",
        }
    }
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Origin {
    History,
    Live,
}

impl Origin {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::History => "HISTORY",
            Self::Live => "LIVE",
        }
    }
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Reading {
    pub band: String,
    pub kind: Kind,
    pub origin: Origin,
    pub slot: String,
    pub time: i64,
    pub value: u16,
    pub revised_at: i64,
    pub revision: String,
}

impl Reading {
    pub fn valid(&self) -> bool {
        let band = self.band.as_bytes();
        band.len() == 17
            && band.iter().enumerate().all(|(i, b)| {
                if i % 3 == 2 {
                    *b == b':'
                } else {
                    b.is_ascii_digit() || (b'A'..=b'F').contains(b)
                }
            })
            && is_hex(&self.revision, 32)
            && (1..MAX_TIME).contains(&self.time)
            && (self.time..MAX_TIME).contains(&self.revised_at)
            && match self.kind {
                Kind::Heart => (1..=254).contains(&self.value),
                Kind::Steps => self.value < u16::MAX,
            }
            && match self.origin {
                Origin::Live => {
                    matches!(self.kind, Kind::Heart)
                        && self.slot == self.revision
                        && self.time == self.revised_at
                }
                Origin::History => {
                    let slot = self.slot.as_bytes();
                    slot.len() == 13
                        && slot[0] == b'2'
                        && slot[8] == b'T'
                        && slot[..8].iter().chain(&slot[9..]).all(u8::is_ascii_digit)
                }
            }
    }
}

pub fn is_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub store: Option<String>,
    pub after: i64,
    pub readings: Vec<Reading>,
}

impl Request {
    pub fn valid(&self) -> bool {
        self.after >= 0
            && self.store.as_ref().is_none_or(|store| is_hex(store, 32))
            && self.readings.len() <= PAGE
            && self.readings.iter().all(Reading::valid)
    }
}

#[derive(Serialize)]
pub struct StoreChanged {
    pub store: String,
}

#[derive(Serialize)]
pub struct Response {
    pub store: String,
    pub cursor: i64,
    pub more: bool,
    pub accepted: Vec<Reading>,
    pub readings: Vec<Reading>,
}
