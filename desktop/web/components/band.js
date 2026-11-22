import { byId, feedback, item, text, timestamp } from '../format.js';

const switches = {
  'auto-sync': 'auto_sync', 'show-workouts': 'workouts',
  'show-notifications': 'notifications', 'show-extras': 'extra_metrics',
};
const operationLabel = {
  scan: 'Scanning…', connect: 'Connecting…', disconnect: 'Disconnecting…',
  sync: 'Syncing band…', measure: 'Measurement running…', logging: 'Updating logging…',
  clock: 'Setting clock…',
};
const bandCommands = {
  scan: { action: 'scan' }, disconnect: { action: 'disconnect' },
  sync: { action: 'sync', history: false }, 'sync-week': { action: 'sync', history: true },
  clock: { action: 'clock' }, 'logging-on': { action: 'logging', enabled: true },
  'logging-off': { action: 'logging', enabled: false }, 'band-alert': { action: 'alert' },
};
let displayedDevices = '';

export function mountBand({ runCommand, updateFeatures, updateDetails, backup, getDevice }) {
  for (const [id, command] of Object.entries(bandCommands)) {
    byId(id).onclick = () => runCommand(command);
  }
  byId('reconnect').onclick = () => {
    const device = getDevice();
    if (device) runCommand({ action: 'connect', id: device.id });
  };
  for (const [id, key] of Object.entries(switches)) {
    const control = byId(id);
    control.onchange = () => updateFeatures(key, control.checked);
  }
  byId('details-form').onsubmit = event => {
    event.preventDefault();
    updateDetails(byId('username').value);
  };
  byId('details-off').onclick = () => updateDetails(null);
  byId('backup').onclick = backup;
  byId('copy-id').onclick = async () => {
    const device = getDevice();
    if (!device) return;
    const status = byId('copy-feedback');
    try {
      await navigator.clipboard.writeText(device.id);
      feedback(status, 'Bluetooth ID copied.');
    } catch {
      feedback(status, device.id);
    }
  };
  byId('devices').addEventListener('click', event => {
    const button = event.target.closest('button[data-id]');
    if (button) runCommand({ action: 'connect', id: button.dataset.id });
  });
}

export function renderBand(state, pending) {
  const { band, features, backup } = state;
  const busy = pending || band.operation != null;
  const indicator = byId('connection-indicator');
  text(indicator, band.connected ? 'Connected' : 'Disconnected');
  indicator.classList.toggle('connected', band.connected);
  text(byId('device-name'), band.device?.name ?? 'No band selected');
  const battery = band.battery == null ? null : `${band.battery}% battery${band.charging ? ' · Charging' : ''}`;
  const lastSync = band.last_sync == null ? null : `Synced ${timestamp(band.last_sync)}`;
  const instructions = band.connected ? [battery, lastSync].filter(Boolean).join(' · ')
    : band.device ? 'Reconnect or scan for another band.' : 'Scan for a nearby HK50 or H59.';
  text(byId('band-detail'), instructions);
  byId('band-detail').hidden = !instructions;
  byId('band-disconnected').hidden = band.connected;
  byId('reconnect').hidden = !band.device;
  byId('band-connected').hidden = !band.connected;
  byId('band-controls').hidden = !band.connected;
  byId('copy-id').hidden = !band.device;
  byId('ble-details').hidden = !features.extra_metrics || !band.supports_details;
  byId('details-form').hidden = state.details_enabled;
  byId('details-off').hidden = !state.details_enabled;
  byId('notifications').hidden = !features.notifications;
  for (const id of [...Object.keys(bandCommands), 'reconnect', 'details-off']) byId(id).disabled = busy;
  byId('band-alert').disabled = busy || !band.connected || !band.supports_alert;
  const alertAvailability = !band.connected ? 'Connect your band to test vibration.'
    : !band.supports_alert ? 'Vibration test unavailable for this band.' : null;
  feedback(byId('notification-feedback'), band.operation === 'alert' ? 'Sending vibration command…'
    : band.alert_message ?? alertAvailability);
  byId('details-form').querySelector('button').disabled = busy;
  for (const [id, key] of Object.entries(switches)) {
    byId(id).checked = features[key];
    byId(id).disabled = pending || (key === 'workouts' && state.active_workout != null);
  }

  const signature = JSON.stringify(band.nearby);
  if (signature !== displayedDevices) {
    displayedDevices = signature;
    byId('devices').replaceChildren(...band.nearby.map(device => {
      const li = item('li');
      const button = item('button', `Connect ${device.name}`);
      button.type = 'button';
      button.dataset.id = device.id;
      li.append(button);
      return li;
    }));
  }
  byId('devices').hidden = band.connected;
  for (const button of byId('devices').querySelectorAll('button')) button.disabled = busy;
  feedback(byId('band-feedback'), operationLabel[band.operation] ?? band.message);
  text(byId('backup-feedback'), backup.running ? 'Syncing backup…' : backup.message
    ?? (state.backup_available ? 'Ready to sync.' : state.backup_configured ? 'Select the band linked to backup.' : 'Backup is off.'));
  byId('backup').hidden = !state.backup_available;
  byId('backup').disabled = pending || backup.running;
}
