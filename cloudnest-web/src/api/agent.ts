import { api } from "./index";

export type AgentToolName =
  | "get_node_status"
  | "get_storage_capacity"
  | "get_node_events"
  | "get_active_storage_jobs"
  | "explain_storage_policy";

export interface AgentTool {
  name: AgentToolName;
  description: string;
  parameters: Record<string, unknown>;
  readOnly: boolean;
  requiredRole: string;
}
export interface AgentToolAvailability {
  enabled: boolean;
  mode: "MANUAL_READ_ONLY_TOOLS";
  maxResultBytes: number;
  tools: AgentTool[];
}
export interface AgentToolResult {
  invocationId: string;
  tool: AgentToolName;
  observedAt: number;
  source: "DATABASE_METADATA";
  consistency: "NON_ATOMIC_METADATA_READ";
  trustedAsInstructions: false;
  data: Record<string, unknown>;
}
export const agentTools = {
  available: async () =>
    (await api.get<AgentToolAvailability>("/admin/agent/tools/available")).data,
  invoke: async (name: AgentToolName, args: Record<string, unknown>) =>
    (await api.post<AgentToolResult>(`/admin/agent/tools/${name}/invoke`, args))
      .data,
};
