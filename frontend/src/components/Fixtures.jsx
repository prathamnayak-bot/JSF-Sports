import { useEffect, useState } from 'react'
import { api } from '../api'

const when = (gmt) =>
  new Date(`${gmt}Z`).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

/** Upcoming fixtures (or the latest results when none are scheduled) with a one-click fantasy XI. */
export default function Fixtures({ onPick }) {
  const [data, setData] = useState(null)

  useEffect(() => {
    api.fixtures().then(setData).catch(() => setData({ enabled: false, fixtures: [] }))
  }, [])

  if (!data) return null
  if (!data.enabled && data.fixtures.length === 0) {
    return (
      <div className="card">
        <h2>Fixtures</h2>
        <p className="muted small">Add a free CricketData.org key as CRICKETDATA_API_KEY in .env to see fixtures here.</p>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>{data.upcoming ? 'Upcoming fixtures' : 'Latest results'}</h2>
      {!data.upcoming && (
        <p className="muted small">
          No upcoming matches are scheduled yet, so here are the most recent ones. Pick one to see the XI the
          advisor would choose from today's data.
        </p>
      )}
      <ul className="fixtures">
        {data.fixtures.map((f) => {
          const linkable = f.team1Id && f.team2Id
          return (
            <li key={f.id}>
              <div>
                <strong>{f.team1} vs {f.team2}</strong>
                <div className="muted small">
                  {when(f.startTimeGmt)}{f.venue && ` · ${f.venue}`}
                </div>
                {f.ended && <div className="small">{f.status}</div>}
              </div>
              <button
                type="button"
                className="pick-xi"
                disabled={!linkable}
                title={linkable ? 'Suggest a fantasy XI for this match' : 'No ball-by-ball data for these teams'}
                onClick={() => onPick(f)}
              >
                Pick XI
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
