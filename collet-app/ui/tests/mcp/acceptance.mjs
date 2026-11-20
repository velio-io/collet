import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { appendFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer } from "node:http";

// This client knows only MCP. Recipes, fragments and dependency coordinates come from the server.
const endpoint = process.env.COLLET_MCP_URL || "http://127.0.0.1:8091/mcp";
const textOnly = process.argv.includes("--text-only");
const toolsOnly = textOnly || process.argv.includes("--tools-only");
const trial = mkdtempSync(join(tmpdir(), "collet-mcp-acceptance-"));
const client = new Client({
  name: textOnly ? "text-only-agent" : "new-agent",
  version: "1",
});
const measurements = [];
const completionLags = [];
let calls = 0;
function fromText(text) {
  if (text.includes("```")) return { text };
  try {
    return JSON.parse(text);
  } catch {}
  const saved = text.match(/^Pipeline (.+) version (\d+)\n/);
  if (saved)
    return {
      name: saved[1],
      version: Number(saved[2]),
      edn: text.slice(saved[0].length),
    };
  if (text.startsWith("Revision ")) {
    const first = text.indexOf("\n");
    return { revision: Number(text.split(" ")[1]), edn: text.slice(first + 1) };
  }
  const entries = text
    .split("\n")
    .filter((x) => /^[\w-]+: /.test(x))
    .map((line) => {
      const at = line.indexOf(": ");
      const value = line.slice(at + 2);
      try {
        return [line.slice(0, at), JSON.parse(value)];
      } catch {
        return [line.slice(0, at), value];
      }
    });
  return entries.length ? Object.fromEntries(entries) : { text };
}
async function call(name, args = {}, expectError = false) {
  const start = performance.now();
  const result = await client.callTool(
    { name: `collet_${name}`, arguments: args },
    undefined,
    { timeout: 120000 },
  );
  const bytes = Buffer.byteLength(JSON.stringify(result));
  const ms = Math.round(performance.now() - start);
  calls++;
  measurements.push({ tool: name, bytes, ms });
  appendFileSync(
    join(trial, "transcript.jsonl"),
    JSON.stringify({ name, args, result, bytes, ms }) + "\n",
  );
  assert.equal(Boolean(result.isError), expectError, JSON.stringify(result));
  if (!["get_draft", "get_pipeline"].includes(name))
    assert.ok(bytes <= 16384, `${name}: ${bytes} bytes`);
  // Intentionally ignore structuredContent in the text-only scenario.
  const value = textOnly
    ? fromText(result.content.map((x) => x.text || "").join("\n"))
    : result.structuredContent;
  if (
    name === "get_execution" &&
    args.waitMs &&
    value.status === "completed" &&
    value.runId &&
    Number.isFinite(value.finishedAt)
  ) {
    const lag = Date.now() - value.finishedAt;
    completionLags.push(lag);
    assert.ok(lag < 2000, `Completed run notification was ${lag} ms late`);
  }
  return value;
}
async function wait(job, extra = {}) {
  for (let n = 0; n < 40; n++) {
    job = await call("get_execution", {
      id: job.id,
      waitMs: 10000,
      afterSequence: job.sequence || 0,
      ...extra,
    });
    if (["completed", "failed", "stopped"].includes(job.status)) {
      assert.equal(job.status, "completed", JSON.stringify(job));
      return job;
    }
  }
  throw new Error("Execution did not settle");
}
// Use the advertised field exactly; unknown aliases are intentionally rejected by the server.
async function task(draft, name) {
  return wait(
    await call("execute", {
      scope: "task",
      id: draft.id,
      revision: draft.revision,
      configurationRevision: draft.configurationRevision,
      instanceId: draft.instanceId,
      idempotencyKey: crypto.randomUUID(),
      "task-name": name,
    }),
    { view: "result" },
  );
}
async function savedRun(draft) {
  const saved = await call("save_draft", {
    id: draft.id,
    revision: draft.revision,
  });
  const definition = await call("get_pipeline", {
    name: saved.savedPipeline.name,
    version: saved.savedVersion,
  });
  assert.equal(definition.version, saved.savedVersion);
  assert.equal(definition.name, saved.savedPipeline.name);
  assert.ok(definition.edn.includes(":name"));
  const command = {
    scope: "pipeline",
    pipeline: saved.savedPipeline.name,
    version: saved.savedVersion,
    configuration: {
      source: "draft",
      id: draft.id,
      revision: draft.configurationRevision,
    },
    instanceId: draft.instanceId,
    idempotencyKey: crypto.randomUUID(),
  };
  const job = await call("execute", command);
  assert.equal((await call("execute", command)).id, job.id);
  return wait(job);
}
const source = createServer((_req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.end('[{"id":1,"amount":12},{"id":2,"amount":8}]');
});
try {
  await client.connect(new StreamableHTTPClientTransport(new URL(endpoint)));
  assert.match(client.getInstructions(), /collet_get_overview/);
  const tools = (await client.listTools()).tools;
  for (const name of ["list_fixtures", "get_fixture", "save_fixture"]) {
    assert.ok(tools.some((tool) => tool.name === `collet_${name}`));
  }
  if (!toolsOnly) {
    const resources = await client.listResources();
    assert.ok(resources.resources.length > 20);
    assert.ok(
      (await client.readResource({ uri: "collet://docs/getting-started" }))
        .contents.length,
    );
    assert.ok(
      (await client.readResource({ uri: "collet://schemas/action" })).contents
        .length,
    );
  }
  const overview = await call("get_overview");
  const hello = await call("create_draft", overview.example.createDraft);
  assert.equal((await task(hello, "hello")).sample.value, 42);
  assert.ok(calls <= 8, `First successful task took ${calls} calls`);
  const firstSuccessCalls = calls;
  async function recipe(section) {
    let text = "";
    let cursor;
    do {
      const page = await call("read_documentation", {
        id: "recipes",
        section,
        ...(cursor ? { cursor } : {}),
      });
      text += page.text;
      cursor = page.nextCursor;
    } while (cursor);
    const source = text.match(/```clojure\n([\s\S]*?)```/);
    assert.ok(source, `Missing recipe ${section}`);
    return source[1];
  }
  const salesRecipe = await recipe("paid-orders-and-daily-totals");
  const sensorRecipe = await recipe("row-iteration-and-retry");
  const sales = await call("create_draft", { edn: salesRecipe });
  await task(sales, "orders");
  const inputs = await call("inspect_context", {
    id: sales.id,
    "task-name": "totals",
  });
  assert.ok(inputs.fields.length);
  assert.equal(inputs.sources[0].representation, "dataset-batches");
  const invalid = await call("validate", {
    kind: "pipeline",
    edn: salesRecipe.replace(":sum :amount", ":summ :amount"),
  });
  assert.equal(invalid.valid, false);
  assert.ok(invalid.errors.some((x) => x.path.includes("columns")));
  const totals = await task(sales, "totals");
  assert.deepEqual(
    totals.sample.value.map((x) => x.total).sort((a, b) => a - b),
    [9, 20],
  );
  const salesRun = await savedRun(sales);
  const savedTotals = await call("get_run", {
    id: salesRun.runId,
    view: "result",
    "task-name": "totals",
  });
  assert.deepEqual(
    savedTotals.sample.value.map((x) => x.total).sort((a, b) => a - b),
    [9, 20],
  );
  const sensor = await call("create_draft", {
    edn: sensorRecipe,
    configEdn:
      '{:readings [{:sensor "A" :celsius 0} {:sensor "B" :celsius 10}]}',
  });
  assert.deepEqual(
    (await task(sensor, "convert")).sample.value.map((x) => x.fahrenheit),
    [32, 50],
  );
  const sensorRun = await savedRun(sensor);
  assert.equal(sensorRun.configurationSource.source, "draft");
  await call("get_execution", { id: sensorRun.id, view: "events" });
  await call("get_execution", { id: sensorRun.id, view: "metrics" });
  await call("list_actions", { query: "fetch http" });
  const http = await call("describe_action", {
    type: "collet.actions.http/request",
  });
  const file = await call("describe_action", {
    type: "collet.actions.file/sink",
  });
  assert.ok(http.dependencyEdn && file.dependencyEdn);
  assert.match(http.version, /^\d/);
  await new Promise((resolve) => source.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${source.address().port}/orders`;
  const httpAction = http.example.replace(
    "https://example.test/orders?limit=20",
    url,
  );
  const fileAction = file.example
    .replace(":input [{:total 10}]", ":input rows")
    .replace(":params", ":selectors {rows [:state :orders]} :params")
    .replace('"totals.json"', JSON.stringify(join(trial, "totals.json")));
  const coordinates = [http, file]
    .map((x) => `[${x.coordinate} "${x.version}"]`)
    .join(" ");
  const ioDraft = await call("create_draft", {
    edn: `{:name :native-http-file :deps {:coordinates [${coordinates}] :requires [[${http.namespace}] [${file.namespace}]]} :tasks [{:name :export :actions [${httpAction} ${fileAction}]}]}`,
  });
  const exported = await task(ioDraft, "export");
  assert.equal(exported.status, "completed");
  await savedRun(ioDraft);
  await call("list_pipelines");
  await call("list_runs");
  console.log(
    JSON.stringify({
      passed: true,
      mode: textOnly ? "text-only" : toolsOnly ? "tools-only" : "SDK",
      firstSuccessCalls,
      calls,
      responseBytes: measurements.reduce((n, x) => n + x.bytes, 0),
      largestResponse: Math.max(...measurements.map((x) => x.bytes)),
      maxCompletionNotificationLagMs: Math.max(0, ...completionLags),
      medianMs: measurements.map((x) => x.ms).sort((a, b) => a - b)[
        Math.floor(measurements.length / 2)
      ],
      transcript: join(trial, "transcript.jsonl"),
    }),
  );
} finally {
  await client.close();
  source.close();
}
