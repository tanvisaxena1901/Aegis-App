import React from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, Bot, GitBranch, ShieldAlert } from 'lucide-react';
import './styles.css';

const signals = [
  'CrashLoopBackOff after rollout',
  'Back-off restarting failed container',
  'OutOfMemoryError in previous logs',
  'Memory working set near configured limit',
];

function App() {
  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Aegis Core</p>
          <h1>Kubernetes Investigation Console</h1>
        </div>
        <button className="icon-button" aria-label="Run investigation" title="Run investigation">
          <Bot size={18} />
        </button>
      </header>

      <section className="metrics-grid">
        <Metric icon={<GitBranch size={18} />} label="Clusters" value="1" />
        <Metric icon={<ShieldAlert size={18} />} label="Open Incidents" value="1" />
        <Metric icon={<Activity size={18} />} label="K8s Watcher" value="Phase 1" />
        <Metric icon={<Bot size={18} />} label="AI Service" value="LangGraph" />
      </section>

      <section className="content-grid">
        <article className="panel incident-panel">
          <div className="panel-header">
            <h2>Active Investigation</h2>
            <span className="severity">HIGH</span>
          </div>
          <div className="incident-body">
            <h3>checkout-api is restarting after deployment</h3>
            <p>
              Probable cause: the workload likely exceeded its memory limit and entered a
              restart loop.
            </p>
            <div className="actions">
              <button>Inspect Events</button>
              <button>Plan Remediation</button>
            </div>
          </div>
        </article>

        <article className="panel">
          <div className="panel-header">
            <h2>Correlated Signals</h2>
          </div>
          <ol className="signal-list">
            {signals.map((signal) => (
              <li key={signal}>{signal}</li>
            ))}
          </ol>
        </article>
      </section>
    </main>
  );
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="metric">
      <div className="metric-icon">{icon}</div>
      <div>
        <span>{label}</span>
        <strong>{value}</strong>
      </div>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
