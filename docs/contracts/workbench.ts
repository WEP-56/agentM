/** agentM design contract v1. Types only; no Android or mock implementation. */
export type AgentId = 'claude' | 'codex' | 'opencode' | 'pi' | 'dsh';
export type EntryKind = 'terminal' | 'web';
export type EngineId = 'proot' | 'proroot';
export type EnvironmentPhase =
  | 'absent' | 'checking' | 'installing' | 'ready' | 'repairRequired' | 'maintenance';
export type PackagePhase =
  | 'absent' | 'installing' | 'installed' | 'updating' | 'removing' | 'broken';
export type SessionPhase =
  | 'created' | 'starting' | 'awaitingAuth' | 'ready' | 'stopping'
  | 'exited' | 'failed' | 'lost';
export type OperationPhase =
  | 'queued' | 'running' | 'waitingForUser' | 'cancelling'
  | 'succeeded' | 'failed' | 'cancelled';
export type ErrorCode =
  | 'ENV_NOT_READY' | 'UNSUPPORTED_DEVICE' | 'UNSUPPORTED_VERSION'
  | 'INSUFFICIENT_STORAGE' | 'DOWNLOAD_FAILED' | 'HASH_MISMATCH'
  | 'INSTALL_FAILED' | 'NATIVE_PAYLOAD_MISSING' | 'PTY_UNAVAILABLE'
  | 'SESSION_LIMIT' | 'PORT_UNAVAILABLE' | 'START_FAILED' | 'AUTH_REQUIRED'
  | 'STOP_UNCONFIRMED' | 'CONFIG_INVALID' | 'CONFIG_CONFLICT'
  | 'CONFIG_FORMAT_UNSUPPORTED' | 'MAINTENANCE_BUSY' | 'PERMISSION_DENIED'
  | 'STALE_GENERATION' | 'BRIDGE_UNAVAILABLE' | 'INTERNAL';
export interface AppError {
  code: ErrorCode;
  message: string; // Redacted, user-readable; never raw command env or token URL.
  retryable: boolean;
  operationId?: string;
  logRef?: string;
}
export type Result<T> = { ok: true; value: T } | { ok: false; error: AppError };
export interface Capability {
  supported: boolean;
  reason?: string;
}
export interface Probe {
  name: string;
  status: 'unknown' | 'running' | 'passed' | 'failed';
  detail?: string;
  checkedAt?: string; // ISO 8601 UTC
}
export interface Environment {
  id: string;
  phase: EnvironmentPhase;
  engine: EngineId;
  actualEngine?: EngineId;
  fallbackReason?: string;
  distribution: 'ubuntu';
  distributionVersion?: string;
  imageVersion?: string;
  architecture: 'arm64';
  probes: Probe[];
  availableBytes?: number;
  lastError?: AppError;
}
export interface AgentDefinition {
  id: AgentId;
  name: string;
  entry: EntryKind;
  packageName: string;
  adapterVersion: string;
  install: Capability;
  config: Capability;
  // Candidate metadata is not Android compatibility evidence.
  validation: 'unverified' | 'experimental' | 'deviceVerified';
}
export interface AgentInstallation {
  agentId: AgentId;
  phase: PackagePhase;
  version?: string;
  recipeId?: string;
  source?: 'managed' | 'external';
  currentOperationId?: string;
  lastError?: AppError;
}
export interface Workspace {
  id: string;
  name: string;
  guestPath: string;
  storage: 'private';
}
export interface Session {
  id: string;
  agentId: AgentId;
  generation: number;
  environmentId: string;
  workspaceId: string;
  entry: EntryKind;
  phase: SessionPhase;
  desiredState: 'running' | 'stopped';
  packageVersion: string;
  configRevision: string;
  startedAt?: string;
  endedAt?: string;
  actualPort?: number;
  exitCode?: number;
  lastError?: AppError;
  // Native PID/birth identity and authentication URLs remain native-only.
}
export interface Operation {
  id: string;
  kind: 'environmentInstall' | 'environmentRepair' | 'environmentReinstall'
    | 'agentInstall' | 'agentUpdate' | 'agentUninstall'
    | 'sessionStart' | 'sessionStop' | 'configApply' | 'backup' | 'restore';
  targetId: string;
  phase: OperationPhase;
  stage: string;
  completedBytes?: number;
  totalBytes?: number; // Omit when unknown; don't invent percentages.
  progress?: number; // 0..1, based on measured work or declared stage weights.
  cancellable: boolean;
  createdAt: string;
  updatedAt: string;
  resultSessionId?: string;
  lastError?: AppError;
}
export type ProviderProtocol =
  | 'anthropic' | 'openaiResponses' | 'openaiChat' | 'native';
