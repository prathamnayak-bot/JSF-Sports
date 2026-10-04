import { useEffect, useRef, useState } from 'react'
import { BarController, BarElement, CategoryScale, Chart, Legend, LinearScale, Tooltip } from 'chart.js'
import ResultTable from './ResultTable'

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip, Legend)

const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim()

/** Re-render charts when the OS switches between light and dark mode. */
function useColorScheme() {
  const query = '(prefers-color-scheme: dark)'
  const [dark, setDark] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const onChange = (e) => setDark(e.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return dark
}

/**
 * Bar chart (optionally stacked) themed from the CSS tokens.
 * series: [{ label, data, color: '--series-1' }]; tooltipLines(index) adds detail lines to the tooltip.
 * tableRows: rows for the accessible "show as table" view.
 */
export default function BarChart({ labels, series, stacked = false, yTitle, tooltipLines, ariaLabel, tableRows }) {
  const canvasRef = useRef(null)
  const dark = useColorScheme()

  useEffect(() => {
    const surface = css('--surface')
    const text = css('--text')
    const muted = css('--muted')
    const grid = css('--border')
    const last = series.length - 1

    const chart = new Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels,
        datasets: series.map((s, i) => ({
          label: s.label,
          data: s.data,
          backgroundColor: css(s.color),
          hoverBackgroundColor: css(s.color),
          // rounded data-end only on the outermost segment; a 2px surface gap between stacked segments
          borderRadius: !stacked || i === last ? { topLeft: 4, topRight: 4 } : 0,
          borderSkipped: false,
          borderColor: surface,
          borderWidth: stacked && i > 0 ? { bottom: 2, top: 0, left: 0, right: 0 } : 0,
          maxBarThickness: 28,
        })),
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        scales: {
          x: { stacked, grid: { display: false }, ticks: { color: muted, maxRotation: 0, autoSkipPadding: 12 } },
          y: {
            stacked,
            beginAtZero: true,
            grid: { color: grid },
            border: { display: false },
            ticks: { color: muted, precision: 0 },
            title: yTitle ? { display: true, text: yTitle, color: muted } : undefined,
          },
        },
        plugins: {
          legend: {
            display: series.length > 1,
            position: 'top',
            align: 'start',
            labels: { color: text, boxWidth: 12, boxHeight: 12, useBorderRadius: true, borderRadius: 3 },
          },
          tooltip: {
            backgroundColor: surface,
            titleColor: text,
            bodyColor: text,
            footerColor: muted,
            borderColor: grid,
            borderWidth: 1,
            padding: 10,
            callbacks: tooltipLines ? { footer: (items) => tooltipLines(items[0].dataIndex) } : {},
          },
        },
      },
    })
    return () => chart.destroy()
  }, [labels, series, stacked, yTitle, tooltipLines, dark])

  return (
    <>
      <div className="chart-box">
        <canvas ref={canvasRef} role="img" aria-label={ariaLabel} />
      </div>
      {tableRows?.length > 0 && (
        <details className="table-toggle">
          <summary>Show as table</summary>
          <ResultTable rows={tableRows} />
        </details>
      )}
    </>
  )
}
