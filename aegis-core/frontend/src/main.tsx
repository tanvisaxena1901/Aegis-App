import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Clock3,
  FileText,
  Gauge,
  Layers3,
  ListFilter,
  MessageSquare,
  RefreshCw,
  RotateCw,
  Search,
  Send,
  Server,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import './styles.css';

type ClusterSnapshot = {
  context: string;
  namespaces: number;
  pods: number;
  warningEvents: string[];
  observedAt: string;
};

type PodSummary = {
  namespace: string;
  name: string;
  phase: string;
  readyContainers: number;
  totalContainers: number;
  restarts: number;
  nodeName: string;
  containers: string[];
  statusReasons: string[];
  failing: boolean;
  createdAt: string | null;
};

type DeploymentSummary = {
  namespace: string;
  name: string;
  replicas: number;
  readyReplicas: number;
  updatedReplicas: number;
  availableReplicas: number;
  strategy: string;
  selector: Record<string, string>;
  images: string[];
  createdAt: string | null;
};

type EventSummary = {
  namespace: string;
  type: string;
  reason: string;
  message: string;
  involvedObject: string;
  count: number;
  lastSeen: string | null;
};

type DeploymentDetail = {
  summary: DeploymentSummary;
  labels: Record<string, string>;
  annotations: Record<string, string>;
  conditions: string[];
  containers: string[];
  rollout: {
    ready: boolean;
    summary: string;
    replicas: number;
    readyReplicas: number;
    updatedReplicas: number;
    availableReplicas: number;
    generation: number;
    observedGeneration: number;
    conditions: string[];
  };
};

type PodLogs = {
  namespace: string;
  podName: string;
  container: string | null;
  tailLines: number;
  lines: string[];
  observedAt: string;
};

type InvestigationResponse = {
  incidentId: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  probableCause: string;
  summary: string;
  evidence: string[];
  recommendedActions: string[];
  humanApprovalRequired: boolean;
  generatedAt: string;
};

type FailingPodSignal = {
  namespace: string;
  name: string;
  phase: string;
  readyContainers: number;
  totalContainers: number;
  restarts: number;
  nodeName: string;
  reasons: string[];
};

type EnvironmentHealth = {
  environment: string;
  namespace: string;
  severity: 'HEALTHY' | 'WARNING' | 'CRITICAL';
  pods: number;
  runningPods: number;
  failingPods: number;
  restarts: number;
  warningEvents: number;
  deployments: number;
  readyDeployments: number;
  failingPodSignals: FailingPodSignal[];
  signals: string[];
};

type EnvironmentHealthResponse = {
  environments: EnvironmentHealth[];
  observedAt: string;
};

type ChatEvidence = {
  sourceType: string;
  sourceName: string;
  message: string;
};

type ChatResponse = {
  answer: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  evidence: ChatEvidence[];
  recommendedActions: string[];
  humanApprovalRequired: boolean;
  generatedAt: string;
};

type ChatTurn = {
  role: 'user' | 'assistant';
  content: string;
  severity?: ChatResponse['severity'];
  evidence?: ChatEvidence[];
  recommendedActions?: string[];
};

type TerminalResponse = {
  command: string;
  output: string;
  exitCode: number;
  durationMs: number;
  generatedAt: string;
};

type TerminalEntry = {
  id: string;
  command: string;
  response?: TerminalResponse;
  error?: string;
};

type RemediationAction = 'ROLLOUT_RESTART_DEPLOYMENT' | 'DELETE_MANAGED_POD';

type RemediationIntent = {
  action: RemediationAction;
  targetName: string;
};

type RemediationResponse = {
  action: RemediationAction;
  namespace: string;
  targetName: string;
  status: string;
  summary: string;
  guardrails: string[];
  executedAt: string;
};

type LoadState = 'idle' | 'loading' | 'ready' | 'error';
type Tab = 'overview' | 'workloads' | 'events' | 'logs' | 'ai';

const namespace = 'aegis';

const quickQuestions = [
  'Which environment has issues?',
  'Which pods are failing?',
  'Why is the selected pod unhealthy?',
  'What should I check next?',
];

