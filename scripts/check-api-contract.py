"""Check the maintained OpenAPI against this project's Spring controller routes.

This is a source-level guard for the mapping style used here, not a Java parser
or a substitute for HTTP integration tests. It never rewrites the contract.
"""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
METHODS = {"get", "post", "put", "patch", "delete"}


def controller_routes():
    routes = set()
    for source in (ROOT / "cloudnest-server/src/main/java").rglob("*.java"):
        text = source.read_text(encoding="utf-8")
        if "@RestController" not in text and "@Controller" not in text:
            continue
        prefix = text.split("public class ", 1)[0]
        base = re.search(r'@RequestMapping\("([^"\n]*)"\)', prefix)
        base = base[1] if base else ""
        for match in re.finditer(
            r'@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"\n]*)"\))?', text
        ):
            path = base + (match[2] or "")
            if not path.startswith("/api/"):
                continue
            # Spring's preview/download alternation is two concrete OpenAPI paths.
            variants = [path]
            if "{action:preview|download}" in path:
                variants = [path.replace("{action:preview|download}", x)
                            for x in ("preview", "download")]
            routes.update((match[1].lower(), p[4:]) for p in variants)
    return routes


def check():
    spec = json.loads((ROOT / "docs/openapi.json").read_text(encoding="utf-8"))
    operations = {(method, path): op for path, item in spec["paths"].items()
                  for method, op in item.items() if method in METHODS}
    actual = controller_routes()
    errors = []
    for method, path in sorted(actual - operations.keys()):
        errors.append(f"Undocumented route: {method.upper()} {path}")
    for method, path in sorted(operations.keys() - actual):
        errors.append(f"No controller route: {method.upper()} {path}")
    seen_ids = set()
    for (method, path), op in operations.items():
        expected = set(re.findall(r"\{([^}]+)\}", path))
        params = [p for p in op.get("parameters", []) if p.get("in") == "path"]
        if expected != {p["name"] for p in params} or any(
            not p.get("required") for p in params
        ):
            errors.append(f"Invalid path parameters: {method} {path}")
        operation_id = op.get("operationId")
        if not operation_id or operation_id in seen_ids:
            errors.append(f"Missing/duplicate operationId: {method} {path}")
        seen_ids.add(operation_id)

    # Regressions caused by the removed exporter overwriting newer definitions.
    required_fields = {
        ("post", "/auth/login"): {"otp"},
        ("post", "/uploads/init"): {"targetId", "expectedRevision"},
        ("post", "/ai/models"): {"purpose", "embeddingDimensions", "maxBatchSize", "requestDimensions"},
        ("delete", "/user/access/mfa"): {"password", "otp"},
    }
    for key, fields in required_fields.items():
        schema = operations.get(key, {}).get("requestBody", {}).get("content", {}).get(
            "application/json", {}).get("schema", {})
        if not fields <= schema.get("properties", {}).keys():
            errors.append(f"Missing request fields: {key}")
    for path, field, value in [
        ("/admin/updates", "action", "backup"),
        ("/entries/upload-name", "policy", "replace"),
    ]:
        schema = operations.get(("post", path), {}).get("requestBody", {}).get(
            "content", {}).get("application/json", {}).get("schema", {})
        if value not in schema.get("properties", {}).get(field, {}).get("enum", []):
            errors.append(f"Missing enum value: {path} {field}={value}")
    tool_path = "/admin/agent/tools/{name}/invoke"
    tool_op = operations.get(("post", tool_path), {})
    tool_names = {"get_node_status", "get_storage_capacity", "get_node_events",
                  "get_active_storage_jobs", "explain_storage_policy"}
    name_param = next((p for p in tool_op.get("parameters", []) if p.get("name") == "name"), {})
    if set(name_param.get("schema", {}).get("enum", [])) != tool_names:
        errors.append("Agent tool allowlist differs from the maintained contract")
    if tool_op.get("security") != [{"cookieAuth": []}]:
        errors.append("Agent tools must document session authentication")
    result = spec.get("components", {}).get("schemas", {}).get("AgentToolResult", {})
    if result.get("properties", {}).get("trustedAsInstructions", {}).get("enum") != [False]:
        errors.append("Agent evidence must be documented as untrusted instructions")
    for error in errors:
        print(error)
    print(f"Checked {len(actual)} controller operations; {len(errors)} issue(s).")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(check())
