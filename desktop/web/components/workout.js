import { byId, elapsed, item, number, text, timestamp } from '../format.js';

let activityNames = new Map();
let active = null;
let synchronizedAt = 0;

export function mountWorkout({ start, stop }) {
  byId('workout-start').onsubmit = event => {
    event.preventDefault();
    start({ activity: byId('activity').value, kg: byId('weight').valueAsNumber });
  };
  byId('workout-stop').onclick = stop;
  setInterval(drawClock, 1000);
}

export function renderWorkout(state, pending) {
  if (activityNames.size === 0) {
    activityNames = new Map(state.activities.map(activity => [activity.id, activity.name]));
    byId('activity').replaceChildren(...state.activities.map(activity => {
      const option = item('option', activity.name);
      option.value = activity.id;
      return option;
    }));
  }
  byId('workouts').hidden = !state.features.workouts;
  const session = state.active_workout;
  if (active?.started_at !== session?.started_at || active?.duration_seconds !== session?.duration_seconds) {
    active = session;
    synchronizedAt = performance.now();
  }
  byId('workout-start').hidden = Boolean(active);
  byId('workout-start').querySelector('button').disabled = pending;
  byId('workout-active').hidden = !active;
  byId('workout-stop').disabled = pending;
  if (active) {
    text(byId('active-activity'), activityNames.get(active.activity));
    text(byId('workout-estimate'), `${number(active.estimated_kcal)} kcal estimated`);
    drawClock();
  }
}

export function renderWorkoutHistory(workouts, loading) {
  byId('no-workouts').hidden = loading || workouts.length > 0 || Boolean(active);
  byId('workout-history').replaceChildren(...workouts.map(workout => {
    const row = item('li');
    row.append(item('strong', activityNames.get(workout.activity)),
      item('span', `${timestamp(workout.started_at)} · ${elapsed(workout.duration_seconds)} · ${number(workout.estimated_kcal)} kcal est.`, 'secondary'));
    return row;
  }));
}

function drawClock() {
  if (active) text(byId('workout-clock'), elapsed(active.duration_seconds + Math.floor((performance.now() - synchronizedAt) / 1000)));
}
