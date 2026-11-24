export const byId = id => document.getElementById(id);

export function text(element, value) {
  if (element.textContent !== value) element.textContent = value;
}

export function feedback(element, message) {
  element.hidden = !message;
  text(element, message ?? '');
}

export function item(tag, value, className) {
  const node = document.createElement(tag);
  if (value != null) node.textContent = value;
  if (className) node.className = className;
  return node;
}

const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeZone: 'UTC' });
const clockFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

export const number = value => numberFormat.format(value);
export const dateLabel = date => dateFormat.format(new Date(`${date}T12:00:00Z`));
export const timestamp = time => clockFormat.format(time);

export function shiftDay(date, days) {
  const shifted = new Date(`${date}T12:00:00Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return shifted.toISOString().slice(0, 10);
}

export const minuteLabel = minute => `${Math.floor(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}`;

export function elapsed(seconds) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  const remainder = (seconds % 60).toString().padStart(2, '0');
  return hours ? `${hours}:${minutes.toString().padStart(2, '0')}:${remainder}` : `${minutes}:${remainder}`;
}

export const metrics = {
  heart: { name: 'Heart rate', unit: 'bpm', color: '#a34356', live: true, gap: 25 },
  steps: { name: 'Steps', unit: '', color: '#357560', total: true },
  calories: { name: 'Band calories', unit: 'kcal', color: '#966334', total: true, scale: 0.01 },
  distance: { name: 'Distance', unit: 'km', color: '#527744', total: true, scale: 0.001 },
  oxygen: { name: 'Blood oxygen', unit: '%', color: '#317491', live: true, extra: true, gap: 65 },
  stress: { name: 'Stress', unit: '', color: '#82628c', live: true, extra: true, gap: 35 },
  hrv: { name: 'HRV', unit: 'ms', color: '#5374a1', extra: true, gap: 35 },
};

export const displayValue = (value, metric) => value * (metric.scale ?? 1);

export function summarize(readings) {
  const groups = Object.fromEntries(Object.keys(metrics).map(key => [key, []]));
  for (const row of readings) groups[row.metric]?.push(row);
  return Object.fromEntries(Object.entries(groups).map(([key, rows]) => {
    const metric = metrics[key];
    const value = metric.total
      ? rows.reduce((sum, row) => sum + row.value, 0)
      : rows.at(-1)?.value;
    return [key, { ...metric, rows, value: rows.length ? displayValue(value, metric) : null }];
  }));
}
