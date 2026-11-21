#[cfg(feature = "android")]
mod android;

pub const FRAME_LEN: usize = 16;
pub const HEART_HISTORY: u8 = 21;
pub const STEPS: u8 = 67;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Interval {
    pub minute: u16,
    pub value: u16,
}

pub fn encode(command: u8, payload: &[u8]) -> Option<[u8; FRAME_LEN]> {
    if payload.len() > FRAME_LEN - 2 {
        return None;
    }
    let mut frame = [0; FRAME_LEN];
    frame[0] = command;
    frame[1..1 + payload.len()].copy_from_slice(payload);
    frame[15] = checksum(&frame[..15]);
    Some(frame)
}

fn checksum(data: &[u8]) -> u8 {
    data.iter().copied().fold(0u8, u8::wrapping_add)
}

pub fn valid(frame: &[u8]) -> bool {
    frame.len() == FRAME_LEN && checksum(&frame[..15]) == frame[15]
}

pub fn heart_rate_valid(value: u8) -> bool {
    (1..255).contains(&value)
}

pub fn heart_intervals(frames: &[u8]) -> Vec<Interval> {
    let mut slots = [0; 288];
    let mut header = false;
    for frame in frames.chunks_exact(FRAME_LEN) {
        if !valid(frame) || frame[0] != HEART_HISTORY {
            continue;
        }
        match frame[1] {
            0 if frame[2] > 0 => {
                header = true;
                slots.fill(0);
            }
            1 if header => slots[..9].copy_from_slice(&frame[6..15]),
            index @ 2..=23 if header => {
                let start = 9 + 13 * (usize::from(index) - 2);
                let count = 13.min(slots.len() - start);
                slots[start..start + count].copy_from_slice(&frame[2..2 + count]);
            }
            _ => {}
        }
    }
    slots
        .iter()
        .enumerate()
        .filter(|&(_, &value)| heart_rate_valid(value))
        .map(|(slot, &value)| Interval {
            minute: (slot * 5) as u16,
            value: u16::from(value),
        })
        .collect()
}

fn bcd(value: u8) -> Option<u32> {
    let (high, low) = (value >> 4, value & 15);
    (high <= 9 && low <= 9).then(|| u32::from(high) * 10 + u32::from(low))
}

pub fn step_intervals(frames: &[u8], date: u32) -> Vec<Interval> {
    let mut slots = [None; 96];
    for frame in frames.chunks_exact(FRAME_LEN) {
        if !valid(frame) || frame[0] != STEPS {
            continue;
        }
        let (Some(year), Some(month), Some(day)) = (bcd(frame[1]), bcd(frame[2]), bcd(frame[3]))
        else {
            continue;
        };
        if 20_000_000 + year * 10_000 + month * 100 + day != date {
            continue;
        }
        let slot = usize::from(frame[4]);
        let value = u16::from_le_bytes([frame[9], frame[10]]);
        if slot < slots.len() && value != u16::MAX {
            slots[slot] = Some(value);
        }
    }
    slots
        .into_iter()
        .enumerate()
        .filter_map(|(slot, value)| {
            value.map(|value| Interval {
                minute: (slot * 15) as u16,
                value,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_battery_query_and_rejects_corruption() {
        let mut frame = encode(3, &[]).unwrap();
        assert_eq!(frame, [3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3]);
        frame[15] = 4;
        assert!(!valid(&frame));
    }

    #[test]
    fn missing_heart_chunk_does_not_shift_following_samples() {
        let frames = [encode(21, &[0, 24]).unwrap(), encode(21, &[3, 72]).unwrap()].concat();
        assert_eq!(
            heart_intervals(&frames),
            [Interval {
                minute: 110,
                value: 72
            }]
        );
    }

    #[test]
    fn steps_keep_real_zero_and_ignore_other_days() {
        let frame = encode(67, &[0x26, 0x11, 0x21, 5]).unwrap();
        assert_eq!(
            step_intervals(&frame, 20261121),
            [Interval {
                minute: 75,
                value: 0
            }]
        );
        assert!(step_intervals(&frame, 20261120).is_empty());
    }
}
