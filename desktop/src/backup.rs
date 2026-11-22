use crate::{App, model::Device, store::backup::Checkpoint};
use anyhow::{Context, Result, ensure};
use openh_k50_protocol::backup::{PAGE, Reading, Request, Response, StoreChanged, is_hex};
use reqwest::{Client, StatusCode, Url};
use std::{env, fs, path::PathBuf, time::Duration};
use tokio::time::Instant;
use uuid::Uuid;

pub struct Backup {
    endpoint: Url,
    key: String,
    device: Uuid,
    mac: String,
    scope: String,
    client: Client,
}

impl Backup {
    pub fn from_env() -> Result<Option<Self>> {
        let Some(endpoint) = env::var_os("OPENHK50_BACKUP_URL") else {
            return Ok(None);
        };
        let mut endpoint = Url::parse(endpoint.to_str().context("Invalid backup URL")?)
            .context("Invalid backup URL")?;
        let loopback = matches!(endpoint.host_str(), Some("127.0.0.1" | "[::1]"));
        ensure!(
            (endpoint.scheme() == "https" || (loopback && endpoint.scheme() == "http"))
                && endpoint.username().is_empty()
                && endpoint.password().is_none()
                && endpoint.path() == "/"
                && endpoint.fragment().is_none()
                && endpoint.query().is_none()
                && endpoint.host().is_some(),
            "Use an HTTPS origin or an HTTP loopback port forward"
        );
        endpoint.set_path("/v1/exchange");
        let key_file =
            env::var_os("OPENHK50_BACKUP_KEY_FILE").context("Set OPENHK50_BACKUP_KEY_FILE")?;
        let key = fs::read_to_string(PathBuf::from(key_file)).context("Read backup key file")?;
        let key = key.trim().to_owned();
        ensure!(
            is_hex(&key, 64),
            "Backup key must contain 64 lowercase hexadecimal characters"
        );
        let device = env::var("OPENHK50_BACKUP_DESKTOP_ID")
            .context("Set OPENHK50_BACKUP_DESKTOP_ID")?
            .parse()
            .context("Invalid desktop band ID")?;
        let mac = env::var("OPENHK50_BACKUP_BAND_MAC")
            .context("Set OPENHK50_BACKUP_BAND_MAC")?
            .to_ascii_uppercase();
        ensure!(
            mac.len() == 17
                && mac.split(':').count() == 6
                && mac
                    .split(':')
                    .all(|part| part.len() == 2 && part.bytes().all(|b| b.is_ascii_hexdigit())),
            "Invalid Android band MAC"
        );
        let scope = format!("{endpoint}|{device}|{mac}");
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(6))
            .timeout(Duration::from_secs(12))
            .build()?;
        Ok(Some(Self {
            endpoint,
            key,
            device,
            mac,
            scope,
            client,
        }))
    }

    pub fn matches(&self, device: &Device) -> bool {
        self.device == device.id
    }

    pub async fn sync(&self, app: &App) -> Result<&'static str> {
        let until = Instant::now() + Duration::from_secs(60);
        let mut reset = false;
        while Instant::now() < until {
            let request = {
                let db = app.db();
                let checkpoint = db.checkpoint(&self.scope)?;
                match checkpoint {
                    Some(checkpoint) => Request {
                        store: Some(checkpoint.store),
                        after: checkpoint.cursor,
                        readings: db.pending(&self.scope, self.device, &self.mac)?,
                    },
                    None => Request {
                        store: None,
                        after: 0,
                        readings: Vec::new(),
                    },
                }
            };
            let mut reply = self
                .client
                .post(self.endpoint.clone())
                .bearer_auth(&self.key)
                .json(&request)
                .send()
                .await
                .context("Backup server unreachable. Check the server or port forward.")?;
            let status = reply.status();
            match status {
                StatusCode::OK | StatusCode::CONFLICT => {}
                StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
                    anyhow::bail!("Backup key rejected.")
                }
                StatusCode::NOT_FOUND => {
                    anyhow::bail!("Backup endpoint missing. Check the server address.")
                }
                _ => anyhow::bail!("Backup returned HTTP {}.", status.as_u16()),
            }
            let mut body = Vec::new();
            while let Some(bytes) = reply
                .chunk()
                .await
                .context("Backup response interrupted. Try again.")?
            {
                ensure!(
                    body.len() + bytes.len() <= 512 * 1024,
                    "Backup response exceeds its limit."
                );
                body.extend_from_slice(&bytes);
            }
            if status == StatusCode::CONFLICT {
                let response: StoreChanged =
                    serde_json::from_slice(&body).context("Invalid backup response.")?;
                ensure!(
                    !reset && is_hex(&response.store, 32),
                    "Backup storage changed again. Retry backup."
                );
                app.db().reset_backup(&self.scope, &response.store)?;
                reset = true;
                continue;
            }
            let response: Response =
                serde_json::from_slice(&body).context("Invalid backup response.")?;
            ensure!(
                request.store.as_deref() == Some(&response.store)
                    && response.cursor >= request.after
                    && (!response.more || response.cursor > request.after)
                    && response.readings.len() <= PAGE
                    && response.readings.iter().all(Reading::valid)
                    && response.accepted.len() == request.readings.len()
                    && response.accepted.iter().zip(&request.readings).all(
                        |(remote, local)| remote.valid()
                            && remote.band == local.band
                            && remote.kind == local.kind
                            && remote.origin == local.origin
                            && remote.slot == local.slot
                            && (remote.revised_at, &remote.revision)
                                >= (local.revised_at, &local.revision)
                    ),
                "Invalid backup response."
            );
            let changed = app.db().apply_page(
                &self.scope,
                self.device,
                &self.mac,
                Checkpoint {
                    store: response.store,
                    cursor: response.cursor,
                },
                response.readings.into_iter().chain(response.accepted),
            )?;
            if changed {
                app.changed();
            }
            if !response.more && request.readings.len() < PAGE {
                return Ok("Backup complete.");
            }
        }
        Ok("Backup paused. Sync again to continue.")
    }
}
