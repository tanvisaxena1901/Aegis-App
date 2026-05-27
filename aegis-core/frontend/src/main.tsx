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

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

function apiUrl(path: string) {
  return `${API_BASE_URL}${path}`;
}

function isGitHubPagesWithoutApiBase() {
  return !API_BASE_URL && window.location.hostname.endsWith('github.io');
}

function chatFailureMessage(error: unknown) {
  if (isGitHubPagesWithoutApiBase()) {
    return 'Aegis backend API is not configured for GitHub Pages. Set VITE_API_BASE_URL to the public backend URL, allow this Pages origin in backend CORS, then redeploy Pages.';
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'The chat request timed out before the backend answered. The dashboard is still usable; check that the backend and AI service are running, then retry.';
  }
  if (error instanceof TypeError && error.message.toLowerCase().includes('failed to fetch')) {
    return API_BASE_URL
      ? `Cannot reach Aegis backend at ${API_BASE_URL}. Make sure the backend is running, reachable from this browser, uses HTTPS for GitHub Pages, and allows this origin in CORS.`
      : 'Cannot reach Aegis backend because no API base URL is configured. For local dev, start the backend on port 8080. For GitHub Pages, set VITE_API_BASE_URL to a public HTTPS backend URL and redeploy.';
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Aegis backend API is unavailable. Check VITE_API_BASE_URL, backend reachability, and CORS.';
}

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

type SafetyMode = 'READ_ONLY' | 'APPROVAL_REQUIRED' | 'AUTONOMOUS_DISABLED';

type PlatformSafetyStatus = {
  mode: SafetyMode;
  canInspect: boolean;
  canMutateWithApproval: boolean;
  autonomousActionsEnabled: boolean;
  approvalRequired: boolean;
  guardrails: string[];
  observedAt: string;
};

type WatcherResourceStatus = {
  resource: string;
  scope: string;
  observedObjects: number;
  resourceVersion: string;
  status: string;
  lastError: string;
  observedAt: string;
};

type WatcherStatus = {
  enabled: boolean;
  watchedResources: string[];
  resources: WatcherResourceStatus[];
  observedAt: string;
};

type RemediationAction =
  | 'ROLLOUT_RESTART_DEPLOYMENT'
  | 'RESTART_MANAGED_POD'
  | 'DELETE_MANAGED_POD'
  | 'ROLLBACK_DEPLOYMENT'
  | 'SCALE_DEPLOYMENT'
  | 'PATCH_RESOURCE_LIMITS';

type RemediationIntent = {
  action: RemediationAction;
  targetName: string;
  replicas?: number;
  containerName?: string;
  cpuLimit?: string;
  memoryLimit?: string;
};

type RemediationPlan = {
  action: RemediationAction;
  namespace: string;
  targetName: string;
  commandPreview: string;
  risk: string;
  namespaceRisk: string;
  riskScore: number;
  dryRunCommand: string;
  dryRunPatch: string;
  dryRunDiff: string;
  approvalRequired: boolean;
  executableInCurrentMode: boolean;
  deterministicChecks: string[];
  guardrails: string[];
  generatedAt: string;
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

type RunbookStep = {
  order: number;
  title: string;
  command: string;
  expectedSignal: string;
};

type RunbookRecommendation = {
  action: RemediationAction;
  targetKind: string;
  targetName: string;
  reason: string;
};

type RunbookResponse = {
  incidentType: string;
  title: string;
  summary: string;
  deterministicSteps: RunbookStep[];
  suggestedActions: RunbookRecommendation[];
  aiEscalationPrompt: string;
  generatedAt: string;
};

type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

type NamespaceRiskProfile = {
  namespace: string;
  riskLevel: RiskLevel;
  riskScoreModifier: number;
  approvalRequiredForAllMutations: boolean;
  policyReasons: string[];
};

type ClusterContext = {
  clusterId: string;
  displayName: string;
  environment: string;
  current: boolean;
  status: string;
};

type IncidentTimelineItem = {
  timestamp: string;
  stage: string;
  reason: string;
  involvedObject: string;
  message: string;
};

type DeduplicatedEvent = {
  reason: string;
  type: string;
  involvedObject: string;
  message: string;
  occurrences: number;
  lastSeen: string;
};

type PodRestartPattern = {
  namespace: string;
  podName: string;
  pattern: string;
  severity: RiskLevel;
  restarts: number;
  evidence: string[];
};

type GoldenSignal = {
  service: string;
  latencyMs: number;
  trafficRpm: number;
  errorRatePercent: number;
  saturationPercent: number;
  sloTarget: string;
  incidentPriority: string;
  impact: string;
};

type DriftSignal = {
  kind: string;
  namespace: string;
  name: string;
  field: string;
  expectedValue: string;
  actualValue: string;
  severity: RiskLevel;
};

type RbacPermission = {
  capability: string;
  verb: string;
  resource: string;
  allowed: boolean;
  reason: string;
};

type RbacScanResponse = {
  clusterId: string;
  permissions: RbacPermission[];
  error: string;
  observedAt: string;
};

type RunbookCodeDefinition = {
  incidentType: string;
  version: string;
  steps: string[];
  aiAfter: string[];
};

type DeploymentSpecChange = {
  field: string;
  previousValue: string;
  currentValue: string;
  impact: string;
};

type DeploymentDiffResponse = {
  clusterId: string;
  namespace: string;
  deploymentName: string;
  baselineCaptured: boolean;
  changes: DeploymentSpecChange[];
  observedAt: string;
};

type IncidentReplayResponse = {
  replayId: string;
  title: string;
  clusterId: string;
  namespace: string;
  timeline: IncidentTimelineItem[];
  restartPatterns: PodRestartPattern[];
  deduplicatedEvents: DeduplicatedEvent[];
  investigationSteps: string[];
  loadedAt: string;
};

type PlatformIntelligenceResponse = {
  clusterId: string;
  clusters: ClusterContext[];
  namespaceRisk: NamespaceRiskProfile;
  timeline: IncidentTimelineItem[];
  deduplicatedEvents: DeduplicatedEvent[];
  restartPatterns: PodRestartPattern[];
  goldenSignals: GoldenSignal[];
  drift: DriftSignal[];
  rbac: RbacScanResponse;
  runbooksAsCode: RunbookCodeDefinition[];
  error: string;
  observedAt: string;
};

type RuntimeEvent = {
  streamId: string;
  eventType: string;
  incidentId: string;
  workflowId: string;
  service: string;
  payload: Record<string, string>;
  createdAt: string;
};

type WorkflowStepExecution = {
  stepId: string;
  stepType: string;
  agentType: string;
  status: string;
  attempt: number;
  maxAttempts: number;
  output: string;
  evidence: string[];
  startedAt: string | null;
  completedAt: string | null;
  nextAttemptAt: string | null;
};

type AgentExecution = {
  executionId: string;
  workflowId: string;
  stepId: string;
  agentType: string;
  status: string;
  inputSignals: string[];
  output: string;
  startedAt: string | null;
  completedAt: string | null;
};

type RetryState = {
  workflowId: string;
  stepId: string;
  attempt: number;
  maxAttempts: number;
  backoffMillis: number;
  nextAttemptAt: string | null;
  lastError: string;
};

type WorkflowExecution = {
  workflowId: string;
  incidentId: string;
  service: string;
  namespace: string;
  clusterId: string;
  symptom: string;
  signals: string[];
  currentStep: string;
  status: string;
  steps: WorkflowStepExecution[];
  agentExecutions: AgentExecution[];
  retries: RetryState[];
  createdAt: string;
  updatedAt: string;
};

type MemoryNode = {
  id: string;
  label: string;
  type: string;
  properties: Record<string, string>;
  observedAt: string;
};

type MemoryEdge = {
  from: string;
  to: string;
  relationship: string;
  weight: number;
};

type OperationalMemoryGraph = {
  nodes: MemoryNode[];
  edges: MemoryEdge[];
  causalPaths: string[];
  storageMode: string;
  observedAt: string;
};

type RuntimeStatusResponse = {
  eventBus: string;
  stateStore: string;
  memoryGraph: string;
  queuedEvents: number;
  recentEvents: RuntimeEvent[];
  workflows: WorkflowExecution[];
  memory: OperationalMemoryGraph;
  observedAt: string;
};

type RuntimeDispatchResponse = {
  incidentId: string;
  workflowId: string;
  eventStreamId: string;
  status: string;
  acceptedSignals: string[];
  createdAt: string;
};

type AgentRegistryEntry = {
  agentType: string;
  displayName: string;
  ownsSteps: string[];
  runtime: string;
  responsibility: string;
};

type LoadState = 'idle' | 'loading' | 'ready' | 'error';
type Tab = 'overview' | 'workloads' | 'events' | 'logs' | 'ai' | 'platform' | 'runtime';

const namespace = 'aegis';
const clusterId = 'dev-cluster';

const quickQuestions = [
  'Which environment has issues?',
  'Which pods are failing?',
  'Why is the selected pod unhealthy?',
  'What should I check next?',
];

function App() {
  const [snapshot, setSnapshot] = useState<ClusterSnapshot | null>(null);
  const [snapshotState, setSnapshotState] = useState<LoadState>('idle');
  const [safety, setSafety] = useState<PlatformSafetyStatus | null>(null);
  const [safetyState, setSafetyState] = useState<LoadState>('idle');
  const [watcher, setWatcher] = useState<WatcherStatus | null>(null);
  const [watcherState, setWatcherState] = useState<LoadState>('idle');
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
  const [chatError, setChatError] = useState('');
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
  const [remediationPlan, setRemediationPlan] = useState<RemediationPlan | null>(null);
  const [remediationReason, setRemediationReason] = useState('');
  const [remediationConfirmation, setRemediationConfirmation] = useState('');
  const [remediationState, setRemediationState] = useState<LoadState>('idle');
  const [remediationResult, setRemediationResult] = useState<RemediationResponse | null>(null);
  const [remediationError, setRemediationError] = useState('');
  const [runbook, setRunbook] = useState<RunbookResponse | null>(null);
  const [runbookState, setRunbookState] = useState<LoadState>('idle');
  const [platformIntelligence, setPlatformIntelligence] = useState<PlatformIntelligenceResponse | null>(null);
  const [platformState, setPlatformState] = useState<LoadState>('idle');
  const [deploymentDiff, setDeploymentDiff] = useState<DeploymentDiffResponse | null>(null);
  const [deploymentDiffState, setDeploymentDiffState] = useState<LoadState>('idle');
  const [incidentReplay, setIncidentReplay] = useState<IncidentReplayResponse | null>(null);
  const [incidentReplayState, setIncidentReplayState] = useState<LoadState>('idle');
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatusResponse | null>(null);
  const [runtimeAgents, setRuntimeAgents] = useState<AgentRegistryEntry[]>([]);
  const [runtimeState, setRuntimeState] = useState<LoadState>('idle');
  const [runtimeDispatch, setRuntimeDispatch] = useState<RuntimeDispatchResponse | null>(null);
  const [runtimeError, setRuntimeError] = useState('');

  useEffect(() => {
    void refreshAll();
  }, []);

  useEffect(() => {
    if (!selectedPod && pods.length > 0) {
      setSelectedPod(pods[0].name);
    }
  }, [pods, selectedPod]);

  useEffect(() => {
    if (pods.length || deployments.length || events.length) {
      void loadRunbook();
    }
  }, [selectedPod, selectedDeployment, pods.length, deployments.length, events.length]);

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
  const mutationBlocked = safety ? !safety.canMutateWithApproval : false;
  const refreshing = [snapshotState, safetyState, watcherState, environmentState, operationsState, detailState].includes('loading');
  const namespaceRisk = platformIntelligence?.namespaceRisk;

  async function refreshAll() {
    await Promise.all([
      loadSafety(),
      loadWatcher(),
      loadSnapshot(),
      loadEnvironmentHealth(),
      loadOperations(),
      loadDeploymentDetail(selectedDeployment),
      loadPlatformIntelligence(),
      loadDeploymentDiff(selectedDeployment),
      loadRuntimeStatus(),
      loadRuntimeAgents(),
    ]);
  }

  async function loadSafety() {
    setSafetyState('loading');
    try {
      const response = await fetch(apiUrl('/api/platform/safety'));
      if (!response.ok) throw new Error('safety failed');
      setSafety(await response.json());
      setSafetyState('ready');
    } catch {
      setSafetyState('error');
    }
  }

  async function loadWatcher() {
    setWatcherState('loading');
    try {
      const response = await fetch(apiUrl('/api/kubernetes/watcher/status'));
      if (!response.ok) throw new Error('watcher failed');
      setWatcher(await response.json());
      setWatcherState('ready');
    } catch {
      setWatcherState('error');
    }
  }

  async function loadPlatformIntelligence() {
    setPlatformState('loading');
    try {
      const response = await fetch(apiUrl(`/api/platform-intelligence/namespaces/${namespace}?clusterId=${clusterId}`));
      if (!response.ok) throw new Error('platform intelligence failed');
      setPlatformIntelligence(await response.json());
      setPlatformState('ready');
    } catch {
      setPlatformState('error');
    }
  }

  async function loadDeploymentDiff(deploymentName = selectedDeployment) {
    if (!deploymentName) return;
    setDeploymentDiffState('loading');
    try {
      const response = await fetch(apiUrl(`/api/platform-intelligence/namespaces/${namespace}/deployments/${deploymentName}/diff?clusterId=${clusterId}`));
      if (!response.ok) throw new Error('deployment diff failed');
      setDeploymentDiff(await response.json());
      setDeploymentDiffState('ready');
    } catch {
      setDeploymentDiffState('error');
    }
  }

  async function loadIncidentReplay() {
    setIncidentReplayState('loading');
    try {
      const response = await fetch(apiUrl('/api/platform-intelligence/replay/sample'));
      if (!response.ok) throw new Error('replay failed');
      setIncidentReplay(await response.json());
      setIncidentReplayState('ready');
      setActiveTab('platform');
    } catch {
      setIncidentReplayState('error');
    }
  }

  async function loadRuntimeStatus() {
    setRuntimeState('loading');
    try {
      const response = await fetch(apiUrl('/api/runtime/status'));
      if (!response.ok) throw new Error('runtime status failed');
      setRuntimeStatus(await response.json());
      setRuntimeState('ready');
    } catch {
      setRuntimeState('error');
    }
  }

  async function loadRuntimeAgents() {
    try {
      const response = await fetch(apiUrl('/api/runtime/agents'));
      if (!response.ok) throw new Error('runtime agents failed');
      setRuntimeAgents(await response.json());
    } catch {
      setRuntimeAgents([]);
    }
  }

  async function loadSnapshot() {
    setSnapshotState('loading');
    try {
      const response = await fetch(apiUrl('/api/cluster/snapshot'));
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
        fetch(apiUrl(`/api/kubernetes/namespaces/${namespace}/pods`)),
        fetch(apiUrl(`/api/kubernetes/namespaces/${namespace}/deployments`)),
        fetch(apiUrl(`/api/kubernetes/namespaces/${namespace}/events`)),
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
      const response = await fetch(apiUrl('/api/environments/health'));
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
        apiUrl(`/api/kubernetes/namespaces/${namespace}/deployments/${deploymentName}/describe`),
      );
      if (!response.ok) throw new Error('describe failed');
      setDeploymentDetail(await response.json());
      setDetailState('ready');
    } catch {
      setDetailState('error');
    }
  }

  async function loadRunbook() {
    const pod = pods.find((item) => item.name === selectedPod);
    const deployment = deployments.find((item) => item.name === selectedDeployment);
    const resourceKind = pod ? 'Pod' : 'Deployment';
    const resourceName = pod?.name ?? deployment?.name ?? selectedDeployment;
    if (!resourceName) return;
    const signals = [
      ...(pod?.statusReasons ?? []),
      ...(deployment ? [`ready ${deployment.readyReplicas}/${deployment.replicas}`, `available ${deployment.availableReplicas}/${deployment.replicas}`] : []),
      ...warningEvents.slice(0, 4).map((event) => `${event.reason}: ${event.message}`),
    ];
    setRunbookState('loading');
    try {
      const response = await fetch(apiUrl('/api/runbooks/evaluate'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          namespace,
          resourceKind,
          resourceName,
          symptom: signals.join(' ') || `Evaluate ${resourceKind} ${resourceName}`,
          signals,
        }),
      });
      if (!response.ok) throw new Error('runbook failed');
      setRunbook(await response.json());
      setRunbookState('ready');
    } catch {
      setRunbookState('error');
    }
  }

  async function loadPodLogs(podName = selectedPod) {
    if (!podName) return;
    setLogsState('loading');
    setActiveTab('logs');
    try {
      const response = await fetch(
        apiUrl(`/api/kubernetes/namespaces/${namespace}/pods/${podName}/logs?tailLines=160`),
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
      const response = await fetch(apiUrl('/api/incidents/investigate'), {
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

  async function createRuntimeIncident() {
    setRuntimeState('loading');
    setRuntimeError('');
    setActiveTab('runtime');
    const service = selectedDeployment || deployments[0]?.name || 'aegis-backend';
    const selectedPodSummary = pods.find((pod) => pod.name === selectedPod);
    const selectedDeploymentSummary = deployments.find((deployment) => deployment.name === service);
    const signals = [
      ...warningEvents.slice(0, 8).map((event) => `${event.type}/${event.reason}: ${event.message}`),
      ...(selectedPodSummary?.statusReasons ?? []),
      ...(selectedDeploymentSummary
        ? [
            `deployment replicas ${selectedDeploymentSummary.readyReplicas}/${selectedDeploymentSummary.replicas}`,
            `deployment images ${selectedDeploymentSummary.images.join(', ')}`,
          ]
        : []),
      `workload_readiness=${workloadReadiness}`,
      `namespace_restarts=${restartTotal}`,
    ].filter(Boolean);
    try {
      const response = await fetch(apiUrl('/api/runtime/incidents'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          service,
          namespace,
          clusterId,
          severity: criticalEnvironments ? 'HIGH' : unhealthyEnvironments.length ? 'MEDIUM' : 'LOW',
          symptom: warningEvents.length
            ? `Investigate ${service}: ${warningEvents[0].reason} in ${namespace}`
            : `Autonomous runtime investigation for ${service}`,
          signals,
        }),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Runtime incident dispatch failed' }));
        throw new Error(error.message || 'Runtime incident dispatch failed');
      }
      setRuntimeDispatch(await response.json());
      await loadRuntimeStatus();
    } catch (error) {
      setRuntimeError(error instanceof Error ? error.message : 'Runtime incident dispatch failed');
      setRuntimeState('error');
    }
  }

  async function askAegis() {
    const message = chatQuestion.trim();
    if (!message) return;
    const includeLogs = wantsLogs(message);
    setChatState('loading');
    setChatError('');
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
    } catch (error) {
      const failureMessage = chatFailureMessage(error);
      setChatQuestion(message);
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: failureMessage,
          severity: 'LOW',
        },
      ]);
      setChatError(failureMessage);
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
    setChatError('');
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
    } catch (error) {
      const failureMessage = chatFailureMessage(error);
      setChatQuestion(trimmed);
      setChatHistory((history) => [
        ...history,
        {
          role: 'assistant',
          content: failureMessage,
          severity: 'LOW',
        },
      ]);
      setChatError(failureMessage);
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
    if (isGitHubPagesWithoutApiBase()) {
      throw new Error('Aegis backend API is not configured for GitHub Pages.');
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 90000);
    try {
      const response = await fetch(apiUrl('/api/chat'), {
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
      if (!response.ok) {
        const error = await response.json().catch(() => ({ message: 'Aegis chat API request failed.' }));
        throw new Error(error.message || 'Aegis chat API request failed.');
      }
      return await response.json() as ChatResponse;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function selectDeployment(name: string) {
    setSelectedDeployment(name);
    setActiveTab('workloads');
    void loadDeploymentDetail(name);
    void loadDeploymentDiff(name);
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
      const response = await fetch(apiUrl('/api/terminal/run'), {
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

  function openRemediation(action: RemediationAction, recommendation?: RunbookRecommendation) {
    const targetName = recommendation?.targetName && !recommendation.targetName.startsWith('<')
      ? recommendation.targetName
      : deploymentAction(action)
        ? selectedDeployment
        : selectedPod;
    if (!targetName) return;
    const nextIntent: RemediationIntent = {
      action,
      targetName,
      replicas: action === 'SCALE_DEPLOYMENT' ? deployments.find((item) => item.name === targetName)?.replicas ?? 2 : undefined,
      containerName: action === 'PATCH_RESOURCE_LIMITS' ? firstContainerName() : undefined,
      cpuLimit: action === 'PATCH_RESOURCE_LIMITS' ? '500m' : undefined,
      memoryLimit: action === 'PATCH_RESOURCE_LIMITS' ? '512Mi' : undefined,
    };
    setRemediationIntent(nextIntent);
    setRemediationPlan(null);
    setRemediationReason(defaultRemediationReason(action, targetName));
    setRemediationConfirmation('');
    setRemediationState('idle');
    setRemediationError('');
    setRemediationResult(null);
    void loadRemediationPlan(nextIntent);
  }

  async function loadRemediationPlan(intent: RemediationIntent) {
    try {
      const response = await fetch(apiUrl('/api/remediation/plan'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(remediationPayload(intent, false)),
      });
      if (!response.ok) throw new Error('plan failed');
      setRemediationPlan(await response.json());
    } catch {
      setRemediationPlan(null);
    }
  }

  async function executeRemediation() {
    if (!remediationIntent) return;
    setRemediationState('loading');
    setRemediationError('');
    try {
      const response = await fetch(apiUrl('/api/remediation/execute'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(remediationPayload(remediationIntent, true)),
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

  function remediationPayload(intent: RemediationIntent, approved: boolean) {
    return {
      action: intent.action,
      namespace,
      targetName: intent.targetName,
      reason: remediationReason || defaultRemediationReason(intent.action, intent.targetName),
      approved: approved && remediationConfirmation.trim() === 'APPROVE',
      confirmation: approved ? remediationConfirmation : 'PLAN_ONLY',
      replicas: intent.replicas,
      containerName: intent.containerName,
      cpuLimit: intent.cpuLimit,
      memoryLimit: intent.memoryLimit,
    };
  }

  function firstContainerName() {
    const fromDetail = deploymentDetail?.containers[0]?.split(' uses ')[0];
    const fromPod = pods.find((pod) => pod.name === selectedPod)?.containers[0];
    return fromDetail || fromPod || selectedDeployment;
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
          <button className="secondary-action" onClick={() => void createRuntimeIncident()} disabled={runtimeState === 'loading'}>
            <ShieldCheck size={17} />
            {runtimeState === 'loading' ? 'Dispatching' : 'Runtime'}
          </button>
        </div>
      </section>

      <section className="metric-grid" aria-label="Cluster metrics">
        <MetricCard icon={<Gauge />} label="Workload readiness" value={`${workloadReadiness}%`} trend={`${readyDeployments}/${deployments.length || 0} deployments`} tone="green" />
        <MetricCard icon={<Server />} label="Running pods" value={`${runningPods}/${pods.length || 0}`} trend={`${restartTotal} restarts`} tone="blue" />
        <MetricCard icon={<AlertTriangle />} label="Warning events" value={warningEvents.length} trend={`last scan ${observedAt}`} tone={warningEvents.length ? 'amber' : 'green'} />
        <MetricCard icon={<Layers3 />} label="Namespace risk" value={namespaceRisk?.riskLevel ?? 'MEDIUM'} trend={namespaceRisk ? `+${namespaceRisk.riskScoreModifier} remediation risk` : 'profile loading'} tone={namespaceRisk?.riskLevel === 'HIGH' ? 'amber' : 'violet'} />
        <MetricCard icon={<Layers3 />} label="Watcher resources" value={watcher?.resources.length ?? 0} trend={(watcher?.watchedResources ?? []).join(', ') || 'pods, deployments, events'} tone="violet" />
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
          {(['overview', 'workloads', 'events', 'logs', 'ai', 'platform', 'runtime'] as Tab[]).map((tab) => (
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
            {chatState === 'error' && <p className="inline-error">{chatError || 'Aegis chat is unavailable.'}</p>}
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
            <button className="action-tile danger-aware" onClick={() => openRemediation('RESTART_MANAGED_POD')} disabled={!selectedPod}>
              <Trash2 size={18} />
              <strong>Restart managed pod</strong>
              <span>{selectedPod || 'Select a pod first'} with approval.</span>
            </button>
            <button className="action-tile danger-aware" onClick={() => openRemediation('ROLLBACK_DEPLOYMENT')} disabled={!selectedDeployment}>
              <RotateCw size={18} />
              <strong>Rollback deployment</strong>
              <span>{selectedDeployment || 'Select a deployment first'} after rollout checks.</span>
            </button>
            <button className="action-tile danger-aware" onClick={() => openRemediation('SCALE_DEPLOYMENT')} disabled={!selectedDeployment}>
              <Gauge size={18} />
              <strong>Scale deployment</strong>
              <span>{selectedDeployment || 'Select a deployment first'} with bounded replicas.</span>
            </button>
          </div>
          {remediationIntent && (
            <div className="remediation-box">
              <div>
                <span>AI-assisted remediation</span>
                <strong>{remediationTitle(remediationIntent.action)}</strong>
                <p>{remediationPlan?.commandPreview ?? remediationCommand(remediationIntent, namespace)}</p>
                {remediationPlan && <small>{remediationPlan.risk} / {remediationPlan.namespaceRisk} namespace / risk score {remediationPlan.riskScore}</small>}
              </div>
              {remediationPlan && (
                <div className="dry-run-box">
                  <span>Server dry-run</span>
                  <code>{remediationPlan.dryRunCommand}</code>
                  <pre>{remediationPlan.dryRunDiff}</pre>
                </div>
              )}
              {remediationPlan?.deterministicChecks.length ? (
                <div className="approval-checks">
                  {remediationPlan.deterministicChecks.map((check) => <p key={check}>{check}</p>)}
                </div>
              ) : null}
              {remediationIntent.action === 'SCALE_DEPLOYMENT' && (
                <label>
                  Replicas
                  <input
                    type="number"
                    min={0}
                    max={50}
                    value={remediationIntent.replicas ?? 1}
                    onChange={(event) => setRemediationIntent({ ...remediationIntent, replicas: Number(event.target.value) })}
                  />
                </label>
              )}
              {remediationIntent.action === 'PATCH_RESOURCE_LIMITS' && (
                <div className="limit-grid">
                  <label>
                    Container
                    <input
                      value={remediationIntent.containerName ?? ''}
                      onChange={(event) => setRemediationIntent({ ...remediationIntent, containerName: event.target.value })}
                    />
                  </label>
                  <label>
                    CPU limit
                    <input
                      value={remediationIntent.cpuLimit ?? ''}
                      onChange={(event) => setRemediationIntent({ ...remediationIntent, cpuLimit: event.target.value })}
                    />
                  </label>
                  <label>
                    Memory limit
                    <input
                      value={remediationIntent.memoryLimit ?? ''}
                      onChange={(event) => setRemediationIntent({ ...remediationIntent, memoryLimit: event.target.value })}
                    />
                  </label>
                </div>
              )}
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
                  disabled={mutationBlocked || remediationState === 'loading' || remediationConfirmation.trim() !== 'APPROVE'}
                >
                  {mutationBlocked ? 'Blocked by safety mode' : remediationState === 'loading' ? 'Executing' : 'Approve and execute'}
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
              title="Kubernetes watcher"
              detail={watcherState === 'error' ? 'Watcher cannot reach the cluster' : `Watching ${(watcher?.watchedResources ?? []).join(', ')}`}
              action={
                <button className="ghost-action" onClick={() => void loadWatcher()}>
                  <RotateCw size={15} />
                  Reload
                </button>
              }
            />
            <div className="watcher-grid">
              {(watcher?.resources ?? []).map((resource) => (
                <div className="watcher-card" key={resource.resource}>
                  <span className={resource.status === 'WATCHING' ? 'watching' : 'watch-error'}>{resource.status}</span>
                  <strong>{resource.resource}</strong>
                  <p>{resource.observedObjects} objects / {resource.scope}</p>
                  <code>rv {resource.resourceVersion || 'unavailable'}</code>
                  {resource.lastError && <small>{resource.lastError}</small>}
                </div>
              ))}
              {!watcher?.resources.length && <EmptyState text="Watcher status has not been collected yet." />}
            </div>
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
              title="Runbook engine"
              detail={runbookState === 'loading' ? 'Evaluating deterministic checks' : runbook?.title ?? 'Deterministic triage first'}
              action={
                <button className="ghost-action" onClick={() => void loadRunbook()}>
                  <RotateCw size={15} />
                  Reload
                </button>
              }
            />
            {runbook ? (
              <div className="runbook-grid">
                <div className="runbook-summary">
                  <span>{runbook.incidentType.replace(/_/g, ' ')}</span>
                  <strong>{runbook.title}</strong>
                  <p>{runbook.summary}</p>
                  <small>{runbook.aiEscalationPrompt}</small>
                </div>
                <div className="runbook-steps">
                  {runbook.deterministicSteps.map((step) => (
                    <div className="runbook-step" key={`${step.order}-${step.title}`}>
                      <b>{step.order}</b>
                      <div>
                        <strong>{step.title}</strong>
                        <code>{step.command}</code>
                        <p>{step.expectedSignal}</p>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="runbook-actions">
                  <h3>Suggested approvals</h3>
                  {runbook.suggestedActions.length ? runbook.suggestedActions.map((action) => (
                    <button className="action-tile danger-aware" key={`${action.action}-${action.targetName}`} onClick={() => openRemediation(action.action, action)}>
                      <ShieldCheck size={18} />
                      <strong>{remediationTitle(action.action)}</strong>
                      <span>{action.reason}</span>
                    </button>
                  )) : <p>No remediation suggested before more evidence is collected.</p>}
                </div>
              </div>
            ) : (
              <EmptyState text="Select a workload or pod to evaluate the matching runbook." />
            )}
          </article>
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

      {activeTab === 'platform' && (
        <section className="dashboard-grid">
          <article className="panel span-2">
            <PanelTitle
              title="Multi-cluster context"
              detail={platformState === 'error' ? 'Platform intelligence API unavailable' : `Selected ${platformIntelligence?.clusterId ?? clusterId}`}
              action={
                <button className="ghost-action" onClick={() => void loadPlatformIntelligence()}>
                  <RotateCw size={15} />
                  Reload
                </button>
              }
            />
            <div className="cluster-strip">
              {(platformIntelligence?.clusters ?? []).map((cluster) => (
                <div className={cluster.current ? 'cluster-pill active' : 'cluster-pill'} key={cluster.clusterId}>
                  <strong>{cluster.displayName}</strong>
                  <span>{cluster.clusterId} / {cluster.environment}</span>
                </div>
              ))}
              {!platformIntelligence?.clusters.length && <EmptyState text="Cluster contexts are not loaded yet." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Namespace risk profile" detail={namespaceRisk?.namespace ?? namespace} />
            {namespaceRisk ? (
              <div className="risk-profile">
                <span className={`risk-badge ${namespaceRisk.riskLevel.toLowerCase()}`}>{namespaceRisk.riskLevel}</span>
                <strong>+{namespaceRisk.riskScoreModifier} remediation risk</strong>
                {namespaceRisk.policyReasons.map((reason) => <p key={reason}>{reason}</p>)}
              </div>
            ) : <EmptyState text="Risk profile is loading." />}
          </article>

          <article className="panel">
            <PanelTitle title="RBAC permission scanner" detail={platformIntelligence?.rbac.error || 'SelfSubjectAccessReview'} />
            <div className="permission-list">
              {(platformIntelligence?.rbac.permissions ?? []).map((permission) => (
                <div className="permission-row" key={`${permission.capability}-${permission.resource}`}>
                  <StatusDot ok={permission.allowed} />
                  <div>
                    <strong>{permission.capability}</strong>
                    <span>{permission.verb} {permission.resource}</span>
                  </div>
                  <code>{permission.allowed ? 'yes' : 'no'}</code>
                </div>
              ))}
              {!platformIntelligence?.rbac.permissions.length && <EmptyState text="RBAC scan needs live cluster access." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Incident timeline" detail="Built from Kubernetes events" />
            <div className="timeline-list">
              {(incidentReplay?.timeline ?? platformIntelligence?.timeline ?? []).slice(0, 8).map((item) => (
                <div className="timeline-row" key={`${item.timestamp}-${item.stage}-${item.involvedObject}`}>
                  <time>{formatTime(item.timestamp)}</time>
                  <div>
                    <strong>{item.stage}</strong>
                    <span>{item.involvedObject} / {item.reason}</span>
                    <p>{item.message}</p>
                  </div>
                </div>
              ))}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Event deduplication" detail="Grouped noisy Kubernetes events" />
            <div className="event-stack">
              {(incidentReplay?.deduplicatedEvents ?? platformIntelligence?.deduplicatedEvents ?? []).slice(0, 6).map((event) => (
                <div className="dedupe-row" key={`${event.reason}-${event.involvedObject}`}>
                  <strong>{event.reason} x{event.occurrences}</strong>
                  <span>{event.involvedObject}</span>
                  <p>{event.message}</p>
                </div>
              ))}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Pod restart patterns" detail="Deterministic detection before AI" />
            <div className="pattern-list">
              {(incidentReplay?.restartPatterns ?? platformIntelligence?.restartPatterns ?? []).slice(0, 6).map((pattern) => (
                <div className="pattern-row" key={`${pattern.podName}-${pattern.pattern}`}>
                  <span className={`risk-badge ${pattern.severity.toLowerCase()}`}>{pattern.severity}</span>
                  <strong>{pattern.pattern}</strong>
                  <p>{pattern.podName} / {pattern.restarts} restarts</p>
                </div>
              ))}
              {!(incidentReplay?.restartPatterns.length || platformIntelligence?.restartPatterns.length) && <EmptyState text="No restart pattern detected." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Golden signals and SLO priority" detail="Latency, traffic, errors, saturation" />
            <div className="golden-grid">
              {(platformIntelligence?.goldenSignals ?? []).map((signal) => (
                <div className="golden-card" key={signal.service}>
                  <div>
                    <strong>{signal.service}</strong>
                    <span>{signal.incidentPriority} / SLO {signal.sloTarget}</span>
                  </div>
                  <Bar label="Latency" value={Math.round(signal.latencyMs)} total={500} />
                  <Bar label="Traffic" value={Math.round(signal.trafficRpm)} total={1000} />
                  <Bar label="Errors" value={Math.round(signal.errorRatePercent)} total={100} />
                  <Bar label="Saturation" value={Math.round(signal.saturationPercent)} total={100} />
                  <p>{signal.impact}</p>
                </div>
              ))}
              {!platformIntelligence?.goldenSignals.length && <EmptyState text="No service golden signals returned." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Deployment diff" detail={deploymentDiffState === 'loading' ? 'Loading diff' : selectedDeployment} />
            <div className="diff-list">
              {(deploymentDiff?.changes ?? []).map((change) => (
                <div className="diff-row" key={`${change.field}-${change.currentValue}`}>
                  <strong>{change.field}</strong>
                  <code>{change.previousValue} {'->'} {change.currentValue}</code>
                  <p>{change.impact}</p>
                </div>
              ))}
              {!deploymentDiff?.changes.length && <EmptyState text="No previous deployment snapshot captured yet." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Cluster drift and GitOps" detail="Live state vs desired manifests" />
            <div className="drift-list">
              {(platformIntelligence?.drift ?? []).slice(0, 8).map((drift) => (
                <div className="drift-row" key={`${drift.name}-${drift.field}`}>
                  <span className={`risk-badge ${drift.severity.toLowerCase()}`}>{drift.severity}</span>
                  <strong>{drift.name} / {drift.field}</strong>
                  <code>{drift.expectedValue} {'->'} {drift.actualValue}</code>
                </div>
              ))}
              {!platformIntelligence?.drift.length && <EmptyState text="No drift detected from local desired manifests." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Runbook-as-code" detail="YAML-backed deterministic steps" />
            <div className="runbook-code-list">
              {(platformIntelligence?.runbooksAsCode ?? []).map((definition) => (
                <div className="runbook-code" key={definition.incidentType}>
                  <strong>{definition.incidentType}</strong>
                  <span>{definition.version}</span>
                  <code>{definition.steps.join(' -> ')}</code>
                </div>
              ))}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle
              title="Incident replay mode"
              detail={incidentReplay?.title ?? 'Load the sample incident JSON'}
              action={
                <button className="primary-action compact-action" onClick={() => void loadIncidentReplay()} disabled={incidentReplayState === 'loading'}>
                  <Clock3 size={15} />
                  {incidentReplayState === 'loading' ? 'Loading' : 'Load Replay'}
                </button>
              }
            />
            {incidentReplay ? (
              <div className="replay-steps">
                {incidentReplay.investigationSteps.map((step, index) => (
                  <div className="runbook-step" key={step}>
                    <b>{index + 1}</b>
                    <div>
                      <strong>{step}</strong>
                      <p>{incidentReplay.replayId} / {incidentReplay.namespace}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : <EmptyState text="Load the replay to demo a deterministic investigation without a live incident." />}
          </article>
        </section>
      )}

      {activeTab === 'runtime' && (
        <section className="dashboard-grid">
          <article className="panel span-2">
            <PanelTitle
              title="Autonomous incident runtime"
              detail={runtimeStatus ? `${runtimeStatus.eventBus} / ${runtimeStatus.stateStore}` : 'Workflow runtime is loading'}
              action={
                <div className="panel-actions">
                  <button className="ghost-action" onClick={() => void loadRuntimeStatus()}>
                    <RotateCw size={15} />
                    Reload
                  </button>
                  <button className="primary-action compact-action" onClick={() => void createRuntimeIncident()} disabled={runtimeState === 'loading'}>
                    <ShieldCheck size={15} />
                    Create Incident
                  </button>
                </div>
              }
            />
            {runtimeError && <p className="inline-error">{runtimeError}</p>}
            {runtimeDispatch && (
              <div className="runtime-dispatch">
                <strong>{runtimeDispatch.incidentId}</strong>
                <span>{runtimeDispatch.workflowId} / stream {runtimeDispatch.eventStreamId}</span>
                <p>{runtimeDispatch.acceptedSignals.length} signals accepted into the runtime event stream.</p>
              </div>
            )}
            <div className="runtime-architecture">
              {['K8s Events', 'Event Bus', 'Workflow Runtime', 'AI Agents', 'Operational Memory', 'Remediation Engine'].map((stage) => (
                <div className="runtime-stage" key={stage}>
                  <span>{stage}</span>
                </div>
              ))}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Agent registry" detail="Stateful agent ownership" />
            <div className="agent-list">
              {runtimeAgents.map((agent) => (
                <div className="agent-row" key={agent.agentType}>
                  <strong>{agent.displayName}</strong>
                  <span>{agent.runtime}</span>
                  <p>{agent.responsibility}</p>
                  <code>{agent.ownsSteps.join(' -> ')}</code>
                </div>
              ))}
              {!runtimeAgents.length && <EmptyState text="Agent registry is not loaded." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Event stream" detail={`${runtimeStatus?.queuedEvents ?? 0} queued events`} />
            <div className="event-stack">
              {(runtimeStatus?.recentEvents ?? []).slice(0, 8).map((event) => (
                <div className="dedupe-row" key={event.streamId}>
                  <strong>{event.eventType}</strong>
                  <span>{event.service} / {event.workflowId}</span>
                  <p>{formatTime(event.createdAt)} / {event.streamId}</p>
                </div>
              ))}
              {!runtimeStatus?.recentEvents.length && <EmptyState text="No runtime events have been published yet." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Workflow persistence and recovery" detail={runtimeStatus?.workflows[0]?.status ?? 'No workflow yet'} />
            <div className="workflow-list">
              {(runtimeStatus?.workflows ?? []).map((workflow) => (
                <div className="workflow-card" key={workflow.workflowId}>
                  <div className="workflow-head">
                    <div>
                      <strong>{workflow.service}</strong>
                      <span>{workflow.incidentId} / {workflow.workflowId}</span>
                    </div>
                    <span className={`runtime-status ${workflow.status.toLowerCase()}`}>{workflow.status}</span>
                  </div>
                  <p>{workflow.symptom}</p>
                  <div className="step-track">
                    {workflow.steps.map((step) => (
                      <div className={`step-chip ${step.status.toLowerCase()}`} key={step.stepId}>
                        <b>{step.stepType}</b>
                        <span>{step.agentType} / attempt {step.attempt}/{step.maxAttempts}</span>
                      </div>
                    ))}
                  </div>
                  {workflow.retries.length ? (
                    <div className="retry-list">
                      {workflow.retries.map((retry) => (
                        <p key={`${retry.stepId}-${retry.attempt}`}>{retry.stepId}: retry {retry.attempt}/{retry.maxAttempts} at {formatTime(retry.nextAttemptAt)}</p>
                      ))}
                    </div>
                  ) : null}
                </div>
              ))}
              {!runtimeStatus?.workflows.length && <EmptyState text="Create an incident to start the stateful workflow runtime." />}
            </div>
          </article>

          <article className="panel">
            <PanelTitle title="Agent executions" detail="LangGraph tasks and fallbacks" />
            <div className="agent-execution-list">
              {(runtimeStatus?.workflows ?? []).flatMap((workflow) => workflow.agentExecutions).slice(0, 8).map((execution) => (
                <div className="agent-execution" key={execution.executionId}>
                  <strong>{execution.agentType}</strong>
                  <span>{execution.status} / {formatTime(execution.completedAt)}</span>
                  <p>{execution.output}</p>
                </div>
              ))}
              {!runtimeStatus?.workflows.some((workflow) => workflow.agentExecutions.length) && <EmptyState text="No agent execution records yet." />}
            </div>
          </article>

          <article className="panel span-2">
            <PanelTitle title="Operational memory graph" detail={runtimeStatus?.memory.storageMode ?? 'Neo4j/OpenSearch-ready graph'} />
            <div className="memory-grid">
              <div className="memory-stat">
                <span>Nodes</span>
                <strong>{runtimeStatus?.memory.nodes.length ?? 0}</strong>
              </div>
              <div className="memory-stat">
                <span>Edges</span>
                <strong>{runtimeStatus?.memory.edges.length ?? 0}</strong>
              </div>
              <div className="memory-stat">
                <span>Causal paths</span>
                <strong>{runtimeStatus?.memory.causalPaths.length ?? 0}</strong>
              </div>
            </div>
            <div className="causal-paths">
              {(runtimeStatus?.memory.causalPaths ?? []).slice(0, 10).map((path) => <code key={path}>{path}</code>)}
              {!runtimeStatus?.memory.causalPaths.length && <EmptyState text="Memory graph will populate when runtime incidents are created." />}
            </div>
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
  switch (action) {
    case 'ROLLOUT_RESTART_DEPLOYMENT':
      return 'Rollout restart selected deployment';
    case 'RESTART_MANAGED_POD':
    case 'DELETE_MANAGED_POD':
      return 'Restart selected managed pod';
    case 'ROLLBACK_DEPLOYMENT':
      return 'Rollback selected deployment';
    case 'SCALE_DEPLOYMENT':
      return 'Scale selected deployment';
    case 'PATCH_RESOURCE_LIMITS':
      return 'Patch deployment resource limits';
  }
}

function remediationCommand(intent: RemediationIntent, namespace: string) {
  switch (intent.action) {
    case 'ROLLOUT_RESTART_DEPLOYMENT':
      return `kubectl rollout restart deployment/${intent.targetName} -n ${namespace}`;
    case 'RESTART_MANAGED_POD':
    case 'DELETE_MANAGED_POD':
      return `kubectl delete pod/${intent.targetName} -n ${namespace}`;
    case 'ROLLBACK_DEPLOYMENT':
      return `kubectl rollout undo deployment/${intent.targetName} -n ${namespace}`;
    case 'SCALE_DEPLOYMENT':
      return `kubectl scale deployment/${intent.targetName} --replicas=${intent.replicas ?? 1} -n ${namespace}`;
    case 'PATCH_RESOURCE_LIMITS':
      return `kubectl set resources deployment/${intent.targetName} -c ${intent.containerName ?? '<container>'} --limits=cpu=${intent.cpuLimit ?? '<cpu>'},memory=${intent.memoryLimit ?? '<memory>'} -n ${namespace}`;
  }
}

function defaultRemediationReason(action: RemediationAction, targetName: string) {
  switch (action) {
    case 'ROLLOUT_RESTART_DEPLOYMENT':
      return `Human-approved restart for deployment ${targetName} after reviewing Aegis RCA signals.`;
    case 'RESTART_MANAGED_POD':
    case 'DELETE_MANAGED_POD':
      return `Human-approved managed pod replacement for ${targetName} after reviewing Aegis RCA signals.`;
    case 'ROLLBACK_DEPLOYMENT':
      return `Human-approved rollback for deployment ${targetName} after failed rollout checks.`;
    case 'SCALE_DEPLOYMENT':
      return `Human-approved scale change for deployment ${targetName} after capacity and scheduling checks.`;
    case 'PATCH_RESOURCE_LIMITS':
      return `Human-approved resource limit patch for deployment ${targetName} after OOM or capacity evidence.`;
  }
}

function deploymentAction(action: RemediationAction) {
  return [
    'ROLLOUT_RESTART_DEPLOYMENT',
    'ROLLBACK_DEPLOYMENT',
    'SCALE_DEPLOYMENT',
    'PATCH_RESOURCE_LIMITS',
  ].includes(action);
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
