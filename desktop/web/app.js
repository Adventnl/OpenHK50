import { api } from './api.js';
import { byId, dateLabel, feedback, metrics, shiftDay, summarize, text } from './format.js';
import { mountBand, renderBand } from './components/band.js';
import { mountHealth, renderHealth, renderMeasurement } from './components/health.js';
import { mountWorkout, renderWorkout, renderWorkoutHistory } from './components/workout.js';

let state;
let date;
let current = 'heart';
let view;
let viewKey = '';
let pending = false;
let refreshing = false;
let rerun = false;
let timer;
let error;
let stopSent = false;
let lastHealth = [];
let lastWorkouts = [];
const blank = { readings: [], sleep: null, workouts: [] };

const runCommand = command => mutate('band', command);
mountHealth(metric => { current = metric; render(); });
mountBand({
  runCommand,
  updateFeatures: (feature, enabled) => mutate('features', { feature, enabled }),
  updateDetails: username => mutate('details', { username }).then(success => {
    if (success) byId('username').value = '';
  }),
  backup: () => mutate('backup'),
  getDevice: () => state?.band.device,
});
mountWorkout({
  start: data => mutate('workout/start', data),
  stop: () => mutate('workout/stop'),
});

byId('date').onchange = event => { if (event.target.value) chooseDay(event.target.value); };
byId('previous-day').onclick = () => { if (date) chooseDay(shiftDay(date, -1)); };
byId('next-day').onclick = () => { if (date) chooseDay(shiftDay(date, 1)); };
byId('today').onclick = () => chooseDay(state.today);
byId('measure').onclick = () => {
  if (state?.band.live) {
    stopSent = true;
    mutate('band/stop').then(success => { if (!success) { stopSent = false; render(); } });
  } else {
    runCommand({ action: 'measure', metric: current });
  }
};
document.addEventListener('visibilitychange', () => {
  clearTimeout(timer);
  if (!document.hidden) refresh();
});
refresh();

function chooseDay(value) {
  if (!state || value > state.today || date === value) return;
  date = value;
  view = undefined;
  viewKey = '';
  render();
  refresh();
}

async function mutate(path, body) {
  if (pending) return false;
  pending = true;
  error = undefined;
  render();
  try {
    await api.post(path, body);
    return true;
  } catch (failure) {
    error = failure.message;
    return false;
  } finally {
    pending = false;
    await refresh();
  }
}

async function refresh() {
  clearTimeout(timer);
  if (refreshing) { rerun = true; return; }
  refreshing = true;
  try {
    const next = await api.status();
    if (date == null || date === state?.today || date > next.today) {
      if (date !== next.today) view = undefined;
      date = next.today;
    }
    if (state?.band.device?.id !== next.band.device?.id) view = undefined;
    state = next;
    if (!state.band.live) stopSent = false;
    const from = date;
    const key = `${date}/${state.revision}`;
    if (key !== viewKey) {
      const result = await api.day(from);
      if (date === from) { view = result; viewKey = key; }
    }
    if (error === 'Dashboard service unavailable. Check the terminal.') error = undefined;
  } catch {
    error = 'Dashboard service unavailable. Check the terminal.';
  } finally {
    refreshing = false;
    render();
    if (rerun) { rerun = false; refresh(); }
    else if (!document.hidden) timer = setTimeout(refresh, state?.band.operation || state?.backup.running ? 800 : state?.active_workout ? 2500 : 5000);
  }
}

function render() {
  feedback(byId('app-error'), error);
  if (!state) return;
  if (!state.features.extra_metrics && metrics[current].extra) current = 'heart';
  const isToday = date === state.today;
  text(byId('day-title'), isToday ? 'Today' : 'History');
  text(byId('day-label'), dateLabel(date));
  const input = byId('date');
  input.max = state.today;
  if (input.value !== date) input.value = date;
  byId('next-day').disabled = isToday;
  byId('today').hidden = isToday;

  const showExtras = state.features.extra_metrics;
  if (lastHealth[0] !== view || lastHealth[1] !== current || lastHealth[2] !== showExtras) {
    const data = view ?? blank;
    renderHealth({ summary: summarize(data.readings), selected: current, sleep: data.sleep, showExtras, loading: !view });
    lastHealth = [view, current, showExtras];
  }
  renderBand(state, pending);
  renderMeasurement({
    live: state.band.live, ready: state.band.connected, selected: current,
    current: isToday, busy: pending || state.band.operation != null || stopSent, stopSent,
  });
  renderWorkout(state, pending);
  if (lastWorkouts[0] !== view || lastWorkouts[1] !== state.active_workout?.started_at) {
    renderWorkoutHistory((view ?? blank).workouts, !view);
    lastWorkouts = [view, state.active_workout?.started_at];
  }
}
