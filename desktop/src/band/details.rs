use super::link::{Channel, Link};
use crate::model::{Sleep, SleepStage, Stage};
use anyhow::Result;
use chrono::{Days, NaiveDate};
use openh_k50_protocol::Interval;
use std::time::Duration;
use tokio::time::{Instant, sleep};

const LOGIN: u8 = 0x4a;
const INIT: u8 = 0x30;
pub const SLEEP: u8 = 0x27;
pub const OXYGEN: u8 = 0x2a;
const HEADER: usize = 6;

#[derive(Default)]
pub struct DetailDecoder {
    pending: Vec<u8>,
}

fn crc(bytes: &[u8]) -> u16 {
    let mut value = 0xffff;
    for &byte in bytes {
        value ^= u16::from(byte);
        for _ in 0..8 {
            value = (value >> 1) ^ (if value & 1 == 1 { 0xa001 } else { 0 });
        }
    }
    value
}

fn packet(kind: u8, body: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(HEADER + body.len());
    bytes.extend([0xbc, kind]);
    bytes.extend((body.len() as u16).to_le_bytes());
    bytes.extend(crc(body).to_le_bytes());
    bytes.extend(body);
    bytes
}

fn u16_at(bytes: &[u8], index: usize) -> u16 {
    u16::from_le_bytes([bytes[index], bytes[index + 1]])
}

impl DetailDecoder {
    fn push(&mut self, bytes: &[u8]) -> Vec<(u8, Vec<u8>)> {
        self.pending.extend(bytes);
        let mut offset = 0;
        let mut frames = Vec::new();
        while let Some(magic) = self.pending[offset..].iter().position(|&byte| byte == 0xbc) {
            offset += magic;
            let frame = &self.pending[offset..];
            if frame.len() < HEADER {
                break;
            }
            let length = usize::from(u16_at(frame, 2));
            if length > 8192 {
                offset += 1;
                continue;
            }
            let Some(body) = frame.get(HEADER..HEADER + length) else {
                break;
            };
            if crc(body) == u16_at(frame, 4) {
                frames.push((frame[1], body.to_vec()));
            }
            offset += HEADER + length;
        }
        if !self.pending[offset..].contains(&0xbc) {
            self.pending.clear();
        } else {
            self.pending.drain(..offset);
        }
        frames
    }
}

impl Link {
    pub async fn detail_login(&mut self, username: &str) -> Result<()> {
        self.subscribe_details().await?;
        let mut body = vec![1, 1, 0, 0xff, 0xfe];
        body.extend(username.encode_utf16().flat_map(u16::to_le_bytes));
        self.write_detail(&packet(LOGIN, &body)).await?;
        sleep(Duration::from_millis(400)).await;
        self.write_detail(&packet(INIT, &[])).await?;
        sleep(Duration::from_millis(400)).await;
        self.drain();
        Ok(())
    }

    pub async fn detail<T>(
        &mut self,
        kind: u8,
        request: &[u8],
        decode: impl Fn(&[u8]) -> Option<T>,
    ) -> Result<Option<T>> {
        self.write_detail(&packet(kind, request)).await?;
        let mut buffer = DetailDecoder::default();
        let until = Instant::now() + Duration::from_millis(3500);
        while let Some(bytes) = self.packet(Channel::Detail, until).await? {
            for (frame, body) in buffer.push(&bytes) {
                if frame == kind
                    && let Some(result) = decode(&body)
                {
                    return Ok(Some(result));
                }
            }
        }
        Ok(None)
    }
}

pub fn oxygen(body: &[u8], offset: u8) -> Option<Vec<Interval>> {
    if body.len() != 49 || body[0] != offset.wrapping_neg() {
        return None;
    }
    Some(
        body[1..]
            .chunks_exact(2)
            .enumerate()
            .filter(|(_, pair)| (1..=100).contains(&pair[1]))
            .map(|(hour, pair)| Interval {
                minute: hour as u16 * 60,
                value: u16::from(pair[1]),
            })
            .collect(),
    )
}

pub fn nights(mut body: &[u8], today: NaiveDate) -> Option<Vec<Sleep>> {
    let (&count, remaining) = body.split_first()?;
    body = remaining;
    let mut result = Vec::new();
    for _ in 0..count {
        let header = body.get(..6)?;
        let day = today.checked_sub_days(Days::new(u64::from(header[0])))?;
        let start = u16_at(header, 2);
        let end = u16_at(header, 4);
        if start >= 1440 || end >= 1440 {
            return None;
        }
        body = &body[6..];
        let duration = (end + 1440 - start) % 1440;
        let mut elapsed = 0;
        let mut stages = Vec::new();
        while elapsed < duration {
            let stage = match *body.first()? {
                2 => Stage::Light,
                3 => Stage::Deep,
                4 => Stage::Rem,
                5 => Stage::Awake,
                _ => return None,
            };
            let minutes = u16::from(*body.get(1)?);
            body = &body[2..];
            if minutes > 0 {
                stages.push(SleepStage { stage, minutes });
            }
            elapsed += minutes;
        }
        if elapsed != duration {
            return None;
        }
        if !stages.is_empty() {
            result.push(Sleep { day, start, stages });
        }
    }
    Some(result)
}
