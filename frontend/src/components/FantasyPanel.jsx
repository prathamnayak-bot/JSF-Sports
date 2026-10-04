import { useEffect, useState } from 'react'
import { api } from '../api'
import Fixtures from './Fixtures'

const ROLE_ORDER = ['WICKET_KEEPER', 'BATTER', 'ALL_ROUNDER', 'BOWLER']
const ROLE_LABEL = {
  WICKET_KEEPER: 'Wicket-keepers',
  BATTER: 'Batters',
  ALL_ROUNDER: 'All-rounders',
  BOWLER: 'Bowlers',
}

function PickRow({ pick, badge }) {
  return (
    <li className="pick">
      <div>
        <strong>{pick.name}</strong> {badge && <span className="badge">{badge}</span>}
        <div className="muted small">
          {pick.team} · form {pick.form}
          {pick.venueAvg != null && ` · at venue ${pick.venueAvg}`}
          {pick.opponentAvg != null && ` · vs opp ${pick.opponentAvg}`}
        </div>
      </div>
      <span className="points">{pick.projected}</span>
    </li>
  )
}

export default function FantasyPanel() {
  const [teams, setTeams] = useState([])
  const [venues, setVenues] = useState([])
  const [form, setForm] = useState({ team1: '', team2: '', venue: '', explain: true })
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    Promise.all([api.teams(), api.venues()])
      .then(([t, v]) => {
        setTeams(t)
        setVenues(v)
      })
      .catch((e) => setError(e.message))
  }, [])

  const set = (key) => (e) =>
    setForm((f) => ({ ...f, [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }))

  async function suggest(e, picked = form) {
    e?.preventDefault()
    setLoading(true)
    setError(null)
    try {
      setResult(await api.fantasy(picked))
    } catch (err) {
      setError(err.message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  const badgeFor = (p) =>
    p.playerId === result.captainId ? 'C' : p.playerId === result.viceCaptainId ? 'VC' : null

  return (
    <section className="fantasy">
      <Fixtures
        onPick={(f) => {
          const picked = { ...form, team1: String(f.team1Id), team2: String(f.team2Id), venue: f.venueId ? String(f.venueId) : '' }
          setForm(picked)
          suggest(null, picked)
        }}
      />
      <form className="card fantasy-form" onSubmit={suggest}>
        <h2>Build a fantasy XI</h2>
        <div className="fields">
          <select value={form.team1} onChange={set('team1')} required>
            <option value="">Team 1…</option>
            {teams.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
          <select value={form.team2} onChange={set('team2')} required>
            <option value="">Team 2…</option>
            {teams.map((t) => (
              <option key={t.id} value={t.id} disabled={String(t.id) === form.team1}>{t.name}</option>
            ))}
          </select>
          <select value={form.venue} onChange={set('venue')}>
            <option value="">Venue (optional)</option>
            {venues.map((v) => (
              <option key={v.id} value={v.id}>{v.name}</option>
            ))}
          </select>
        </div>
        <label className="check">
          <input type="checkbox" checked={form.explain} onChange={set('explain')} /> Explain the picks with AI
        </label>
        <button type="submit" disabled={loading || !form.team1 || !form.team2}>
          {loading ? 'Picking…' : 'Suggest XI'}
        </button>
      </form>

      {error && <p className="error-text">{error}</p>}

      {result && (
        <>
          <div className="card">
            <h2>
              {result.team1} vs {result.team2}
              {result.venue && <span className="muted small"> · {result.venue}</span>}
            </h2>
            <p className="muted">
              Projected total (with C ×2, VC ×1.5): <strong>{result.projectedTotal}</strong> pts
            </p>
            {result.summary && <p className="summary-text">{result.summary}</p>}

            {ROLE_ORDER.map((role) => {
              const picks = result.xi.filter((p) => p.role === role)
              if (!picks.length) return null
              return (
                <div key={role}>
                  <h4 className="role-heading">{ROLE_LABEL[role]}</h4>
                  <ul className="picks">
                    {picks.map((p) => (
                      <PickRow key={p.playerId} pick={p} badge={badgeFor(p)} />
                    ))}
                  </ul>
                </div>
              )
            })}
          </div>

          {result.bench.length > 0 && (
            <div className="card">
              <h2>Bench</h2>
              <ul className="picks">
                {result.bench.map((p) => (
                  <PickRow key={p.playerId} pick={p} />
                ))}
              </ul>
            </div>
          )}

          <details className="card">
            <summary>How are points projected?</summary>
            <p className="small">{result.scoringRules}</p>
            <p className="small">
              Projected = 60% recent form (last 10 matches, recent ones weigh more) + 20% average at this venue +
              20% average against this opponent (each falls back to form with fewer than 3 matches).
            </p>
            <ul className="small">
              {result.notes.map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          </details>
        </>
      )}
    </section>
  )
}
