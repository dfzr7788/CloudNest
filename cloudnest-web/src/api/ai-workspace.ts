import { api } from "./index";
import type { AiTask } from "./ai";
export interface KnowledgeBase {
  id: string;
  name: string;
  model_id: string;
  scopeIds: string[];
  fileIds: string[];
  ready: boolean;
  needsUpdate: boolean;
  chunks: number;
  active_generation: string | null;
  pending_generation: string | null;
}
export interface Source {
  sourceId: string;
  file_id: string;
  revision: number;
  name: string;
  locator: string;
  text: string;
  score?: number;
}
export interface Suggestion {
  fileId: string;
  originalName: string;
  name: string;
  tags: string[];
  targetFolderId: string;
  reason: string;
}
export interface WorkspaceResult {
  text?: string;
  question?: string;
  notice?: string;
  sources?: Source[];
  remainingChunks?: number;
  suggestions?: Suggestion[];
  changes?: { type: string; line: number; text: string }[];
  invalidCitations?: boolean;
  partial?: boolean;
}
export type WorkspaceTask = Omit<AiTask, "result"> & {
  workspace_id?: string;
  conversation_id?: string;
  question?: string;
  result: WorkspaceResult | null;
};
export interface IndexPreview {
  digest: string;
  chunks: number;
  maxCalls: number;
  destination: string;
  notice: string;
  files: {
    fileId: string;
    name: string;
    revision: number;
    partial: boolean;
    reason: string;
  }[];
}
export interface KbSession {
  readOnly?: boolean;
  id: string;
  model_id: string;
  generation_id: string;
  updated_at: number;
}
export const workspace = {
  list: () =>
    api.get<KnowledgeBase[]>("/ai/knowledge-bases").then((r) => r.data),
  save: (id: string | null, body: object) =>
    (id
      ? api.patch<KnowledgeBase>(`/ai/knowledge-bases/${id}`, body)
      : api.post<KnowledgeBase>("/ai/knowledge-bases", body)
    ).then((r) => r.data),
  remove: (id: string) => api.delete(`/ai/knowledge-bases/${id}`),
  preview: (id: string) =>
    api
      .get<IndexPreview>(`/ai/knowledge-bases/${id}/index-preview`)
      .then((r) => r.data),
  job: (path: string, body: object, key: string) =>
    api
      .post<WorkspaceTask>(`/ai/${path}`, body, {
        headers: { "Idempotency-Key": key },
      })
      .then((r) => r.data),
  task: (id: string) =>
    api.get<WorkspaceTask>(`/ai/tasks/${id}`).then((r) => r.data),
  sessions: (id: string) =>
    api
      .get<KbSession[]>(`/ai/knowledge-bases/${id}/sessions`)
      .then((r) => r.data),
  createSession: (id: string, modelId: string) =>
    api
      .post<KbSession>(`/ai/knowledge-bases/${id}/sessions`, { modelId })
      .then((r) => r.data),
  history: (id: string) =>
    api
      .get<WorkspaceTask[]>(`/ai/knowledge-sessions/${id}`)
      .then((r) => r.data),
  deleteSession: (id: string) => api.delete(`/ai/knowledge-sessions/${id}`),
};
