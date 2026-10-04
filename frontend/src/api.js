// Thin wrapper around the backend REST API (proxied to :8080 by Vite in development).

async function request(path, options = {}) {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  const body = await res.json().catch(() => null)
  if (!res.ok) {
    throw new Error(body?.detail || body?.message || `Request failed (${res.status})`)
  }
  return body
}

export const api = {
  ask: (question) => request('/chat', { method: 'POST', body: JSON.stringify({ question }) }),
  overview: () => request('/stats/overview'),
  recentMatches: (limit = 10) => request(`/matches?limit=${limit}`),
  searchPlayers: (q) => request(`/players?q=${encodeURIComponent(q)}`),
  playerSummary: (id) => request(`/players/${id}/summary`),
  playerForm: (id, limit = 20) => request(`/players/${id}/form?limit=${limit}`),
  fixtures: (limit = 12) => request(`/fixtures?limit=${limit}`),
  teams: () => request('/teams'),
  teamSeasons: (id) => request(`/teams/${id}/seasons`),
  venues: () => request('/venues'),
  fantasy: ({ team1, team2, venue, explain }) =>
    request(
      `/fantasy/suggest?team1=${team1}&team2=${team2}${venue ? `&venue=${venue}` : ''}&explain=${explain}`,
    ),
}