export interface ProviderProfile {
  id: string;
  agentId: AgentId;
  name: string;
  protocol: ProviderProtocol;
  baseUrl?: string;
  modelId?: string;
  authMode: 'apiKey' | 'nativeLogin';
  hasSecret: boolean;
  secretRef?: string; // Opaque handle, not a key or encrypted key payload.
  revision: string;
  nativeRevision?: string;
  applyState: 'draft' | 'applied' | 'pending' | 'conflict' | 'unknown';
  nativeSelection: 'selected' | 'available' | 'unknown';
}
export interface ProviderDraft {
  id?: string;
  agentId: AgentId;
  name: string;
  protocol: ProviderProtocol;
  baseUrl?: string;
  modelId?: string;
  authMode: 'apiKey' | 'nativeLogin';
  secretRef?: string;
  expectedProfileRevision?: string;
}
export interface ConfigPlan {
  id: string;
  agentId: AgentId;
  expectedNativeRevision: string;
  expiresAt: string;
  files: { guestPath: string; redactedDiff: string }[];
  affectedSessionIds: string[];
  effect: 'nextLaunch' | 'nativeSelectionRequired';
  warnings: string[];
}
export interface MaintenancePlan {
  id: string;
  action: 'repair' | 'reinstall';
  affectedSessionIds: string[];
  requiredBytes: number;
  availableBytes: number;
  preserve: string[];
  replace: string[];
  backupRequired: boolean;
  expiresAt: string;
}
export interface Settings {
  theme: 'system' | 'light' | 'dark';
  autoPrepareEnvironment: boolean;
  restoreWebServices: boolean;
  maxConcurrentSessions: number;
}
export interface Snapshot {
  protocolVersion: 1;
  mode: 'preview' | 'native';
  epoch: string; // Changes when the event stream authority restarts.
  sequence: number;
  environment: Environment;
  definitions: AgentDefinition[];
  installations: AgentInstallation[];
  workspaces: Workspace[];
  sessions: Session[];
  operations: Operation[];
  providers: ProviderProfile[];
  settings: Settings;
}
export type WorkbenchEvent = {
  epoch: string;
  sequence: number;
  timestamp: string;
} & (
  | { type: 'environmentChanged'; value: Environment }
  | { type: 'installationChanged'; value: AgentInstallation }
  | { type: 'sessionChanged'; value: Session }
  | { type: 'operationChanged'; value: Operation }
  | { type: 'providerChanged'; value: ProviderProfile }
  | { type: 'snapshotInvalidated' }
);
export interface TaskAck { operationId: string }
export interface Commands {
  getSnapshot: { input: Record<string, never>; output: Snapshot };
  prepareEnvironment: { input: { recipeId: string }; output: TaskAck };
  installAgent: { input: { agentId: AgentId; recipeId: string }; output: TaskAck };
  updateAgent: { input: { agentId: AgentId; recipeId: string }; output: TaskAck };
  uninstallAgent: { input: { agentId: AgentId; preserveData: true }; output: TaskAck };
  startSession: {
    input: { agentId: AgentId; workspaceId: string };
    output: TaskAck;
  };
  openSessionView: {
    input: { sessionId: string; generation: number };
    output: { opened: true }; // Native opens the correct terminal/WebView.
  };
  stopSession: {
    input: { sessionId: string; generation: number };
    output: TaskAck;
  };
  cancelOperation: { input: { operationId: string }; output: TaskAck };
  saveProviderDraft: { input: ProviderDraft; output: ProviderProfile };
  // Native origin only, in-memory payload, never log/cache; preview uses fake keys.
  storeSecret: { input: { value: string }; output: { secretRef: string } };
  planConfig: { input: { profileId: string; expectedProfileRevision: string }; output: ConfigPlan };
  applyConfig: {
    input: { planId: string; runningPolicy: 'whenStopped' | 'stopAndApply' };
    output: TaskAck;
  };
  planMaintenance: { input: { action: 'repair' | 'reinstall' }; output: MaintenancePlan };
  runMaintenance: { input: { confirmedPlanId: string }; output: TaskAck };
  readLogs: {
    input: { targetId: string; cursor?: string; limit: number };
    output: { lines: string[]; nextCursor?: string };
  };
  updateSettings: { input: Partial<Settings>; output: Settings };
  openPermissionSettings: {
    input: { permission: 'notifications' | 'batteryOptimization' | 'installPackages' };
    output: { opened: boolean };
  };
}
export interface WorkbenchGateway {
  request<K extends keyof Commands>(
    command: K,
    input: Commands[K]['input'],
    requestId: string, // Idempotency token; retransmission must not repeat a mutation.
  ): Promise<Result<Commands[K]['output']>>;
  subscribe(listener: (event: WorkbenchEvent) => void): () => void;
}
export type PreviewScenario =
  | 'fresh' | 'partial-install' | 'ready' | 'terminal-active' | 'web-auth'
  | 'config-conflict' | 'low-storage' | 'port-busy' | 'process-lost'
  | 'maintenance' | 'rollback' | 'permission-denied';
