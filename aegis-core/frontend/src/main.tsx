import React from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, AlertTriangle, GitBranch, ListChecks, RefreshCcw } from 'lucide-react';
import './styles.css';

type Workflow = {
  id: string;
  status: string;
  currentStep: string;
  retryCount: number;
  request: string;
  createdAt: string;
  updatedAt: string;
};

const sampleWorkflows: Workflow[] = [
  {
    id: 'phase-1-preview',
    status: 'CREATED',
    currentStep: 'workflow.created',
    retryCount: 0,
    request: 'Investigate failed deployment and notify team.',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
];

function App() {
  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Aegis Core</p>
          <h1>Workflow Control Plane</h1>
        </div>
        <button className="icon-button" aria-label="Refresh workflows" title="Refresh workflows">
          <RefreshCcw size={18} />
        </button>
      </header>

      <section className="metrics-grid" aria-label="Workflow metrics">
        <Metric icon={<GitBranch size={18} />} label="Workflows" value="1" />
        <Metric icon={<ListChecks size={18} />} label="Tasks Planned" value="6" />
        <Metric icon={<AlertTriangle size={18} />} label="Failures" value="0" />
        <Metric icon={<Activity size={18} />} label="Workers" value="Phase 2" />
      </section>

      <section className="content-grid">
        <div className="panel">
          <div className="panel-header">
            <h2>Workflows</h2>
          </div>
          <div className="workflow-list">
            {sampleWorkflows.map((workflow) => (
              <article className="workflow-row" key={workflow.id}>
                <div>
                  <span className="status">{workflow.status}</span>
                  <h3>{workflow.request}</h3>
                  <p>{workflow.currentStep}</p>
                </div>
                <span className="retry-count">{workflow.retryCount} retries</span>
              </article>
            ))}
          </div>
        </div>

        <div className="panel">
          <div className="panel-header">
            <h2>Phase 1 DAG</h2>
          </div>
          <ol className="dag-list">
            <li>Fetch deployment logs</li>
            <li>Analyze failure</li>
            <li>Correlate metrics</li>
            <li>Generate summary</li>
            <li>Suggest remediation</li>
            <li>Send notification</li>
          </ol>
        </div>
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