function App() {
  const [snapshot, setSnapshot] = useState<ClusterSnapshot | null>(null);
  const [snapshotState, setSnapshotState] = useState<LoadState>('idle');
  const [pods, setPods] = useState<PodSummary[]>([]);
  const [deployments, setDeployments] = useState<DeploymentSummary[]>([]);
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [environmentHealth, setEnvironmentHealth] = useState<EnvironmentHealthResponse | null>(null);
  const [environmentState, setEnvironmentState] = useState<LoadState>('idle');
  const [operationsState, setOperationsState] = useState<LoadState>('idle');
  const [selectedPod, setSelectedPod] = useState('');
  const [selectedDeployment, setSelectedDeployment] = useState('aegis-backend');
  const [deploymentDetail, setDeploymentDetail] = useState<DeploymentDetail | null>(null);
  const [detailState, setDetailState] = useState<LoadState>('idle');
  const [logs, setLogs] = useState<PodLogs | null>(null);
  const [logsState, setLogsState] = useState<LoadState>('idle');
  const [investigation, setInvestigation] = useState<InvestigationResponse | null>(null);
  const [investigationState, setInvestigationState] = useState<LoadState>('idle');
  const [chatQuestion, setChatQuestion] = useState('Which environment has issues and what pods are failing?');
  const [chatHistory, setChatHistory] = useState<ChatTurn[]>([]);
  const [chatState, setChatState] = useState<LoadState>('idle');
  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const [query, setQuery] = useState('');
  const [showWarningsOnly, setShowWarningsOnly] = useState(false);
  const [terminalCommand, setTerminalCommand] = useState(`kubectl get pods -n ${namespace}`);
  const [terminalEntries, setTerminalEntries] = useState<TerminalEntry[]>([]);
  const [terminalCommandHistory, setTerminalCommandHistory] = useState<string[]>([]);
  const [terminalHistoryCursor, setTerminalHistoryCursor] = useState<number | null>(null);
  const [terminalState, setTerminalState] = useState<LoadState>('idle');
  const [terminalError, setTerminalError] = useState('');
  const [remediationIntent, setRemediationIntent] = useState<RemediationIntent | null>(null);
  const [remediationReason, setRemediationReason] = useState('');
  const [remediationConfirmation, setRemediationConfirmation] = useState('');
  const [remediationState, setRemediationState] = useState<LoadState>('idle');
  const [remediationResult, setRemediationResult] = useState<RemediationResponse | null>(null);
  const [remediationError, setRemediationError] = useState('');

  useEffect(() => {
    void refreshAll();
  }, []);

  useEffect(() => {
    if (!selectedPod && pods.length > 0) {
      setSelectedPod(pods[0].name);
    }
  }, [pods, selectedPod]);

  const warningEvents = events.filter((event) => event.type.toLowerCase() === 'warning');
  const runningPods = pods.filter((pod) => pod.phase === 'Running').length;
  const restartTotal = pods.reduce((total, pod) => total + pod.restarts, 0);
  const readyDeployments = deployments.filter((deployment) => deployment.readyReplicas === deployment.replicas).length;
  const workloadReadiness = deployments.length ? Math.round((readyDeployments / deployments.length) * 100) : 0;
  const unhealthyEnvironments = environmentHealth?.environments.filter((environment) => environment.severity !== 'HEALTHY') ?? [];
  const criticalEnvironments = unhealthyEnvironments.filter((environment) => environment.severity === 'CRITICAL').length;
  const environmentChart = (environmentHealth?.environments ?? [])
    .filter((environment) => environment.failingPods || environment.warningEvents || environment.restarts)
    .slice()
    .sort((left, right) => right.failingPods - left.failingPods || right.warningEvents - left.warningEvents)
    .slice(0, 6)
    .map((environment) => ({
      label: environment.environment,
      value: environment.failingPods,
      detail: `${environment.warningEvents} warnings`,
    }));
  const filteredPods = pods.filter((pod) => contains(pod.name, query) || contains(pod.containers.join(' '), query));
  const filteredEvents = events
    .filter((event) => !showWarningsOnly || event.type.toLowerCase() === 'warning')
    .filter((event) => contains(`${event.reason} ${event.message} ${event.involvedObject}`, query));
  const observedAt = formatTime(snapshot?.observedAt);
  const refreshing = [snapshotState, environmentState, operationsState, detailState].includes('loading');

  async function refreshAll() {
    await Promise.all([loadSnapshot(), loadEnvironmentHealth(), loadOperations(), loadDeploymentDetail(selectedDeployment)]);
  }

  async function loadSnapshot() {
    setSnapshotState('loading');
    try {
      const response = await fetch('/api/cluster/snapshot');
      if (!response.ok) throw new Error('snapshot failed');
      setSnapshot(await response.json());
      setSnapshotState('ready');
    } catch {
      setSnapshotState('error');
    }
  }

  async function loadOperations() {
    setOperationsState('loading');
    try {
      const [podResponse, deploymentResponse, eventResponse] = await Promise.all([
        fetch(`/api/kubernetes/namespaces/${namespace}/pods`),
        fetch(`/api/kubernetes/namespaces/${namespace}/deployments`),
        fetch(`/api/kubernetes/namespaces/${namespace}/events`),
      ]);
      if (!podResponse.ok || !deploymentResponse.ok || !eventResponse.ok) {
        throw new Error('operations failed');
      }
      setPods(await podResponse.json());
      setDeployments(await deploymentResponse.json());
      setEvents(await eventResponse.json());
      setOperationsState('ready');
    } catch {
      setOperationsState('error');
    }
  }

  async function loadEnvironmentHealth() {
    setEnvironmentState('loading');
    try {
      const response = await fetch('/api/environments/health');
      if (!response.ok) throw new Error('environment health failed');
      setEnvironmentHealth(await response.json());
      setEnvironmentState('ready');
    } catch {
      setEnvironmentState('error');
    }
  }

  async function loadDeploymentDetail(deploymentName = selectedDeployment) {
    if (!deploymentName) return;
    setDetailState('loading');
    try {
      const response = await fetch(
        `/api/kubernetes/namespaces/${namespace}/deployments/${deploymentName}/describe`,
      );
      if (!response.ok) throw new Error('describe failed');
      setDeploymentDetail(await response.json());
      setDetailState('ready');
    } catch {
      setDetailState('error');
    }
  }

  async function loadPodLogs(podName = selectedPod) {
    if (!podName) return;
    setLogsState('loading');
    setActiveTab('logs');
    try {
      const response = await fetch(
        `/api/kubernetes/namespaces/${namespace}/pods/${podName}/logs?tailLines=160`,
      );
      if (!response.ok) throw new Error('logs failed');
      setLogs(await response.json());
      setLogsState('ready');
    } catch {
      setLogsState('error');
    }
  }

  async function runInvestigation() {
    setInvestigationState('loading');
    setActiveTab('ai');
    try {
      const response = await fetch('/api/incidents/investigate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          namespace,
          resourceKind: 'Deployment',
          resourceName: selectedDeployment,
          symptom: `Review rollout, warning events, and pod logs for ${selectedDeployment}.`,
          events: filteredEvents.slice(0, 8).map((event) => `${event.type}/${event.reason}: ${event.message}`),
          logs: logs?.lines.slice(-10) ?? ['No pod logs loaded yet.'],
          metrics: [
            `namespace_pods=${pods.length}`,
            `running_pods=${runningPods}`,
            `deployment_readiness=${workloadReadiness}`,
            `warning_events=${warningEvents.length}`,
            `restarts=${restartTotal}`,
          ],
        }),
      });
      if (!response.ok) throw new Error('rca failed');
      setInvestigation(await response.json());
      setInvestigationState('ready');
    } catch {
      setInvestigationState('error');
    }
  }

  async function askAegis() {
    const message = chatQuestion.trim();
    if (!message) return;
    const includeLogs = wantsLogs(message);
    setChatState('loading');
    setChatQuestion('');
    setChatHistory((history) => [...history, { role: 'user', content: message }]);
    try {
      const result = await postChat({
        message,
        includeLogs,
        history: chatHistory.slice(-6).map((turn) => ({ role: turn.role, content: turn.content })),
      });
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: result.answer,
          severity: result.severity,
          evidence: result.evidence,
          recommendedActions: result.recommendedActions,
        },
      ]);
      setChatState('ready');
    } catch {
      setChatQuestion(message);
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: 'The LLM request timed out or the local model stopped responding. The dashboard is still usable; retry once, or restart Ollama if this repeats.',
          severity: 'LOW',
        },
      ]);
      setChatState('error');
    }
  }

  function askQuickQuestion(question: string) {
    void askAegisWithMessage(question);
  }

  async function askAegisWithMessage(message: string) {
    const trimmed = message.trim();
    if (!trimmed) return;
    const includeLogs = wantsLogs(trimmed);
    setChatState('loading');
    setChatQuestion('');
    setChatHistory((history) => [...history, { role: 'user', content: trimmed }]);
    try {
      const result = await postChat({
        message: trimmed,
        includeLogs,
        history: chatHistory.slice(-6).map((turn) => ({ role: turn.role, content: turn.content })),
      });
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: result.answer,
          severity: result.severity,
          evidence: result.evidence,
          recommendedActions: result.recommendedActions,
        },
      ]);
      setChatState('ready');
    } catch {
      setChatQuestion(trimmed);
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: 'The LLM request timed out or the local model stopped responding. The dashboard is still usable; retry once, or restart Ollama if this repeats.',
          severity: 'LOW',
        },
      ]);
      setChatState('error');
    }
  }

  async function postChat({
    message,
    includeLogs,
    history,
  }: {
    message: string;
    includeLogs: boolean;
    history: { role: ChatTurn['role']; content: string }[];
  }) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 90000);
    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          message,
          namespace,
          deploymentName: selectedDeployment,
          podName: selectedPod,
          includeLogs,
          history,
        }),
      });
      if (!response.ok) throw new Error('chat failed');
      return await response.json() as ChatResponse;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function selectDeployment(name: string) {
    setSelectedDeployment(name);
    setActiveTab('workloads');
    void loadDeploymentDetail(name);
  }

  function selectPod(name: string) {
    setSelectedPod(name);
    setActiveTab('workloads');
  }

  async function runTerminal(command = terminalCommand) {
    const trimmed = command.trim();
    if (!trimmed) return;
    setTerminalState('loading');
    setTerminalError('');
    setTerminalCommand(trimmed);
    try {
      const response = await fetch('/api/terminal/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command: trimmed }),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Command failed' }));
        throw new Error(error.message || 'Command failed');
      }
      const result = await response.json();
      setTerminalEntries((entries) => [...entries, {
        id: `${Date.now()}-${entries.length}`,
        command: trimmed,
        response: result,
      }].slice(-12));
      setTerminalCommandHistory((history) => [...history.filter((item) => item !== trimmed), trimmed].slice(-20));
      setTerminalHistoryCursor(null);
      setTerminalCommand('');
      setTerminalState('ready');
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Command failed';
      setTerminalError(message);
      setTerminalEntries((entries) => [...entries, {
        id: `${Date.now()}-${entries.length}`,
        command: trimmed,
        error: message,
      }].slice(-12));
      setTerminalCommandHistory((history) => [...history.filter((item) => item !== trimmed), trimmed].slice(-20));
      setTerminalHistoryCursor(null);
      setTerminalState('error');
    }
  }

  function openRemediation(action: RemediationAction) {
    const targetName = action === 'ROLLOUT_RESTART_DEPLOYMENT' ? selectedDeployment : selectedPod;
    if (!targetName) return;
    setRemediationIntent({ action, targetName });
    setRemediationReason(defaultRemediationReason(action, targetName));
    setRemediationConfirmation('');
    setRemediationState('idle');
    setRemediationError('');
    setRemediationResult(null);
  }

  async function executeRemediation() {
    if (!remediationIntent) return;
    setRemediationState('loading');
    setRemediationError('');
    try {
      const response = await fetch('/api/remediation/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: remediationIntent.action,
          namespace,
          targetName: remediationIntent.targetName,
          reason: remediationReason,
          approved: remediationConfirmation.trim() === 'APPROVE',
          confirmation: remediationConfirmation,
        }),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Remediation failed' }));
        throw new Error(error.message || 'Remediation failed');
      }
      const result: RemediationResponse = await response.json();
      setRemediationResult(result);
      setRemediationState('ready');
      void refreshAll();
    } catch (error) {
      setRemediationError(error instanceof Error ? error.message : 'Remediation failed');
      setRemediationState('error');
    }
  }

  function recallTerminalCommand(direction: 'older' | 'newer') {
    if (!terminalCommandHistory.length) return;
    const lastIndex = terminalCommandHistory.length - 1;
    if (direction === 'older') {
      const nextCursor = terminalHistoryCursor === null ? lastIndex : Math.max(0, terminalHistoryCursor - 1);
      setTerminalHistoryCursor(nextCursor);
      setTerminalCommand(terminalCommandHistory[nextCursor]);
      return;
    }
    if (terminalHistoryCursor === null) return;
    const nextCursor = terminalHistoryCursor + 1;
    if (nextCursor > lastIndex) {
      setTerminalHistoryCursor(null);
      setTerminalCommand('');
      return;
    }
    setTerminalHistoryCursor(nextCursor);
    setTerminalCommand(terminalCommandHistory[nextCursor]);
  }

  return (
    <main className="app-shell">
      <section className="hero">
        <div>
          <p className="eyebrow">Namespace / {namespace}</p>
          <h1>Kubernetes Operations Dashboard</h1>
          <p className="subtitle">
            Workloads, rollout state, events, logs, and RCA context for the active cluster.
          </p>
        </div>
        <div className="hero-actions">
          <button className="primary-action" onClick={() => void refreshAll()} disabled={refreshing}>
            <RefreshCw size={17} />
            {refreshing ? 'Refreshing' : 'Refresh'}
          </button>
          <button className="secondary-action" onClick={() => void runInvestigation()} disabled={investigationState === 'loading'}>
            <Bot size={17} />
            {investigationState === 'loading' ? 'Analyzing' : 'Analyze'}
          </button>
        </div>
      </section>

      <section className="metric-grid" aria-label="Cluster metrics">
        <MetricCard icon={<Gauge />} label="Workload readiness" value={`${workloadReadiness}%`} trend={`${readyDeployments}/${deployments.length || 0} deployments`} tone="green" />
        <MetricCard icon={<Server />} label="Running pods" value={`${runningPods}/${pods.length || 0}`} trend={`${restartTotal} restarts`} tone="blue" />
        <MetricCard icon={<AlertTriangle />} label="Warning events" value={warningEvents.length} trend={`last scan ${observedAt}`} tone={warningEvents.length ? 'amber' : 'green'} />
        <MetricCard icon={<Layers3 />} label="Environments with issues" value={unhealthyEnvironments.length} trend={`${criticalEnvironments} critical`} tone={criticalEnvironments ? 'amber' : 'violet'} />
      </section>

      <section className="control-bar">
        <div className="search-box">
          <Search size={16} />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search pods, events, workloads"
          />
        </div>
        <div className="segmented-tabs">
          {(['overview', 'workloads', 'events', 'logs', 'ai'] as Tab[]).map((tab) => (
            <button className={activeTab === tab ? 'active' : ''} key={tab} onClick={() => setActiveTab(tab)}>
              {tab}
            </button>
          ))}
        </div>
      </section>

      <section className="ops-dock" aria-label="Aegis tools">
        <article className="panel chat-panel">
          <PanelTitle
            title="Ask Aegis Kubernetes Copilot"
            detail="One-stop RCA, logs, and cluster chat"
          />
          <div className="chat-shell compact-chat">
            <div className="chat-messages">
              {chatHistory.length ? (
                chatHistory.slice(-6).map((turn, index) => (
                  <div className={`chat-message ${turn.role}`} key={`${turn.role}-${index}-${turn.content}`}>
                    <div className="chat-message-head">
                      <span>{turn.role === 'user' ? 'You' : 'Aegis'}</span>
                      {turn.severity && <b className={`severity ${turn.severity.toLowerCase()}`}>{turn.severity}</b>}
                    </div>
                    <ChatText content={turn.content} />
                    {turn.evidence?.length ? <span className="signal-count">{turn.evidence.length} source signals used</span> : null}
                  </div>
                ))
              ) : (
                <div className="chat-message assistant">
                  <div className="chat-message-head"><span>Aegis</span></div>
                  <ChatText content="Ask about unhealthy environments, failing pods, warning events, logs, or rollout state." />
                </div>
              )}
            </div>
            <div className="quick-questions">
              {quickQuestions.map((question) => (
                <button key={question} onClick={() => askQuickQuestion(question)} disabled={chatState === 'loading'}>
                  {question}
                </button>
              ))}
            </div>
            <div className="chat-composer">
              <div className="chat-input">
                <MessageSquare size={18} />
                <textarea
                  value={chatQuestion}
                  onChange={(event) => setChatQuestion(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && !event.shiftKey) {
                      event.preventDefault();
                      void askAegis();
                    }
                  }}
                  placeholder="Ask Aegis about failing pods, environments, events, logs, or remediation"
                />
              </div>
              <button className="primary-action chat-submit" onClick={() => void askAegis()} disabled={chatState === 'loading'}>
                <Send size={15} />
                {chatState === 'loading' ? 'Asking' : 'Ask'}
              </button>
            </div>
            {chatState === 'error' && <p className="inline-error">Aegis chat is unavailable.</p>}
          </div>
        </article>

        <article className="panel tools-panel">
          <PanelTitle title="Ops actions" detail="Useful dashboard controls" />
          <div className="action-grid">
            <button className="action-tile" onClick={() => void refreshAll()} disabled={refreshing}>
              <RefreshCw size={18} />
              <strong>{refreshing ? 'Refreshing data' : 'Refresh data'}</strong>
              <span>Reload snapshot, environments, workloads, events, and rollout detail.</span>
            </button>
            <button className="action-tile" onClick={() => void runInvestigation()} disabled={investigationState === 'loading'}>
              <Bot size={18} />
              <strong>{investigationState === 'loading' ? 'Running RCA' : 'Analyze selected deployment'}</strong>
              <span>{selectedDeployment}</span>
            </button>
            <button className="action-tile" onClick={() => void loadPodLogs()} disabled={!selectedPod || logsState === 'loading'}>
              <FileText size={18} />
              <strong>{logsState === 'loading' ? 'Loading logs' : 'Load selected pod logs'}</strong>
              <span>{selectedPod || 'Select a pod first'}</span>
            </button>
            <button className="action-tile" onClick={() => { setShowWarningsOnly(true); setActiveTab('events'); }}>
              <AlertTriangle size={18} />
              <strong>Review warnings</strong>
              <span>{warningEvents.length} warning events in namespace.</span>
            </button>
            <button className="action-tile danger-aware" onClick={() => openRemediation('ROLLOUT_RESTART_DEPLOYMENT')} disabled={!selectedDeployment}>
              <RotateCw size={18} />
              <strong>Restart deployment</strong>
              <span>{selectedDeployment || 'Select a deployment first'} with approval.</span>
            </button>
            <button className="action-tile danger-aware" onClick={() => openRemediation('DELETE_MANAGED_POD')} disabled={!selectedPod}>
              <Trash2 size={18} />
              <strong>Delete managed pod</strong>
              <span>{selectedPod || 'Select a pod first'} with approval.</span>
            </button>
          </div>
          {remediationIntent && (
            <div className="remediation-box">
              <div>
                <span>AI-assisted remediation</span>
                <strong>{remediationTitle(remediationIntent.action)}</strong>
                <p>{remediationCommand(remediationIntent, namespace)}</p>
              </div>
              <label>
                Reason
                <textarea value={remediationReason} onChange={(event) => setRemediationReason(event.target.value)} />
              </label>
              <label>
                Type APPROVE
                <input value={remediationConfirmation} onChange={(event) => setRemediationConfirmation(event.target.value)} />
              </label>
              <div className="remediation-actions">
                <button className="secondary-action" onClick={() => setRemediationIntent(null)} disabled={remediationState === 'loading'}>
                  Cancel
                </button>
                <button
                  className="primary-action danger-action"
                  onClick={() => void executeRemediation()}
                  disabled={remediationState === 'loading' || remediationConfirmation.trim() !== 'APPROVE'}
                >
                  {remediationState === 'loading' ? 'Executing' : 'Approve and execute'}
                </button>
              </div>
              {remediationError && <p className="inline-error">{remediationError}</p>}
              {remediationResult && (
                <div className="remediation-result">
                  <strong>{remediationResult.status}</strong>
                  <p>{remediationResult.summary}</p>
                </div>
              )}
            </div>
          )}
        </article>
      </section>

      {activeTab === 'overview' && (
        <section className="dashboard-grid">
          <article className="panel">
            <PanelTitle title="Recent warning signals" detail={`${warningEvents.length} warning events`} />
            <div className="event-stack">
              {warningEvents.slice(0, 5).map((event) => (
                <EventRow event={event} key={`${event.involvedObject}-${event.reason}-${event.lastSeen}`} />
              ))}
              {warningEvents.length === 0 && <EmptyState text="No warning events in this namespace." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Failing pods by environment" detail="Top impacted namespaces" />
            <MiniBarChart
              emptyText="No failing pod data returned."
              items={environmentChart}
              tone="warning"
            />
          </article>

          <article className="panel span-2">
            <PanelTitle
              title="Environment issues"
              detail={environmentState === 'error' ? 'Environment health API unavailable' : `Observed ${formatTime(environmentHealth?.observedAt)}`}
              action={
                <button className="ghost-action" onClick={() => void loadEnvironmentHealth()}>
                  <RotateCw size={15} />
                  Reload
                </button>
              }
            />
            <div className="environment-grid">
              {(environmentHealth?.environments ?? []).slice(0, 8).map((environment) => (
                <EnvironmentCard environment={environment} key={environment.namespace} />
              ))}
              {environmentHealth?.environments.length === 0 && <EmptyState text="No environments returned by the cluster scan." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Deployment readiness" detail="Click a deployment to inspect rollout details" />
            <div className="deployment-strip">
              {deployments.map((deployment) => (
                <button
                  className={selectedDeployment === deployment.name ? 'deployment-card selected' : 'deployment-card'}
                  key={deployment.name}
                  onClick={() => selectDeployment(deployment.name)}
                >
                  <span>{deployment.name}</span>
                  <strong>{deployment.readyReplicas}/{deployment.replicas}</strong>
                  <Bar value={deployment.readyReplicas} total={deployment.replicas || 1} compact />
                </button>
              ))}
            </div>
          </article>

        </section>
      )}

      {activeTab === 'workloads' && (
        <section className="dashboard-grid">
          <article className="panel">
            <PanelTitle title="Deployments" detail="Select one to describe and analyze" />
            <div className="resource-list">
              {deployments.map((deployment) => (
                <button
                  className={selectedDeployment === deployment.name ? 'resource-row selected' : 'resource-row'}
                  key={deployment.name}
                  onClick={() => selectDeployment(deployment.name)}
                >
                  <StatusDot ok={deployment.readyReplicas === deployment.replicas} />
                  <div>
                    <strong>{deployment.name}</strong>
                    <span>{deployment.images.join(', ')}</span>
                  </div>
                  <code>{deployment.readyReplicas}/{deployment.replicas}</code>
                </button>
              ))}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Pods" detail="Select a pod, then open logs" />
            <div className="resource-list">
              {filteredPods.map((pod) => (
                <button
                  className={selectedPod === pod.name ? 'resource-row selected' : 'resource-row'}
                  key={pod.name}
                  onClick={() => selectPod(pod.name)}
                >
                  <StatusDot ok={!pod.failing} />
                  <div>
                    <strong>{pod.name}</strong>
                    <span>{pod.statusReasons.length ? pod.statusReasons.join('; ') : `${pod.containers.join(', ')} on ${pod.nodeName}`}</span>
                  </div>
                  <code>{pod.restarts} restarts</code>
                </button>
              ))}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle
              title="Rollout detail"
              detail={detailState === 'loading' ? 'Loading deployment detail' : selectedDeployment}
              action={
                <button className="ghost-action" onClick={() => void loadDeploymentDetail()}>
                  <RotateCw size={15} />
                  Reload
                </button>
              }
            />
            {deploymentDetail ? (
              <div className="rollout-grid">
                <div className="rollout-summary">
                  <CheckCircle2 size={22} />
                  <strong>{deploymentDetail.rollout.summary}</strong>
                  <p>
                    Generation {deploymentDetail.rollout.observedGeneration}/{deploymentDetail.rollout.generation},
                    available {deploymentDetail.rollout.availableReplicas}/{deploymentDetail.rollout.replicas}.
                  </p>
                </div>
                <SignalList title="Containers" items={deploymentDetail.containers} />
                <SignalList title="Conditions" items={deploymentDetail.conditions} />
              </div>
            ) : (
              <EmptyState text="Select a deployment to inspect rollout state." />
            )}
          </article>
        </section>
      )}

      {activeTab === 'events' && (
        <section className="panel full-panel">
          <PanelTitle
            title="Namespace events"
            detail={`${filteredEvents.length} matching events`}
            action={
              <button className={showWarningsOnly ? 'ghost-action active' : 'ghost-action'} onClick={() => setShowWarningsOnly((value) => !value)}>
                <ListFilter size={15} />
                Warnings
              </button>
            }
          />
          <div className="event-table">
            {filteredEvents.map((event) => (
              <EventRow event={event} key={`${event.involvedObject}-${event.reason}-${event.lastSeen}-${event.count}`} wide />
            ))}
            {filteredEvents.length === 0 && <EmptyState text="No events match the current filters." />}
          </div>
        </section>
      )}

      {activeTab === 'logs' && (
        <section className="panel full-panel">
          <PanelTitle
            title="Pod logs"
            detail={selectedPod || 'Select a pod'}
            action={
              <button className="ghost-action" onClick={() => void loadPodLogs()} disabled={!selectedPod || logsState === 'loading'}>
                <FileText size={15} />
                {logsState === 'loading' ? 'Loading' : 'Load logs'}
              </button>
            }
          />
          <pre className="log-view">
            {logsState === 'error'
              ? 'Unable to load logs for the selected pod.'
              : logs?.lines.length
                ? logs.lines.join('\n')
                : 'Select a pod from Workloads and load logs.'}
          </pre>
        </section>
      )}

      {activeTab === 'ai' && (
        <section className="dashboard-grid">
          <article className="panel span-2">
            <PanelTitle
              title="AI triage"
              detail={`Target deployment: ${selectedDeployment}`}
              action={
                <button className="primary-action compact-action" onClick={() => void runInvestigation()} disabled={investigationState === 'loading'}>
                  <Bot size={15} />
                  {investigationState === 'loading' ? 'Analyzing' : 'Run RCA'}
                </button>
              }
            />
            {investigation ? (
              <div className="ai-result">
                <div>
                  <span className={`severity ${investigation.severity.toLowerCase()}`}>{investigation.severity}</span>
                  <h2>{investigation.summary}</h2>
                  <p>{investigation.probableCause}</p>
                </div>
                <SignalList title="Evidence" items={investigation.evidence} />
                <SignalList title="Recommended actions" items={investigation.recommendedActions} />
              </div>
            ) : (
              <EmptyState text="Run RCA to combine rollout status, events, logs, and warnings into a triage summary." />
            )}
          </article>
        </section>
      )}

      <section className="panel terminal-panel">
        <PanelTitle title="Live Kubernetes Terminal" detail="Read-only kubectl status checks from the dashboard" />
        <div className="terminal-shell">
          {terminalError && <p className="inline-error">{terminalError}</p>}
          <div className="terminal-console">
            <div className="terminal-transcript" aria-live="polite">
              {terminalEntries.length ? terminalEntries.map((entry) => (
                <div className="terminal-entry" key={entry.id}>
                  <div className="terminal-command-line"><span>aegis@k8s:~$</span><code>{entry.command}</code></div>
                  <pre className={entry.error ? 'terminal-output terminal-output-error' : 'terminal-output'}>
                    {entry.response
                      ? `exit ${entry.response.exitCode} / ${entry.response.durationMs}ms\n${entry.response.output || '(no output)'}`
                      : entry.error}
                  </pre>
                </div>
              )) : (
                <pre className="terminal-output terminal-output-muted">Run a read-only kubectl command. Previous commands will stay here like a terminal session.</pre>
              )}
            </div>
            <div className="terminal-prompt">
              <span>aegis@k8s:~$</span>
              <input
                value={terminalCommand}
                onChange={(event) => setTerminalCommand(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    void runTerminal();
                  } else if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    recallTerminalCommand('older');
                  } else if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    recallTerminalCommand('newer');
                  }
                }}
                placeholder="kubectl get pods -n aegis"
              />
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}

