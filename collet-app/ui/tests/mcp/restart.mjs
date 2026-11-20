import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
const [phase, file] = process.argv.slice(2);
assert.ok(
  ["record", "verify"].includes(phase) && file,
  "Usage: restart.mjs record|verify STATE_FILE",
);
const endpoint = process.env.COLLET_MCP_URL || "http://127.0.0.1:8091/mcp";
const client = new Client({ name: "restart-and-conflict-agent", version: "1" });
async function call(name, args = {}, error = false) {
  const result = await client.callTool({
    name: `collet_${name}`,
    arguments: args,
  });
  assert.equal(Boolean(result.isError), error, JSON.stringify(result));
  return result.structuredContent;
}
try {
  await client.connect(new StreamableHTTPClientTransport(new URL(endpoint)));
  const overview = await call("get_overview");
  if (phase === "record") {
    const draft = await call("create_draft", {
      edn: "{:unfinished",
      configEdn: '{:transient "not-persisted"}',
    });
    const competing = await Promise.all(
      ["; agent A\n{:unfinished", "; agent B\n{:unfinished"].map((edn) =>
        client.callTool({
          name: "collet_update_draft",
          arguments: { id: draft.id, revision: 0, edn },
        }),
      ),
    );
    assert.equal(competing.filter((x) => x.isError).length, 1);
    assert.equal(
      competing.find((x) => x.isError).structuredContent.error.currentRevision,
      1,
    );
    await call("restore_draft", { id: draft.id, revision: 1, fromRevision: 0 });
    assert.equal(
      (await call("get_draft_history", { id: draft.id })).revisions.length,
      3,
    );
    const runnable = await call("create_draft", overview.example.createDraft);
    const saved = await call("save_draft", { id: runnable.id, revision: 0 });
    const command = {
      scope: "pipeline",
      pipeline: saved.savedPipeline.name,
      version: saved.savedVersion,
      configuration: { source: "empty" },
      instanceId: overview.instanceId,
      idempotencyKey: crypto.randomUUID(),
    };
    let execution = await call("execute", command);
    for (let n = 0; n < 20 && execution.status !== "completed"; n++)
      execution = await call("get_execution", {
        id: execution.id,
        waitMs: 10000,
      });
    assert.equal(execution.status, "completed");
    writeFileSync(
      file,
      JSON.stringify({
        instance: overview.instanceId,
        draft: draft.id,
        source: "{:unfinished",
        revision: 2,
        execution: execution.id,
        run: execution.runId,
        command,
      }),
    );
    const oldProtocol = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json, text/event-stream",
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "older-client", version: "1" },
        },
      }),
    });
    const raw = await oldProtocol.text();
    const init = JSON.parse(raw.match(/^data: (.+)$/m)?.[1] || raw);
    assert.equal(init.result.protocolVersion, "2025-06-18");
    console.log(
      JSON.stringify({
        recorded: true,
        conflict: "one accepted, one recoverable",
        history: 3,
        protocol: "2025-06-18",
      }),
    );
  } else {
    const previous = JSON.parse(readFileSync(file, "utf8"));
    assert.notEqual(overview.instanceId, previous.instance);
    const draft = await call("get_draft", { id: previous.draft });
    assert.equal(draft.edn, previous.source);
    assert.equal(draft.revision, previous.revision);
    assert.equal(draft.configurationReady, false);
    assert.equal(
      (await call("get_draft_history", { id: previous.draft })).revisions
        .length,
      3,
    );
    assert.equal(
      (await call("get_execution", { id: previous.execution }, true)).error
        .code,
      "execution-unavailable",
    );
    assert.equal(
      (await call("execute", previous.command, true)).error.code,
      "stale-instance",
    );
    assert.equal(
      (await call("get_run", { id: previous.run })).status,
      "completed",
    );
    assert.equal(
      (await call("get_run", { id: previous.run, view: "events" })).available,
      false,
    );
    console.log(
      JSON.stringify({
        restored: true,
        configuration: "transient",
        oldStart: "rejected",
        durableRun: "completed",
      }),
    );
  }
} finally {
  await client.close();
}
