import { byId, displayValue, feedback, item, metrics, minuteLabel, number, text } from '../format.js';

const canvas = byId('plot');
const stagesCanvas = byId('sleep-timeline');
let chart;
let sleep;
const buttons = new Map();
const stageStyle = {
  light: ['Light', '#7b9aab'], deep: ['Deep', '#3c5476'], rem: ['REM', '#89749d'], awake: ['Awake', '#ab7d47'],
};

export function mountHealth(onMetric) {
  for (const [key, metric] of Object.entries(metrics)) {
    const button = item('button', null, 'metric');
    button.type = 'button';
    const value = item('span', '—', 'metric-value');
    const unit = item('span', metric.unit, 'metric-unit');
    button.append(item('span', metric.name, 'metric-label'), value, unit);
    button.addEventListener('click', () => onMetric(key));
    byId('metrics').append(button);
    buttons.set(key, { button, value, unit });
  }
  new ResizeObserver(drawChart).observe(canvas);
  new ResizeObserver(drawSleep).observe(stagesCanvas);
}

export function renderHealth({ summary, selected, showExtras, loading, sleep: night }) {
  for (const [key, metric] of Object.entries(summary)) {
    const { button, value, unit } = buttons.get(key);
    button.hidden = metric.extra && !showExtras;
    if (button.getAttribute('aria-pressed') !== String(key === selected)) button.setAttribute('aria-pressed', key === selected);
    text(value, metric.value == null ? '—' : number(metric.value));
    unit.hidden = metric.value == null;
  }
  const metric = summary[selected];
  chart = metric;
  drawChart();
  feedback(byId('no-readings'), loading ? 'Loading readings…' : metric.rows.length ? null : 'No readings for this day.');
  const rows = [...metric.rows].reverse().map(row => {
    const tr = item('tr');
    const value = number(displayValue(row.value, metric));
    tr.append(item('td', minuteLabel(row.minute)), item('td', [value, metric.unit].filter(Boolean).join(' ')), item('td', row.live ? 'Measurement' : 'Band history'));
    return tr;
  });
  byId('readings').hidden = rows.length === 0;
  text(byId('readings-title'), `Readings (${rows.length})`);
  byId('reading-rows').replaceChildren(...rows);
  byId('sleep').hidden = !showExtras;
  renderSleep(night);
}

export function renderMeasurement({ live, ready, selected, current, busy, stopSent }) {
  const button = byId('measure');
  button.hidden = !live && (!ready || !current || !metrics[selected].live);
  button.disabled = busy && (!live || stopSent);
  text(button, live ? 'Stop measurement' : 'Measure');
  const metric = live && metrics[live.metric];
  const message = live && (live.value == null ? `Measuring ${metric.name.toLowerCase()}… Hold still.` : `${metric.name} · ${live.value} ${metric.unit}`.trim());
  feedback(byId('live'), message);
}

function canvasArea(node) {
  const width = node.clientWidth;
  const height = node.clientHeight;
  if (!width || !height) return null;
  const ratio = window.devicePixelRatio || 1;
  node.width = Math.round(width * ratio);
  node.height = Math.round(height * ratio);
  const ctx = node.getContext('2d');
  ctx.scale(ratio, ratio);
  return { ctx, width, height };
}

function drawChart() {
  const area = canvasArea(canvas);
  if (!area || !chart) return;
  const { ctx, width, height } = area;
  const { rows, total, color } = chart;
  const values = rows.map(row => displayValue(row.value, chart));
  const peak = values.reduce((max, value) => Math.max(max, value), 0);
  const min = total || !values.length ? 0 : Math.max(0, Math.floor(values.reduce((x, y) => Math.min(x, y)) / 10) * 10 - 5);
  const max = chart.scale
    ? Math.max(0.004, Math.ceil(peak * 100) / 100)
    : Math.max(min + 10, Math.ceil((peak + (total ? 0 : 5)) / 10) * 10);
  const left = 38;
  const x = minute => left + minute / 1440 * (width - left - 1);
  const y = value => 8 + (height - 16) * (1 - (value - min) / (max - min));
  ctx.font = '11px system-ui';
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'right';
  for (let line = 0; line <= 4; line += 1) {
    const value = min + (max - min) / 4 * line;
    ctx.fillStyle = '#e0e4db';
    ctx.fillRect(left, Math.round(y(value)), width - left, 1);
    if (rows.length) {
      ctx.fillStyle = '#52665f';
      ctx.fillText(number(value), left - 6, y(value));
    }
  }
  const bar = Math.max(2, (width - left) / 115);
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 1.55;
  rows.forEach((row, index) => {
    const offset = x(row.minute);
    const top = y(values[index]);
    const previous = rows[index - 1];
    if (total) { ctx.fillRect(offset, top, bar, Math.max(1, y(0) - top)); return; }
    if (previous && row.minute >= previous.minute && row.minute - previous.minute <= chart.gap) {
      ctx.beginPath(); ctx.moveTo(x(previous.minute), y(values[index - 1])); ctx.lineTo(offset, top); ctx.stroke();
    }
    ctx.beginPath(); ctx.arc(offset, top, row.live ? 3.4 : 2.1, 0, Math.PI * 2); ctx.fill();
  });
}

function renderSleep(night) {
  sleep = night;
  const totals = new Map();
  for (const row of sleep?.stages ?? []) totals.set(row.stage, (totals.get(row.stage) ?? 0) + row.minutes);
  const resting = [...totals.entries()].reduce((sum, [stage, time]) => stage === 'awake' ? sum : sum + time, 0);
  text(byId('sleep-duration'), sleep ? `${Math.floor(resting / 60)}h ${resting % 60}m` : '');
  text(byId('sleep-summary'), sleep ? `Started at ${minuteLabel(sleep.start)}` : 'No sleep record for this day.');
  stagesCanvas.hidden = !sleep;
  byId('sleep-stages').replaceChildren(...Object.entries(stageStyle).filter(([key]) => totals.has(key))
    .map(([key, [name]]) => item('li', `${name} · ${totals.get(key)} min`, key)));
  drawSleep();
}

function drawSleep() {
  if (!sleep) return;
  const area = canvasArea(stagesCanvas);
  if (!area) return;
  const { ctx, width, height } = area;
  const total = sleep.stages.reduce((sum, stage) => sum + stage.minutes, 0);
  let left = 0;
  for (const row of sleep.stages) {
    const span = row.minutes / total * width;
    ctx.fillStyle = stageStyle[row.stage][1];
    ctx.fillRect(left, 0, span + 0.2, height);
    left += span;
  }
}
