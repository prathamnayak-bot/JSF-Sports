import { useState } from 'react'
import { api } from '../api'
import ResultTable from './ResultTable'

const EXAMPLES = [
  'Who are the top 5 run scorers in the dataset?',
  'Which batter has the highest strike rate in the death overs (min 60 balls)?',
  'Which bowlers have the best economy at Chepauk?',
  'How many matches has each team won?',
]

export default function ChatPanel() {
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState([])
  const [loading, setLoading] = useState(false)

  async function ask(q) {
    const text = q.trim()
    if (!text || loading) return
    setQuestion('')
    setLoading(true)
    setMessages((m) => [...m, { role: 'user', text }])
    try {
      const res = await api.ask(text)
      setMessages((m) => [...m, { role: 'bot', text: res.answer, sql: res.sql, rows: res.rows }])
    } catch (e) {
      setMessages((m) => [...m, { role: 'bot', text: e.message, error: true }])
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="chat">
      {messages.length === 0 && (
        <div className="examples">
          <p>Ask anything about players, teams, matches or venues. Try:</p>
          {EXAMPLES.map((ex) => (
            <button key={ex} className="chip" onClick={() => ask(ex)}>
              {ex}
            </button>
          ))}
        </div>
      )}

      <div className="messages">
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role} ${m.error ? 'error' : ''}`}>
            <p>{m.text}</p>
            {m.sql && (
              <details>
                <summary>SQL used · {m.rows.length} row(s)</summary>
                <pre>{m.sql}</pre>
                <ResultTable rows={m.rows} />
              </details>
            )}
          </div>
        ))}
        {loading && <div className="msg bot muted">Thinking…</div>}
      </div>

      <form
        className="ask"
        onSubmit={(e) => {
          e.preventDefault()
          ask(question)
        }}
      >
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="e.g. Who took the most wickets in IPL 2024?"
          maxLength={500}
        />
        <button type="submit" disabled={loading || !question.trim()}>
          Ask
        </button>
      </form>
    </section>
  )
}
