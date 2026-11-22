use crate::model::Device;
use anyhow::{Context, Result, anyhow, bail, ensure};
use btleplug::{
    api::{
        Central, CentralState, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
        ScanFilter, ValueNotification, WriteType,
    },
    platform::{Adapter, Manager, Peripheral},
};
use futures_util::{FutureExt, Stream, StreamExt};
use std::{future::Future, pin::Pin, time::Duration};
use tokio::time::{Instant, sleep, timeout, timeout_at};
use uuid::{Uuid, uuid};

const SERVICE: Uuid = uuid!("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e");
const WRITE: Uuid = uuid!("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
const NOTIFY: Uuid = uuid!("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
const DETAIL_WRITE: Uuid = uuid!("de5bf72a-d711-4e47-af26-65e3012a5dc7");
const DETAIL_NOTIFY: Uuid = uuid!("de5bf729-d711-4e47-af26-65e3012a5dc7");
const IMMEDIATE_ALERT: Uuid = uuid!("00001802-0000-1000-8000-00805f9b34fb");
const ALERT_LEVEL: Uuid = uuid!("00002a06-0000-1000-8000-00805f9b34fb");

pub struct Radio {
    adapter: Option<Adapter>,
    candidates: Vec<(Device, Peripheral)>,
    pub link: Option<Link>,
}

impl Radio {
    pub fn new() -> Self {
        Self {
            adapter: None,
            candidates: Vec::new(),
            link: None,
        }
    }

    async fn adapter(&mut self) -> Result<Adapter> {
        if let Some(adapter) = &self.adapter {
            return Ok(adapter.clone());
        }
        let adapter = bounded(async {
            let manager = Manager::new().await?;
            manager.adapters().await?.into_iter().next().ok_or_else(|| {
                anyhow!("Bluetooth adapter unavailable. Check macOS Bluetooth permissions.")
            })
        })
        .await?;
        self.adapter = Some(adapter.clone());
        Ok(adapter)
    }

    pub async fn scan(&mut self) -> Result<Vec<Device>> {
        ensure!(
            self.link.is_none(),
            "Disconnect the current band before scanning."
        );
        self.candidates.clear();
        let adapter = self.adapter().await?;
        let state = bounded(async { Ok(adapter.adapter_state().await?) }).await?;
        if state != CentralState::PoweredOn {
            self.adapter = None;
            bail!(
                "Turn on Bluetooth and allow your terminal in macOS Bluetooth privacy settings. Then scan again."
            );
        }
        bounded(async {
            adapter.clear_peripherals().await?;
            Ok(adapter.start_scan(ScanFilter::default()).await?)
        })
        .await?;
        sleep(Duration::from_secs(5)).await;
        bounded(async { Ok(adapter.stop_scan().await?) }).await?;
        let peripherals = bounded(async { Ok(adapter.peripherals().await?) }).await?;
        for peripheral in peripherals {
            let Ok(Ok(Some(properties))) =
                timeout(Duration::from_millis(300), peripheral.properties()).await
            else {
                continue;
            };
            let name = properties.local_name.unwrap_or_default();
            let upper = name.to_ascii_uppercase();
            if !upper.starts_with("HK50")
                && !upper.starts_with("H59")
                && !properties.services.contains(&SERVICE)
            {
                continue;
            }
            let id = peripheral
                .id()
                .to_string()
                .parse()
                .context("Couldn’t read the band’s macOS Bluetooth ID.")?;
            let name = if name.is_empty() {
                "OpenHK50 band".to_owned()
            } else {
                name
            };
            self.candidates.push((Device { id, name }, peripheral));
            if self.candidates.len() == 24 {
                break;
            }
        }
        self.candidates
            .sort_by(|a, b| a.0.name.cmp(&b.0.name).then(a.0.id.cmp(&b.0.id)));
        Ok(self
            .candidates
            .iter()
            .map(|(device, _)| device.clone())
            .collect())
    }

    pub async fn connect(&mut self, id: Uuid) -> Result<Device> {
        ensure!(
            self.link.is_none(),
            "Disconnect the current band before connecting."
        );
        if !self.candidates.iter().any(|(device, _)| device.id == id) {
            self.scan().await?;
        }
        let (device, peripheral) = self.candidates.iter().find(|(device, _)| device.id == id)
            .cloned().context("Band unavailable. Bring it closer and release it from other apps, then scan again.")?;
        let result = timeout(Duration::from_secs(15), Link::connect(peripheral.clone())).await;
        match result {
            Ok(Ok(link)) => {
                self.link = Some(link);
                Ok(device)
            }
            error => {
                let _ = timeout(Duration::from_secs(2), peripheral.disconnect()).await;
                match error {
                    Ok(Err(error)) => Err(error),
                    _ => Err(anyhow!(
                        "Connection timed out. Bring the band closer and try again."
                    )),
                }
            }
        }
    }

    pub async fn disconnect(&mut self) -> Result<()> {
        if let Some(link) = self.link.take() {
            bounded(async { Ok(link.peripheral.disconnect().await?) }).await?;
        }
        Ok(())
    }
}

pub struct Link {
    peripheral: Peripheral,
    incoming: Pin<Box<dyn Stream<Item = ValueNotification> + Send>>,
    uart: Characteristic,
    quality: Option<(Characteristic, Characteristic)>,
    alert: Option<Characteristic>,
    detail_subscribed: bool,
}

impl Link {
    async fn connect(peripheral: Peripheral) -> Result<Self> {
        peripheral
            .connect()
            .await
            .context("Couldn’t connect to the band. Release it from other apps and retry.")?;
        peripheral
            .discover_services()
            .await
            .context("Couldn’t discover band services. Reconnect and try again.")?;
        let chars = peripheral.characteristics();
        let uart = chars
            .iter()
            .find(|c| c.service_uuid == SERVICE && c.uuid == WRITE && writable(c))
            .cloned()
            .context("Band command channel missing.")?;
        let notify = chars
            .iter()
            .find(|c| c.service_uuid == SERVICE && c.uuid == NOTIFY && notifies(c))
            .context("Band reply channel missing.")?;
        let incoming = peripheral.notifications().await?;
        peripheral
            .subscribe(notify)
            .await
            .context("Couldn’t subscribe to band replies. Reconnect and retry.")?;
        let quality = chars
            .iter()
            .find(|c| c.uuid == DETAIL_WRITE && writable(c))
            .zip(
                chars
                    .iter()
                    .find(|c| c.uuid == DETAIL_NOTIFY && notifies(c)),
            )
            .map(|(tx, rx)| (tx.clone(), rx.clone()));
        let alert = chars
            .iter()
            .find(|c| {
                c.service_uuid == IMMEDIATE_ALERT
                    && c.uuid == ALERT_LEVEL
                    && c.properties.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE)
            })
            .cloned();
        Ok(Self {
            peripheral,
            incoming,
            uart,
            quality,
            alert,
            detail_subscribed: false,
        })
    }

    pub fn has_details(&self) -> bool {
        self.quality.is_some()
    }

    pub fn has_alert(&self) -> bool {
        self.alert.is_some()
    }

    pub async fn is_connected(&mut self) -> bool {
        matches!(
            timeout(Duration::from_secs(2), self.peripheral.is_connected()).await,
            Ok(Ok(true))
        )
    }

    pub async fn write_uart(&mut self, bytes: &[u8]) -> Result<()> {
        self.write(&self.uart.clone(), bytes).await
    }

    pub async fn subscribe_details(&mut self) -> Result<()> {
        let (_, rx) = self
            .quality
            .as_ref()
            .context("Sleep and oxygen history are unavailable on this band.")?;
        if !self.detail_subscribed {
            bounded(async { Ok(self.peripheral.subscribe(rx).await?) }).await?;
            self.detail_subscribed = true;
        }
        Ok(())
    }

    pub async fn write_detail(&mut self, bytes: &[u8]) -> Result<()> {
        let (tx, _) = self
            .quality
            .as_ref()
            .context("Sleep and oxygen history are unavailable on this band.")?;
        self.write(&tx.clone(), bytes).await
    }

    pub async fn alert(&mut self) -> Result<()> {
        let alert = self.alert.clone().context("Band alerts are unavailable.")?;
        self.write(&alert, &[1]).await?;
        sleep(Duration::from_millis(700)).await;
        self.write(&alert, &[0]).await
    }

    async fn write(&mut self, target: &Characteristic, bytes: &[u8]) -> Result<()> {
        let mode = if target
            .properties
            .contains(CharPropFlags::WRITE_WITHOUT_RESPONSE)
        {
            WriteType::WithoutResponse
        } else {
            WriteType::WithResponse
        };
        bounded(async { Ok(self.peripheral.write(target, bytes, mode).await?) })
            .await
            .context("Couldn’t send to the band. Reconnect and try again.")
    }

    pub fn drain(&mut self) {
        for _ in 0..128 {
            if !matches!(self.incoming.next().now_or_never(), Some(Some(_))) {
                break;
            }
        }
    }

    pub async fn packet(&mut self, channel: Channel, deadline: Instant) -> Result<Option<Vec<u8>>> {
        let uuid = match channel {
            Channel::Uart => NOTIFY,
            Channel::Detail => DETAIL_NOTIFY,
        };
        loop {
            match timeout_at(deadline, self.incoming.next()).await {
                Ok(Some(item)) if item.uuid == uuid => return Ok(Some(item.value)),
                Ok(Some(_)) => continue,
                Ok(None) => bail!("Band connection lost. Reconnect and try again."),
                Err(_) => return Ok(None),
            }
        }
    }
}

#[derive(Clone, Copy)]
pub enum Channel {
    Uart,
    Detail,
}

fn writable(c: &Characteristic) -> bool {
    c.properties
        .intersects(CharPropFlags::WRITE | CharPropFlags::WRITE_WITHOUT_RESPONSE)
}

fn notifies(c: &Characteristic) -> bool {
    c.properties
        .intersects(CharPropFlags::NOTIFY | CharPropFlags::INDICATE)
}

async fn bounded<T>(future: impl Future<Output = Result<T>>) -> Result<T> {
    timeout(Duration::from_secs(6), future)
        .await
        .context("Bluetooth timed out. Check band range and macOS Bluetooth settings.")?
}
