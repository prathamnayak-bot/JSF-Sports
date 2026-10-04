import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import BarChart from './BarChart'

/** Fantasy points in each of the player's last 20 T20 matches. */
export default function PlayerFormChart({ playerId, name }) {
  const [form, setForm] = useState(null)

  useEffect(() => {
    api.playerForm(playerId).then(setForm).catch(() => setForm([]))
  }, [playerId])

  const chart = useMemo(() => {
    if (!form?.length) return null
    return {
      labels: form.map((f) => f.date.slice(2).replaceAll('-', '/')), // 24/05/12
      series: [{ label: 'Fantasy points', data: form.map((f) => f.points), color: '--series-1' }],
      tooltipLines: (i) => {
        const f = form[i]
        return [`vs ${f.opponent}`, `${f.runs} runs (${f.ballsFaced} balls) · ${f.wickets} wkts`, f.venue ?? '']
      },
      tableRows: form.map((f) => ({
        date: f.date, opponent: f.opponent, runs: f.runs, balls: f.ballsFaced, wickets: f.wickets, points: f.points,
      })),
    }
  }, [form])

  if (form === null) return <p className="muted small">Loading form…</p>
  if (!chart) return <p className="muted small">No T20 matches for this player.</p>

  const avg = (form.reduce((s, f) => s + f.points, 0) / form.length).toFixed(1)
  return (
    <div className="chart-card">
      <h3>Fantasy points — last {form.length} matches</h3>
      <p className="muted small caption">Average {avg} points per match · hover a bar for the match details</p>
      <BarChart
        labels={chart.labels}
        series={chart.series}
        yTitle="Points"
        tooltipLines={chart.tooltipLines}
        tableRows={chart.tableRows}
        ariaLabel={`Bar chart of ${name}'s fantasy points in each of the last ${form.length} matches, average ${avg}`}
      />
    </div>
  )
}