function MetricCard({
  icon,
  label,
  value,
  trend,
  tone,
}: {
  icon: React.ReactNode;
  label: string;
  value: React.ReactNode;
  trend: string;
  tone: 'green' | 'blue' | 'amber' | 'violet';
}) {
  return (
    <article className={`metric-card ${tone}`}>
      <div className="metric-icon">{icon}</div>
      <span>{label}</span>
      <strong>{value}</strong>
      <p>{trend}</p>
    </article>
  );
}

function EnvironmentCard({ environment }: { environment: EnvironmentHealth }) {
  const failingPods = environment.failingPodSignals.map((pod) => pod.name).join(', ');
  return (
    <article className={`environment-card ${environment.severity.toLowerCase()}`}>
      <div>
        <span>{environment.severity}</span>
        <strong>{environment.environment}</strong>
      </div>
      <p>
        {environment.failingPods} failing pods, {environment.warningEvents} warnings, {environment.restarts} restarts
      </p>
      <Bar value={environment.runningPods} total={environment.pods || 1} compact />
      <small>{failingPods || `${environment.readyDeployments}/${environment.deployments || 0} deployments ready`}</small>
    </article>
  );
}

function PanelTitle({ title, detail, action }: { title: string; detail: string; action?: React.ReactNode }) {
  return (
    <div className="panel-title">
      <div>
        <h2>{title}</h2>
        <p>{detail}</p>
      </div>
      {action}
    </div>
  );
}

