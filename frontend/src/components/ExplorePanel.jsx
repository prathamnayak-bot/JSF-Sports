import { useEffect, useState } from 'react'
import { api } from '../api'

function Stat({ label, value }) {
  return (
    <div className="stat">
      <span className="stat-value">{value ?? '—'}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

function PlayerSearch() {
  const [q, setQ] = useState('')
  const [results, setResults] = useState([])
  const [summary, setSummary] = useState(null)
  const [picked, setPicked] = useState(null)
  const shown = q.trim().length >= 2 && q !== picked ? results : []

  useEffect(() => {
    if (q.trim().length < 2 || q === picked) return
    const t = setTimeout(() => api.searchPlayers(q).then(setResults).catch(() => setResults([])), 250)
    return () => clearTimeout(t)
  }, [q, picked])

  return (
    <div className="card">
      <h2>Player lookup</h2>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search a player, e.g. Kohli" />
      {shown.length > 0 && (
        <ul className="results">
          {shown.map((p) => (
            <li key={p.id}>
              <button
                className="link"
                onClick={() => {
                  setPicked(p.name)
                  setQ(p.name)
                  api.playerSummary(p.id).then(setSummary)
                }}
              >
                {p.name}
              </button>
            </li>
          ))}
        </ul>
      )}
      {summary && (
        <div className="summary">
          <h3>{summary.player.name}</h3>
          <h4>Batting</h4>
          <div className="stats">
            <Stat label="Innings" value={summary.batting.innings} />
            <Stat label="Runs" value={summary.batting.runs} />
            <Stat label="Average" value={summary.batting.average} />
            <Stat label="Strike rate" value={summary.batting.strikeRate} />
            <Stat label="4s / 6s" value={`${summary.batting.fours} / ${summary.batting.sixes}`} />
          </div>
          <h4>Bowling</h4>
          <div className="stats">
            <Stat label="Wickets" value={summary.bowling.wickets} />
            <Stat label="Economy" value={summary.bowling.economy} />
            <Stat label="Average" value={summary.bowling.average} />
            <Stat label="Balls" value={summary.bowling.ballsBowled} />
          </div>
        </div>
      )}
    </div>
  )
}

export default function ExplorePanel() {
  const [overview, setOverview] = useState(null)
  const [matches, setMatches] = useState([])
  const [error, setError] = useState(null)

  useEffect(() => {
    Promise.all([api.overview(), api.recentMatches(10)])
      .then(([o, m]) => {
        setOverview(o)
        setMatches(m)
      })
      .catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="error-text">Could not reach the backend: {error}</p>

  return (
    <section className="explore">
      <div className="stats">
        <Stat label="Matches" value={overview?.matches} />
        <Stat label="Teams" value={overview?.teams} />
        <Stat label="Players" value={overview?.players} />
        <Stat label="Venues" value={overview?.venues} />
        <Stat label="Balls recorded" value={overview?.deliveries?.toLocaleString()} />
      </div>

      {overview?.matches === 0 && (
        <p className="muted">No data yet — import a Cricsheet download (see the README).</p>
      )}

      <PlayerSearch />

      <div className="card">
        <h2>Recent matches</h2>
        <ul className="matches">
          {matches.map((m) => (
            <li key={m.id}>
              <span className="muted">{m.date} · {m.format}</span>
              <strong>
                {m.team1?.name} vs {m.team2?.name}
              </strong>
              <span>{m.result}</span>
              <span className="muted">{m.venue}</span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
