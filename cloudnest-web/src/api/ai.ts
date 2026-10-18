import { api } from "./index";
export interface AiConnection {
  id: string;
  name: string;
  base_url: string;
  enabled: boolean;
  config_version: number;
  keyConfigured: boolean;
  destinationFingerprint: string;
  consented: boolean;
  last_test_status: string;
}
export interface AiModel {
  purpose?: "CHAT" | "EMBEDDING";
  embedding_dimensions?: number | null;
  max_batch_size?: number | null;
  request_dimensions?: boolean;
  last_test_status?: string;
  last_test_at?: number | null;
  id: string;
  name: string;
  connection_id: string;
  model_id: string;
  context_limit: number;
  max_output: number;
  output_param_mode: string;
  enabled: boolean;
  config_version: number;
}
export interface AiCoverage {
  revision: number;
  status: string;
  partial: boolean;
  reason: string;
  processedCharacters: number;
  selectedCharacters: number;
  processedPages: number;
  totalPages: number | null;
  summaryCalls: number;
  tagCalls: number;
  sources: { sourceId: string; locator: string }[];
}
export interface AiTask {
  taskId: string;
  id: string;
  kind: string;
  status: string;
  stage: string;
  cancel_requested: boolean;
  model_name: string;
  model_id: string;
  created_at: number;
  completed_calls: number;
  total_calls: number | null;
  result: {
    text?: string;
    tags?: string[];
    dimensions?: number;
    vectorCount?: number;
    notice?: string;
  } | null;
  coverage: AiCoverage | null;
  error_message: string | null;
  error_code: string | null;
}
export interface AiConversation {
  id: string;
  file_id: string;
  revision: number;
  currentRevision: number;
  model_name: string;
  model_id: string;
  destination: string;
  readOnly: boolean;
  created_at: number;
  updated_at: number;
  activeTaskId: string | null;
  messages?: {
    id: string;
    task_id: string;
    role: string;
    content: string;
    status: string;
    error_message: string | null;
    scope?: { partial: boolean; selectedCharacters: number };
    citations: { id: string; source_id: string; locator: string }[];
  }[];
}
export interface AiCitation {
  id: string;
  source_id: string;
  locator: string;
  excerpt: string;
  revision: number;
  readOnly: boolean;
  fileId: string;
}
export const ai = {
  conversations: (fileId: string, page = 1) =>
    api
      .get<AiConversation[]>("/ai/conversations", { params: { fileId, page } })
      .then((r) => r.data),
  conversation: (id: string) =>
    api.get<AiConversation>(`/ai/conversations/${id}`).then((r) => r.data),
  createConversation: (input: object) =>
    api.post<AiConversation>("/ai/conversations", input).then((r) => r.data),
  deleteConversation: (id: string) => api.delete(`/ai/conversations/${id}`),
  question: (id: string, input: object, key: string) =>
    api
      .post<AiTask>(`/ai/conversations/${id}/messages`, input, {
        headers: { "Idempotency-Key": key },
      })
      .then((r) => r.data),
  citation: (cid: string, id: string) =>
    api
      .get<AiCitation>(`/ai/conversations/${cid}/citations/${id}`)
      .then((r) => r.data),
  capabilities: () =>
    api.get<{ enabled: boolean }>("/ai/capabilities").then((r) => r.data),
  connections: () =>
    api.get<AiConnection[]>("/ai/connections").then((r) => r.data),
  models: () => api.get<AiModel[]>("/ai/models").then((r) => r.data),
  preferences: () =>
    api
      .get<{ default_chat_model_id: string | null }>("/ai/preferences")
      .then((r) => r.data),
  task: (id: string) => api.get<AiTask>(`/ai/tasks/${id}`).then((r) => r.data),
  tasks: (fileId?: string, page = 1, analysisOnly = false) =>
    api
      .get<AiTask[]>("/ai/tasks", { params: { fileId, page, analysisOnly } })
      .then((r) => r.data),
  cancel: (id: string) =>
    api.post<AiTask>(`/ai/tasks/${id}/cancel`).then((r) => r.data),
  test: (id: string, key: string) =>
    api
      .post<AiTask>(
        `/ai/models/${id}/test`,
        {},
        { headers: { "Idempotency-Key": key } },
      )
      .then((r) => r.data),
  embeddingTest: (id: string, key: string) =>
    api
      .post<AiTask>(
        `/ai/models/${id}/embedding-test`,
        {},
        { headers: { "Idempotency-Key": key } },
      )
      .then((r) => r.data),
  analysis: (id: string, modelId: string, rangeChars: number) =>
    api
      .get<AiCoverage>(`/ai/files/${id}/analysis`, {
        params: { modelId, rangeChars },
      })
      .then((r) => r.data),
  generate: (id: string, kind: string, input: object, key: string) =>
    api
      .post<AiTask>(`/ai/files/${id}/${kind}`, input, {
        headers: { "Idempotency-Key": key },
      })
      .then((r) => r.data),
};
export const aiFinished = (task: AiTask) =>
  ["SUCCEEDED", "FAILED", "CANCELLED", "STALE"].includes(task.status);
export const aiStage = (task: AiTask) =>
  task.cancel_requested && !aiFinished(task)
    ? "正在停止"
    : {
        QUEUED: "排队中",
        STARTING: "准备中",
        EXTRACTING: "提取中",
        GENERATING: "生成中",
        MERGING: "合并中",
        SUCCEEDED: "已完成",
        FAILED: "失败",
        CANCELLED: "已停止",
        STALE: "已过期",
      }[aiFinished(task) ? task.status : task.stage] || task.stage;