function Bar({ label, value, total, compact = false }: { label?: string; value: number; total: number; compact?: boolean }) {
  const percent = Math.round((value / Math.max(total, 1)) * 100);
  return (
    <div className={compact ? 'bar compact' : 'bar'}>
      {label && (
        <div>
          <span>{label}</span>
          <strong>{value}/{total}</strong>
        </div>
      )}
      <i><b style={{ width: `${percent}%` }} /></i>
    </div>
  );
}

function MiniBarChart({
  items,
  emptyText,
  tone,
}: {
  items: { label: string; value: number; detail: string }[];
  emptyText: string;
  tone: 'warning' | 'info';
}) {
  const max = Math.max(...items.map((item) => item.value), 1);
  if (!items.length) {
    return <EmptyState text={emptyText} />;
  }
  return (
    <div className="mini-chart">
      {items.map((item) => (
        <div className="mini-chart-row" key={`${item.label}-${item.detail}`}>
          <div>
            <strong>{item.label}</strong>
            <span>{item.detail}</span>
          </div>
          <i><b className={tone} style={{ width: `${Math.max((item.value / max) * 100, item.value ? 8 : 0)}%` }} /></i>
          <code>{item.value}</code>
        </div>
      ))}
    </div>
  );
}

function EventRow({ event, wide = false }: { event: EventSummary; wide?: boolean }) {
  const warning = event.type.toLowerCase() === 'warning';
  return (
    <div className={wide ? 'event-row wide' : 'event-row'}>
      <div className={warning ? 'event-mark warning' : 'event-mark'} />
      <div>
        <strong>{event.reason || event.type}</strong>
        <p>{event.message}</p>
        <span><Clock3 size={12} /> {event.involvedObject} / {formatTime(event.lastSeen)}</span>
      </div>
    </div>
  );
}

