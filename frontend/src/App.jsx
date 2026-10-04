import { useState } from 'react'
import ChatPanel from './components/ChatPanel'
import ExplorePanel from './components/ExplorePanel'
import FantasyPanel from './components/FantasyPanel'

const TABS = { ask: 'Ask the analyst', fantasy: 'Fantasy XI', explore: 'Explore stats' }

export default function App() {
  // the tab lives in the URL hash so pages can be linked directly, e.g. /#fantasy
  const [tab, setTabState] = useState(() => (location.hash.slice(1) in TABS ? location.hash.slice(1) : 'ask'))
  const setTab = (key) => {
    setTabState(key)
    history.replaceState(null, '', `#${key}`)
  }

  return (
    <div className="app">
      <header>
        <h1>🏏 AI Cricket Analytics</h1>
        <nav>
          {Object.entries(TABS).map(([key, label]) => (
            <button key={key} className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>
              {label}
            </button>
          ))}
        </nav>
      </header>
      <main>
        {tab === 'ask' && <ChatPanel />}
        {tab === 'fantasy' && <FantasyPanel />}
        {tab === 'explore' && <ExplorePanel />}
      </main>
    </div>
  )
}
