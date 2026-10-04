import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import BarChart from './BarChart'

/** Wins and losses per season for one team (stacked bars: height = results). */
export default function TeamTrends() {
  const [teams, setTeams] = useState([])
  const [teamId, setTeamId] = useState('')
  const [seasons, setSeasons] = useState([])

  useEffect(() => {
    api.teams().then((t) => {
      setTeams(t)
      if (t.length) setTeamId(String(t[0].id))
    }).catch(() => setTeams([]))
  }, [])

  useEffect(() => {
    if (teamId) api.teamSeasons(teamId).then(setSeasons).catch(() => setSeasons([]))
  }, [teamId])

  const chart = useMemo(() => ({
    labels: seasons.map((s) => String(s.year)),
    series: [
      { label: 'Wins', data: seasons.map((s) => s.wins), color: '--series-1' },
      { label: 'Losses', data: seasons.map((s) => s.losses), color: '--series-2' },
    ],
    tooltipLines: (i) => {
      const s = seasons[i]
      const decided = s.wins + s.losses
      const noResult = s.matches - decided
      return [
        `${decided ? Math.round((100 * s.wins) / decided) : 0}% won · ${s.matches} matches`,
        noResult ? `${noResult} tied / no result` : '',
      ]
    },
    tableRows: seasons.map((s) => ({ season: s.season, matches: s.matches, wins: s.wins, losses: s.losses })),
  }), [seasons])

  const teamName = teams.find((t) => String(t.id) === teamId)?.name ?? ''

  return (
    <div className="card chart-card">
      <h2>Team record by season</h2>
      <select className="inline" value={teamId} onChange={(e) => setTeamId(e.target.value)} aria-label="Team">
        {teams.map((t) => (
          <option key={t.id} value={t.id}>{t.name}</option>
        ))}
      </select>
      {seasons.length > 0 && (
        <BarChart
          labels={chart.labels}
          series={chart.series}
          stacked
          yTitle="Matches"
          tooltipLines={chart.tooltipLines}
          tableRows={chart.tableRows}
          ariaLabel={`Stacked bar chart of ${teamName} wins and losses in each season`}
        />
      )}
    </div>
  )
}