function ChatText({ content }: { content: string }) {
  const lines = content.split('\n').map((line) => line.trimEnd()).filter((line) => line.trim());
  const blocks: React.ReactNode[] = [];
  let bulletItems: React.ReactNode[] = [];
  const flushBullets = () => {
    if (!bulletItems.length) return;
    blocks.push(<ul key={`bullets-${blocks.length}`}>{bulletItems}</ul>);
    bulletItems = [];
  };

  lines.forEach((line, index) => {
    const trimmed = line.trim();
    const isBullet = /^[-*]\s+/.test(trimmed) || /^\d+\.\s/.test(trimmed);
    const isNestedBullet = /^\s{2,}[-*]\s+/.test(line);
    const isHeading = !isBullet && index < lines.length - 1 && !/[.:;]$/.test(trimmed) && trimmed.length < 48;
    if (isBullet) {
      const display = trimmed.replace(/^[-*]\s+/, '').replace(/^\d+\.\s+/, '');
      bulletItems.push(<li className={isNestedBullet ? 'nested' : undefined} key={`${index}-${trimmed}`}>{display}</li>);
      return;
    }
    flushBullets();
    if (isHeading) {
      blocks.push(<h4 key={`${index}-${trimmed}`}>{trimmed}</h4>);
      return;
    }
    blocks.push(<p key={`${index}-${trimmed}`}>{trimmed}</p>);
  });
  flushBullets();

  return (
    <div className="chat-text">
      {blocks}
    </div>
  );
}

