import assert from "node:assert/strict";
const base = process.env.COLLET_URL || "http://127.0.0.1:8091";
let session;
async function rpc(method, params = {}) {
  const r = await fetch(base + "/mcp", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      ...(session
        ? { "Mcp-Session-Id": session, "MCP-Protocol-Version": "2025-11-25" }
        : {}),
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  session ||= r.headers.get("Mcp-Session-Id");
  const body = await r.text();
  const parsed = JSON.parse(body.match(/^data: (.+)$/m)?.[1] || body);
  assert.equal(r.status, 200, body);
  assert.equal(parsed.error, undefined, body);
  return parsed.result;
}
const page = await (await fetch(base)).text();
assert.match(page, /Collet Workbench/);
const asset = page.match(/src="([^"]+\.js)"/)[1];
assert.equal((await fetch(base + asset)).status, 200);
assert.equal(
  (
    await rpc("initialize", {
      protocolVersion: "2025-11-25",
      capabilities: {},
      clientInfo: { name: "docker-probe", version: "1" },
    })
  ).protocolVersion,
  "2025-11-25",
);
await fetch(base + "/mcp", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    "Mcp-Session-Id": session,
  },
  body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
});
assert.equal((await rpc("tools/list")).tools.length, 25);
for (const uri of [
  "collet://docs/getting-started",
  "collet://docs/recipes",
  "collet://schemas/action",
  "collet://actions/collet.actions.http/request",
])
  assert.ok((await rpc("resources/read", { uri })).contents.length);
const action = await rpc("tools/call", {
  name: "collet_describe_action",
  arguments: { type: "collet.actions.http/request" },
});
assert.equal(action.isError, false);
assert.equal(action.structuredContent.version, "0.0.0-local");
assert.ok(action.structuredContent.dependencyEdn);
assert.equal(action.structuredContent["dependency-present"], false);
console.log(
  JSON.stringify({
    passed: true,
    ui: true,
    tools: 25,
    resources: true,
    compatibilityVersion: action.structuredContent.version,
    optionalRuntimePresent: action.structuredContent["dependency-present"],
  }),
);
