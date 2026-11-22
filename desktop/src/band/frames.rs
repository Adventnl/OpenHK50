use super::link::{Channel, Link};
use anyhow::Result;
use openh_k50_protocol::{FRAME_LEN, HEART_HISTORY, Interval, STEPS, encode, valid};
use std::time::Duration;
use tokio::time::Instant;

pub const BATTERY: u8 = 3;
pub const HEART_LOG: u8 = 22;
pub const STRESS: u8 = 55;
pub const HRV: u8 = 57;
pub const LIVE: u8 = 105;
pub const LIVE_STOP: u8 = 106;

pub struct Reply {
    frames: Vec<u8>,
    rejected: bool,
}

impl Reply {
    pub fn answered(&self) -> bool {
        !self.frames.is_empty() && !self.rejected
    }

    pub fn first(&self) -> Option<&[u8]> {
        self.answered().then(|| &self.frames[..FRAME_LEN])
    }

    pub fn frames(&self) -> &[u8] {
        if self.rejected { &[] } else { &self.frames }
    }
}

impl Link {
    pub async fn request(&mut self, command: u8, payload: &[u8], wait: Duration) -> Result<Reply> {
        self.drain();
        self.write_uart(&encode(command, payload).expect("bounded UART payload"))
            .await?;
        let until = Instant::now() + wait;
        let mut reply = Reply {
            frames: Vec::new(),
            rejected: false,
        };
        while let Some(frame) = self.packet(Channel::Uart, until).await? {
            if !valid(&frame) {
                continue;
            }
            if frame[0] == command | 128 {
                reply.rejected = true;
                break;
            }
            if frame[0] != command || (command == BATTERY && frame[1] > 100) {
                continue;
            }
            reply.frames.extend_from_slice(&frame);
            let terminal = match command {
                HEART_HISTORY => matches!(frame[1], 23 | 255),
                STEPS => frame[1] == 255,
                STRESS | HRV => {
                    frame[1] == 255
                        || (reply.frames[1] == 0
                            && u16::from(frame[1]) + 1 >= u16::from(reply.frames[2]))
                }
                _ => true,
            };
            if terminal || reply.frames.len() == 120 * FRAME_LEN {
                break;
            }
        }
        Ok(reply)
    }
}

pub fn slot_intervals(frames: &[u8]) -> Vec<Interval> {
    let mut slots = [0; 1440];
    let interval = frames
        .chunks_exact(FRAME_LEN)
        .find(|frame| frame[1] == 0 && frame[3] > 0)
        .map_or(30, |frame| usize::from(frame[3]));
    for frame in frames.chunks_exact(FRAME_LEN) {
        match frame[1] {
            255 => return Vec::new(),
            0 => continue,
            chunk => {
                for (index, value) in frame[2..15].iter().enumerate() {
                    let minute = ((usize::from(chunk) - 1) * 13 + index) * interval;
                    if let Some(slot) = slots.get_mut(minute) {
                        *slot = *value;
                    }
                }
            }
        }
    }
    slots
        .into_iter()
        .enumerate()
        .filter(|(_, value)| *value != 0)
        .map(|(minute, value)| Interval {
            minute: minute as u16,
            value: u16::from(value),
        })
        .collect()
}