function SignalList({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="signal-list">
      <h3>{title}</h3>
      {items.length ? items.map((item) => <p key={item}>{item}</p>) : <p>No data returned.</p>}
    </div>
  );
}

function StatusDot({ ok }: { ok: boolean }) {
  return <span className={ok ? 'status-dot ok' : 'status-dot warn'} />;
}

function EmptyState({ text }: { text: string }) {
  return (
    <div className="empty-state">
      <ShieldCheck size={24} />
      <p>{text}</p>
    </div>
  );
}

function contains(value: string, query: string) {
  return value.toLowerCase().includes(query.trim().toLowerCase());
}

function wantsLogs(message: string) {
  const normalized = message.toLowerCase();
  return [
    'log',
    'trace',
    'stack',
    'rca',
    'analyze',
    'analysis',
    'fix',
    'failing',
    'failed',
    'crash',
    'restart',
    'probe',
    'error',
  ].some((token) => normalized.includes(token));
}

function remediationTitle(action: RemediationAction) {
  return action === 'ROLLOUT_RESTART_DEPLOYMENT'
    ? 'Rollout restart selected deployment'
    : 'Delete selected managed pod';
}

function remediationCommand(intent: RemediationIntent, namespace: string) {
  return intent.action === 'ROLLOUT_RESTART_DEPLOYMENT'
    ? `kubectl rollout restart deployment/${intent.targetName} -n ${namespace}`
    : `kubectl delete pod/${intent.targetName} -n ${namespace}`;
}

function defaultRemediationReason(action: RemediationAction, targetName: string) {
  return action === 'ROLLOUT_RESTART_DEPLOYMENT'
    ? `Human-approved restart for deployment ${targetName} after reviewing Aegis RCA signals.`
    : `Human-approved managed pod replacement for ${targetName} after reviewing Aegis RCA signals.`;
}

function formatTime(value?: string | null) {
  if (!value) return 'not observed';
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}

createRoot(document.getElementById('root')!).render(<App />);
