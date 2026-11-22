async function request(path, options) {
  const response = await fetch(`/api/${path}`, {
    ...options,
    signal: AbortSignal.timeout(5000),
    cache: 'no-store',
  });
  if (!response.ok) {
    const details = await response.json().catch(() => null);
    throw new Error(details?.message ?? `Request failed (HTTP ${response.status}). Try again.`);
  }
  return response.headers.get('Content-Type')?.includes('application/json') ? response.json() : undefined;
}

export const api = {
  status: () => request('status'),
  day: date => request(`day?date=${encodeURIComponent(date)}`),
  post: (path, payload = {}) => request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-OpenHK50-Local': '1' },
    body: JSON.stringify(payload),
  }),
};
