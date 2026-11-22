use super::{
    details,
    frames::{BATTERY, HRV, STRESS, slot_intervals},
    link::Link,
};
use crate::{
    App,
    model::{Metric, today},
};
use anyhow::{Result, ensure};
use chrono::{Datelike, Days, Local, NaiveDate};
use openh_k50_protocol::{HEART_HISTORY, Interval, STEPS, activity_intervals, heart_intervals};
use std::time::Duration;
use uuid::Uuid;

pub async fn battery(app: &App, link: &mut Link) -> Result<()> {
    let reply = link.request(BATTERY, &[], Duration::from_secs(3)).await?;
    if let Some(frame) = reply.first() {
        let mut status = app.band();
        status.battery = Some(frame[1]);
        status.charging = frame[2] != 0;
    }
    Ok(())
}

pub async fn history(app: &App, link: &mut Link, band: Uuid, days: u8) -> Result<Option<String>> {
    battery(app, link).await?;
    let date = today();
    let (features, username) = {
        let db = app.db();
        (db.features()?, db.detail_user(band)?)
    };
    let mut missing_heart = 0;
    let mut missing_steps = 0;
    let mut extra = [
        (STRESS, Metric::Stress, features.extra_metrics),
        (HRV, Metric::Hrv, features.extra_metrics),
    ];
    for offset in 0..days {
        ensure!(today() == date, "The day changed. Sync again.");
        let day = date - Days::new(u64::from(offset));
        // The band expects its wall-clock midnight encoded as UTC seconds.
        let seconds = day.and_hms_opt(0, 0, 0).unwrap().and_utc().timestamp() as u32;
        let heart = link
            .request(
                HEART_HISTORY,
                &seconds.to_le_bytes(),
                Duration::from_millis(3500),
            )
            .await?;
        if !heart.answered() {
            missing_heart += 1;
        }
        save(
            app,
            band,
            day,
            &[(Metric::Heart, heart_intervals(heart.frames()))],
        )?;

        ensure!(today() == date, "The day changed. Sync again.");
        let steps = link
            .request(
                STEPS,
                &[offset, 0x0f, 0, 0x5f, 1],
                Duration::from_millis(3500),
            )
            .await?;
        if !steps.answered() {
            missing_steps += 1;
        }
        ensure!(today() == date, "The day changed. Sync again.");
        let code = day.year() as u32 * 10_000 + day.month() * 100 + day.day();
        let mut metrics = [
            (Metric::Steps, Vec::new()),
            (Metric::Calories, Vec::new()),
            (Metric::Distance, Vec::new()),
        ];
        for slot in activity_intervals(steps.frames(), code) {
            for ((_, intervals), value) in
                metrics
                    .iter_mut()
                    .zip([slot.steps, slot.calories, slot.distance])
            {
                if let Some(value) = value {
                    intervals.push(Interval {
                        minute: slot.minute,
                        value,
                    });
                }
            }
        }
        save(app, band, day, &metrics)?;
        for (command, metric, enabled) in &mut extra {
            if !*enabled {
                continue;
            }
            ensure!(today() == date, "The day changed. Sync again.");
            let reply = link
                .request(*command, &[offset], Duration::from_secs(2))
                .await?;
            ensure!(today() == date, "The day changed. Sync again.");
            *enabled = reply.answered();
            save(app, band, day, &[(*metric, slot_intervals(reply.frames()))])?;
        }
    }
    let mut messages = Vec::new();
    if missing_heart < days || missing_steps < days {
        app.band().last_sync = Some(Local::now().timestamp_millis());
    }
    if missing_heart == days && missing_steps == days {
        messages.push("No heart-rate or step history reply.");
    } else {
        if missing_heart > 0 {
            messages.push("Some heart-rate history didn’t reply.");
        }
        if missing_steps > 0 {
            messages.push("Some step history didn’t reply.");
        }
    }
    if features.extra_metrics
        && link.has_details()
        && let Some(username) = username
    {
        link.detail_login(&username).await?;
        ensure!(today() == date, "The day changed. Sync again.");
        let range = if days == 1 { 0 } else { 255 };
        if let Some(nights) = link
            .detail(details::SLEEP, &[range, 1], |body| {
                details::nights(body, date)
            })
            .await?
        {
            if app.db().save_sleep(band, &nights)? {
                app.changed();
            }
        } else {
            messages.push("Sleep history didn’t reply.");
        }
        for offset in 0..days {
            ensure!(today() == date, "The day changed. Sync again.");
            let Some(values) = link
                .detail(details::OXYGEN, &[offset.wrapping_neg()], |body| {
                    details::oxygen(body, offset)
                })
                .await?
            else {
                messages.push("Some blood oxygen history didn’t reply.");
                break;
            };
            ensure!(today() == date, "The day changed. Sync again.");
            save(
                app,
                band,
                date - Days::new(u64::from(offset)),
                &[(Metric::Oxygen, values)],
            )?;
        }
    }
    if messages.is_empty() {
        Ok(None)
    } else {
        Ok(Some(format!("{} Sync again.", messages.join(" "))))
    }
}

fn save(app: &App, band: Uuid, day: NaiveDate, metrics: &[(Metric, Vec<Interval>)]) -> Result<()> {
    if app.db().save_intervals(band, day, metrics)? {
        app.changed();
    }
    Ok(())
}
