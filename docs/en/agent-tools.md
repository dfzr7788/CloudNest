# Read-only Agent tools

[简体中文](../agent-tools.md) · [English home](../../README.en.md)

The manual diagnostic console exposes administrator-only read-only tools.
Manual queries do not call models or modify files and nodes. AI provider configuration
is not required. Optional [Agent runs and approvals](agent-runtime.md) use the same tool boundary.

Set `AGENT_TOOLS_ENABLED=true` in the deployment environment and recreate the
application using the normal procedure for your verified source or image revision.
The default is false. Open **Node center → Read-only diagnostics**, choose a tool,
set the record limit, and run the query. Node event queries also need the backend
identifier (`local`, `minio`, or `s3:POLICY_ID`).

Available tools read recorded node health, capacity and reservations, node events,
active storage jobs, and the current placement policy. They do not probe remote
nodes. Missing events remain empty. An unset budget returns null remaining budget;
an admin budget does not measure provider disk space. `hasMore=true` means additional
records exist beyond the bounded view.

The console escapes text such as node names, preserves results on language changes,
and clears old evidence when a new query fails. Results are kept only on the page.
Successful invocation metadata is logged without parameters or result bodies; this
does not provide durable Agent run or step auditing.

The APIs are `GET /api/admin/agent/tools/available` and
`POST /api/admin/agent/tools/{name}/invoke`. Both require an active administrator
session; POST also requires CSRF. Discovery returns strict parameter schemas.
JSON bodies are limited to 4 KiB with no duplicate keys or trailing JSON. A record
limit of 1–100 defaults to 20. Up to 30 registry invocation attempts per administrator
per minute are allowed; responses are limited to 32 KiB and are not cached.

Evidence reports its database source, query time and non-atomic consistency. Actual
health-check time is a separate field. Private file names, object paths, credentials,
user identifiers and raw provider errors are excluded from tool data.
See the [OpenAPI contract](../openapi.json) for response types.

The subsequent [bounded runtime](agent-runtime.md) provides model tool negotiation and durable steps using existing AI tables. Human proposals use only the isolated development schema. Production schema changes and stable release remain subject to the [existing acceptance gates](../features.md).
