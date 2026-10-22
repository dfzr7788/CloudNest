# Bounded Agent runtime and human approvals

This is a development preview. See the [feature limitations (Chinese)](../features.md#功能边界).

Enable `AI_ENABLED`, `AGENT_TOOLS_ENABLED` and `AGENT_RUNTIME_ENABLED` on an isolated instance.
Configure an administrator-owned chat model and destination consent in AI settings, then open
**Node center → Agent runs**. The page separately asks consent to send the objective and diagnostic
metadata to that provider. Calls may incur charges.

Runs reuse `ai_task(kind=AGENT)` and `ai_call`, without a default production schema migration.
The runtime persists bounded steps and observations, returns the same run for the same idempotent request,
and retains own administrator runs for seven days. It does not reuse old diagnostic success as a fresh run.

Limits: two minutes from creation including queue time, six model rounds, eight tools, at most 1024 output
tokens per round, conservative cumulative reservation of 32768, and 48 KB persisted result size.
Known usage above the reservation increases the total. Missing usage stays unknown; this is not a currency cap.
The first request forces `get_node_status`. Plain text instead of actual tool calls yields `DEGRADED_QA`
without fabricated evidence or another paid request. Rejected tool parameters fail without automatic fallback.

Tool names, IDs, arguments, caller permission and feature state are validated at execution boundaries.
Duplicate IDs/JSON keys, trailing JSON, unknown tools and unauthorized access fail. Evidence is committed
before another model round. Cancel stops the active HTTP call. Timeouts, errors and restart never implicitly
replay paid calls; interrupted runs fail and unfinished billing records become unknown.
Model text is labeled inference and escaped; actual observations are stored separately. The fixed tool and
authorization boundary constrains actions, without claiming that model language itself can never be influenced.

Human approvals are disabled by default and can be enabled in runtime settings.
V20 belongs to the canonical schema history; **connected-dev** enables capabilities only.
Use an independent database/data directory for development checks.
The model may only simulate metadata or save a pending proposal using explicit own file IDs, kind and target.
It cannot approve or execute storage actions. Administrators cannot act on other users' files through this API.

**Action approvals** shows deterministic plans with revisions, checksums, target/domain/admission constraints,
usage, reservations and proposal hashes. Plans expire after ten minutes. Human approval reauthenticates and
recomputes under the existing user/storage locks; changed inputs or budgets require a fresh proposal.
Approval only queues existing storage jobs, is idempotent, and returns the same job IDs when repeated.
`SUBMITTED` does not mean copying succeeded. Inspect actual job completion and readback; the existing worker
copies, checks SHA-256 and retains the source. Simulation is metadata-only and reserves no capacity.

See [API/OpenAPI](../api.md), [manual tool contract](agent-tools.md), and the detailed
[implementation boundaries](../agent-runtime.md). Real providers, dual-node readback, MySQL and small-VPS
measurements remain acceptance work. Ordinary-user file agents, multi-worker fencing and price-based budgets
are not implemented.
