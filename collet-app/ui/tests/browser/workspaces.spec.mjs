import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
const headers = { "X-Collet-Workbench": "1" };
const buttonContrast = (el) => {
  const style = getComputedStyle(el);
  const luminance = (color) =>
    color
      .match(/[\d.]+/g)
      .slice(0, 3)
      .map(Number)
      .map((x) => {
        const value = x / 255;
        return value <= 0.04045
          ? value / 12.92
          : ((value + 0.055) / 1.055) ** 2.4;
      })
      .reduce((sum, value, i) => sum + value * [0.2126, 0.7152, 0.0722][i], 0);
  const a = luminance(style.color),
    b = luminance(style.backgroundColor);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
};
const template = `{:name :workbench-ui-test :custom {:preserved true}
 :tasks [{:name :source :keep-state true
 :setup [{:name :base :type :clj/identity :params [4]}]
 :actions [{:name :first :type :clj/inc :selectors {n [:state :base]} :params [n]}
           {:name :second :type :clj/* :selectors {n [:state :first]} :params [n 2]}]}
 {:name :dependent :inputs [:source] :keep-state true
 :actions [{:name :first :type :clj/inc :selectors {n [:inputs :source]} :params [n]}]}]}`;
let id, pipeline, sessions, pageErrors;
const snapshot = async (request) =>
  (await request.get(`/api/sessions/${id}`)).json();
async function allDrafts(request) {
  const rows = [];
  let cursor;
  do {
    const page = await (
      await request.get(
        `/api/drafts?limit=100&maxBytes=65536${cursor ? `&cursor=${cursor}` : ""}`,
      )
    ).json();
    rows.push(...page.drafts);
    cursor = page.nextCursor;
  } while (cursor);
  return rows;
}
async function draftDeletion(page, name, draftId = id) {
  await page.getByRole("button", { name: "Manage", exact: true }).click();
  const manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await manager
    .getByRole("textbox", { name: "Filter cleanup list" })
    .fill(name);
  await manager
    .getByRole("button", {
      name: `Delete draft ${name} ${draftId.slice(0, 8)}`,
      exact: true,
    })
    .click();
  return page.getByRole("dialog", { name: "Delete draft", exact: true });
}
async function fixtureRun(page, request) {
  const draft = await snapshot(request);
  expect(
    (
      await request.post(`/api/sessions/${id}/save`, {
        headers,
        data: { revision: draft.revision },
      })
    ).ok(),
  ).toBeTruthy();
  const response = await request.post("/api/executions", {
    headers,
    data: {
      scope: "pipeline",
      pipeline: draft.document.name,
      version: 1,
      configuration: { source: "empty" },
      instanceId: draft.instanceId,
      idempotencyKey: randomUUID(),
    },
  });
  expect(response.ok()).toBeTruthy();
  const done = await waitJob(page, await response.json());
  expect(done.status).toBe("completed");
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Open saved revision 1", exact: true }),
  ).toBeVisible();
  return { draft, runId: done.runId };
}
const editor = (page, name = "Action") =>
  page.getByRole("textbox", { name: `${name} EDN editor`, exact: true });
async function replace(page, field, text) {
  await field.click();
  await field.press("ControlOrMeta+A");
  await page.keyboard.insertText(text);
  const name = await field.getAttribute("aria-label");
  await expect
    .poll(async () => {
      if (name === "Configuration EDN editor")
        return (await field.innerText()).trim() === text.trim();
      return page.evaluate(
        async ({ name, text }) => {
          const pending = JSON.parse(
            localStorage.getItem("collet.pending.restore") || "null",
          );
          const state =
            pending ||
            (await (
              await fetch(
                `/api/sessions/${localStorage.getItem("collet.session")}`,
              )
            ).json());
          const source = pending ? pending.edn : state.document.edn;
          return name === "Pipeline EDN editor"
            ? source === text
            : source.includes(text);
        },
        { name, text },
      );
    })
    .toBe(true);
}
async function canvas(page) {
  await page
    .getByRole("button", { name: "Collet Workbench", exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "Pipeline graph", exact: true }),
  ).toBeVisible();
}
async function task(page, name = "Source") {
  await canvas(page);
  await page
    .getByRole("region", { name: "Pipeline graph", exact: true })
    .getByRole("button", { name, exact: true })
    .click();
  await expect(
    page.getByRole("complementary", { name: "Task details" }),
  ).toBeVisible();
}
async function action(page, name = "first", taskName = "Source") {
  await task(page, taskName);
  await page.getByRole("button", { name: new RegExp(`^\\d ${name} `) }).click();
  await expect(editor(page)).toBeVisible();
}
async function source(page) {
  if (!(await editor(page, "Pipeline").isVisible()))
    await page.keyboard.press("ControlOrMeta+e");
  await expect(editor(page, "Pipeline")).toBeVisible();
}
async function choose(page, label) {
  const previousUrl = page.url();
  await page
    .getByRole("button", { name: "Switch pipeline", exact: true })
    .click();
  const dialog = page.getByRole("dialog", {
    name: "Switch pipeline",
    exact: true,
  });
  await dialog.getByRole("combobox").fill(label);
  const prefix = label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  await dialog
    .getByRole("option", { name: new RegExp(`^${prefix}`) })
    .first()
    .click();
  await expect.poll(() => page.url()).not.toBe(previousUrl);
}
async function waitJob(page, job) {
  await expect
    .poll(
      async () =>
        (await (await page.request.get(`/api/executions/${job.id}`)).json())
          .status,
    )
    .toMatch(/completed|failed/);
  return (await page.request.get(`/api/executions/${job.id}`)).json();
}
async function execute(page, scope = "action") {
  const response = page.waitForResponse(
    (r) =>
      r.url().endsWith("/execute") ||
      (r.url().endsWith("/source") && r.status() >= 400),
  );
  await page
    .getByRole("button", {
      name: scope === "prefix" ? "Run to here" : `Run ${scope}`,
      exact: true,
    })
    .click();
  const result = await response;
  if (result.ok()) {
    await waitJob(page, await result.json());
    await expect(
      page.getByRole("button", {
        name: scope === "prefix" ? "Run to here" : `Run ${scope}`,
        exact: true,
      }),
    ).toBeEnabled();
  }
}
async function apply(page) {
  const response = page.waitForResponse((r) => r.url().endsWith("/validate"));
  await page
    .getByRole("button", { name: "Check", exact: true })
    .first()
    .click();
  await response;
}
async function save(page) {
  const response = page.waitForResponse((r) => r.url().endsWith("/save"));
  await page.getByRole("button", { name: "Save version", exact: true }).click();
  return (await response).json();
}
async function runPipeline(page, version) {
  await page.getByRole("button", { name: "Run pipeline", exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: "Run pipeline",
    exact: true,
  });
  if (version != null) {
    await dialog.getByRole("combobox", { name: "Pipeline version" }).click();
    await page
      .getByRole("option", {
        name: new RegExp(`^Version ${version}( · latest)?$`),
      })
      .click();
  }
  const response = page.waitForResponse(
    (r) => r.url().endsWith("/executions") && r.request().method() === "POST",
  );
  await dialog
    .getByRole("button", { name: /^(Save & run|Run version)$/ })
    .click();
  const job = await waitJob(page, await (await response).json());
  return (await page.request.get(`/api/runs/${job.runId}`)).json();
}
async function waitRun(request, run) {
  await expect
    .poll(async () => {
      const r = await (await request.get(`/api/runs/${run.id}`)).json();
      return r.run["run/status"];
    })
    .toMatch(/done|failed/);
}

test.beforeEach(async ({ page, request }) => {
  sessions = new Set();
  pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  pipeline = template.replace(":workbench-ui-test", `:ui-${randomUUID()}`);
  const response = await request.post("/api/sessions", {
    headers,
    data: { edn: pipeline, configEdn: "{}" },
  });
  expect(response.ok()).toBeTruthy();
  const session = await response.json();
  id = session.id;
  sessions.add(id);
  page.on("response", async (response) => {
    if (
      response.request().method() === "POST" &&
      /\/api\/sessions$/.test(response.url())
    ) {
      const data = await response.json().catch(() => null);
      if (data?.id) sessions.add(data.id);
    }
  });
  await page.addInitScript((s) => {
    if (!localStorage.getItem("collet.session"))
      localStorage.setItem("collet.session", s.id);
  }, session);
  await page.goto("/");
  await expect(
    page.getByRole("region", { name: "Pipeline graph", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("status", { name: "Connected", exact: true }),
  ).toBeVisible();
});
test.afterEach(async ({ page, request }) => {
  const copies = await page
    .evaluate(() =>
      Object.keys(JSON.parse(localStorage.getItem("collet.drafts") || "{}")),
    )
    .catch(() => []);
  for (const sid of new Set([...sessions, ...copies]))
    await request.post(`/api/sessions/${sid}/close`, { headers, data: {} });
  expect(pageErrors).toEqual([]);
});

test("graph reload requests metadata and loads exact EDN when the source editor opens", async ({ page }) => {
  const base = `/api/sessions/${id}`;
  const textRequests = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path === `${base}/source` || path === `${base}/configuration`) textRequests.push(path);
  });
  const overview = page.waitForResponse((response) =>
    new URL(response.url()).pathname === base && response.request().method() === "GET"
  );
  await page.reload();
  await expect(page.getByRole("region", { name: "Pipeline graph" })).toBeVisible();
  const response = await overview;
  expect(response.request().headers()["x-collet-view"]).toBe("overview");
  const draft = await response.json();
  expect(draft.document.edn).toBeUndefined();
  expect(draft.document.tasks[0].actions[0].edn).toBeUndefined();
  expect(draft.configEdn).toBeUndefined();
  expect(textRequests).toEqual([]);
  const sourceResponse = page.waitForResponse((response) => new URL(response.url()).pathname === `${base}/source`);
  await source(page);
  expect((await (await sourceResponse).json()).edn).toBe(pipeline);
  await expect(editor(page, "Pipeline")).toContainText(`:${draft.document.name}`);
  expect(textRequests).toEqual([`${base}/source`]);
});

test("EDN formatting preserves comments, supports undo and leaves invalid text intact", async ({ page, request }) => {
  await source(page);
  const field = editor(page, "Pipeline");
  const original = `;; Keep this comment\n${pipeline}\n;; Keep this too`;
  await replace(page, field, original);
  const response = await request.post("/api/format", {
    headers, data: { edn: original },
  });
  expect(response.ok()).toBeTruthy();
  const formatted = await response.json();
  await page.getByRole("button", { name: "Format Pipeline EDN", exact: true }).click();
  await expect.poll(async () => (await snapshot(request)).document.edn).toBe(formatted.edn);
  await field.press("ControlOrMeta+z");
  await expect.poll(async () => (await snapshot(request)).document.edn).toBe(original);

  await replace(page, field, "{:broken [1}");
  await field.press("ControlOrMeta+Shift+f");
  await expect(page.locator(".edn-format-error")).toContainText("Unmatched delimiter");
  await expect(field).toHaveText("{:broken [1}");

  await page.getByRole("button", { name: "Configuration", exact: true }).click();
  const config = editor(page, "Configuration");
  const rawConfig = ';; Keep this comment\n{:enabled true, :label "commas, stay"}';
  await replace(page, config, rawConfig);
  await expect.poll(async () => (await snapshot(request)).configEdn).toBe(rawConfig);
  const responseConfig = await request.post("/api/format", {
    headers, data: { edn: rawConfig },
  });
  expect(responseConfig.ok()).toBeTruthy();
  const formattedConfig = (await responseConfig.json()).edn;
  expect(formattedConfig).toContain("Keep this comment");
  expect(formattedConfig).not.toContain("true,");
  const formatConfig = page.getByRole("button", { name: "Format Configuration EDN", exact: true });
  await formatConfig.click();
  await expect.poll(async () => (await snapshot(request)).configEdn).toBe(formattedConfig);
  await config.press("ControlOrMeta+z");
  await expect.poll(async () => (await snapshot(request)).configEdn).toBe(rawConfig);
  await formatConfig.click();
  await expect.poll(async () => (await snapshot(request)).configEdn).toBe(formattedConfig);
  await page.reload();
  await expect(config).toContainText("Keep this comment");
  await expect(config).toContainText('{:enabled true :label "commas, stay"}');
});

test("formatting an action updates the shared source as one undoable edit", async ({ page, request }) => {
  await action(page);
  const original = "{:name :first,:type :clj/inc,:selectors {n [:state :base]},:params [n]}";
  await replace(page, editor(page), original);
  await expect.poll(async () => (await snapshot(request)).document.edn).toContain(original);
  const before = (await snapshot(request)).document.edn;
  const response = await request.post("/api/format", {
    headers, data: { edn: original },
  });
  expect(response.ok()).toBeTruthy();
  const formatted = await response.json();
  expect(formatted.edn).not.toBe(original);
  await editor(page).press("ControlOrMeta+Shift+f");
  await expect.poll(async () => (await snapshot(request)).document.edn).toBe(before.replace(original, formatted.edn));
  await editor(page).press("ControlOrMeta+z");
  await expect.poll(async () => (await snapshot(request)).document.edn).toBe(before);
});

test("formatting cannot overwrite text typed while the formatter is responding", async ({ page, request }) => {
  await source(page);
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  await page.route("**/api/format", async (route) => {
    const response = await route.fetch();
    expect(response.ok()).toBeTruthy();
    await gate;
    await route.fulfill({ response });
  });
  const button = page.getByRole("button", { name: "Format Pipeline EDN", exact: true });
  const sent = page.waitForRequest("**/api/format");
  await button.click();
  await sent;
  const changed = `${pipeline}\n;; Typed while formatting`;
  try {
    await replace(page, editor(page, "Pipeline"), changed);
  } finally {
    release();
  }
  await expect(button).toBeEnabled();
  await expect.poll(async () => (await snapshot(request)).document.edn).toBe(changed);
});

test("a delayed formatter response cannot change another draft with identical source", async ({ page, request }) => {
  const response = await request.post("/api/sessions", {
    headers, data: { edn: pipeline, configEdn: "{}" },
  });
  expect(response.ok()).toBeTruthy();
  const other = await response.json();
  sessions.add(other.id);
  await source(page);
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  await page.route("**/api/format", async (route) => {
    const formatted = await route.fetch();
    expect(formatted.ok()).toBeTruthy();
    await gate;
    await route.fulfill({ response: formatted });
  });
  const sent = page.waitForRequest("**/api/format");
  await page.getByRole("button", { name: "Format Pipeline EDN", exact: true }).click();
  await sent;
  try {
    await page.evaluate((id) => { location.hash = `view=source&draft=${id}`; }, other.id);
    await expect.poll(() => page.evaluate(() => localStorage.getItem("collet.session"))).toBe(other.id);
  } finally {
    release();
  }
  await expect(page.getByRole("button", { name: "Format Pipeline EDN", exact: true })).toBeEnabled();
  const current = await (await request.get(`/api/sessions/${other.id}`)).json();
  expect(current.document.edn).toBe(pipeline);
  expect(current.revision).toBe(0);
});

test("canvas selection keeps the graph; outline, expansion, viewport and Escape preserve context", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Expand source", exact: true })
    .click();
  await page.getByRole("button", { name: "Zoom In", exact: true }).click();
  const viewport = page.locator(".react-flow__viewport");
  await expect.poll(() => viewport.getAttribute("style")).toContain("scale");
  const transform = await viewport.getAttribute("style");
  await task(page);
  await expect(
    page.getByRole("button", { name: "Run task", exact: true }),
  ).toBeVisible();
  await expect(viewport).toBeVisible();
  await page.getByRole("button", { name: /^1 first / }).click();
  await expect(
    page.getByRole("complementary", { name: "Pipeline outline" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Run action", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/view=action.*task=source.*action=first/);
  await page.keyboard.press("Escape");
  await expect(viewport).toBeVisible();
  await expect(viewport).toHaveAttribute("style", transform);
  await expect(
    page.getByRole("button", { name: "Collapse source", exact: true }),
  ).toBeVisible();
});

test("autosave and run prepare the exact shared source revision and follow an action rename", async ({
  page,
  request,
}) => {
  await action(page);
  const requests = [];
  page.on("request", (r) => {
    if (r.method() === "POST" && /\/(source|execute)$/.test(r.url()))
      requests.push({ url: r.url(), data: r.postDataJSON() });
  });
  await replace(
    page,
    editor(page),
    "{:name :renamed :type :clj/inc :params [20]}",
  );
  const applied = page.waitForResponse((r) => r.url().endsWith("/source"));
  await execute(page);
  expect(requests.map((r) => r.url.split("/").at(-1))).toEqual([
    "source",
    "execute",
  ]);
  const state = await snapshot(request);
  expect(state.tasks.source.preview.value).toBe(21);
  expect(requests[1].data.revision).toBe(
    (await (await applied).json()).revision,
  );
  expect(requests[1].data.mode).toBe("action");
  await expect(
    page.getByRole("heading", { name: "renamed", exact: true }),
  ).toBeVisible();
});

test("unfinished action and full source text are one draft across navigation and reload", async ({
  page,
  request,
}) => {
  await action(page);
  const before = await snapshot(request);
  const bad = "{:name :first :params [";
  await replace(page, editor(page), bad);
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText(bad);
  await canvas(page);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await action(page, "second");
  await replace(
    page,
    editor(page),
    "{:name :second :type :clj/identity :params [22]}",
  );
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText(":params [22]");
  await expect(editor(page, "Pipeline")).toContainText(bad);
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText(":params [22]");
  await expect(editor(page, "Pipeline")).toContainText(bad);
  await expect
    .poll(async () => (await snapshot(request)).revision)
    .toBeGreaterThan(before.revision);
  let executions = 0;
  page.on("request", (r) => {
    if (r.url().endsWith("/execute")) executions++;
  });
  await apply(page);
  await expect(page.getByRole("alert").first()).toBeVisible();
  expect(executions).toBe(0);
  await expect
    .poll(async () => (await snapshot(request)).revision)
    .toBeGreaterThan(before.revision);
});

test("source edits synchronize into action lenses and preserve comments, tags and unknown fields", async ({
  page,
  request,
}) => {
  await source(page);
  const text = pipeline
    .replace(
      ":custom {:preserved true}",
      ':custom {:preserved true :pattern #rgx "ok"} ;; keep me',
    )
    .replace(":params [n 2]", ":params [n 3]");
  await replace(page, editor(page, "Pipeline"), text);
  await action(page, "second");
  await expect(editor(page)).toContainText(":params [n 3]");
  await replace(
    page,
    editor(page),
    "{:name :second :type :clj/* :selectors {n [:state :first]} :params [n 4]}",
  );
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText(";; keep me");
  await expect(editor(page, "Pipeline")).toContainText('#rgx "ok"');
  await expect(editor(page, "Pipeline")).toContainText(":params [n 4]");
  await apply(page);
  const state = await snapshot(request);
  expect(state.document.edn).toContain(":preserved true");
  expect(state.document.edn).toContain("#rgx");
});

test("ambiguous source ranges redirect to Source without losing the raw draft", async ({
  page,
  request,
}) => {
  await source(page);
  await replace(page, editor(page, "Pipeline"), "{:tasks [");
  await canvas(page);
  await task(page);
  await page.getByRole("button", { name: /^1 first / }).click();
  await expect(
    page.getByRole("heading", { name: "Edit this action in Source" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Open Source", exact: true }).click();
  await expect(editor(page, "Pipeline")).toHaveText("{:tasks [");
  expect((await snapshot(request)).document.tasks).toHaveLength(2);
});

test("Run action, Run to here and Run task retain explicit scopes and prerequisites", async ({
  page,
  request,
}) => {
  await action(page, "first", "Dependent");
  await execute(page);
  await expect(page.getByRole("alert").first()).toContainText("upstream");
  expect((await snapshot(request)).tasks.source).toBeUndefined();
  await action(page, "second");
  await execute(page);
  await expect(page.getByRole("alert").first()).toContainText(
    "earlier actions",
  );
  await execute(page, "prefix");
  expect((await snapshot(request)).tasks.source.preview.value).toBe(10);
  await task(page, "Dependent");
  await execute(page, "task");
  expect((await snapshot(request)).tasks.dependent.preview.value).toBe(11);
});

test("Add next action applies pending text once, keeps the completed prefix and exposes Inputs", async ({
  page,
  request,
}) => {
  await action(page);
  await execute(page);
  const first = (await snapshot(request)).tasks.source.actions.first;
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  await page.getByRole("button", { name: "Blank action", exact: true }).click();
  await expect(editor(page)).toBeFocused();
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await expect(
    page.getByRole("tabpanel", { name: "Inputs", exact: true }),
  ).toContainText(":first");
  await replace(
    page,
    editor(page),
    "{:name :middle :type :clj/inc :selectors {n [:state :first]} :params [n]}",
  );
  await execute(page);
  let state = await snapshot(request);
  expect(state.tasks.source.preview.value).toBe(6);
  expect(state.tasks.source.actions.first).toEqual(first);
  await replace(
    page,
    editor(page),
    "{:name :middle :type :clj/inc :selectors {n [:state :first]} :params [n]} ;; note",
  );
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  await page.getByRole("button", { name: "Blank action", exact: true }).click();
  await expect
    .poll(
      async () => (await snapshot(request)).document.tasks[0].actions.length,
    )
    .toBe(4);
  state = await snapshot(request);
  expect(state.document.tasks[0].actions.map((a) => a.name)).toEqual([
    "first",
    "middle",
    "step-1",
    "second",
  ]);
  expect(state.tasks.source.actions.first).toEqual(first);
});

test("editing a later action preserves earlier results and makes dependent outputs stale", async ({
  page,
  request,
}) => {
  await action(page, "second");
  await execute(page, "prefix");
  await task(page, "Dependent");
  await execute(page, "task");
  const first = (await snapshot(request)).tasks.source.actions.first;
  await action(page, "second");
  await replace(
    page,
    editor(page),
    "{:name :second :type :clj/* :selectors {n [:state :first]} :params [n 3]}",
  );
  await apply(page);
  let state = await snapshot(request);
  expect(state.tasks.source.actions.first).toEqual(first);
  expect(state.tasks.dependent.status).toBe("stale");
  await execute(page);
  expect((await snapshot(request)).tasks.source.preview.value).toBe(15);
});

test("available Inputs exclude stale upstream values and identify missing earlier steps", async ({
  page,
  request,
}) => {
  await action(page, "second");
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await expect(
    page.getByRole("tabpanel", { name: "Inputs", exact: true }),
  ).toContainText("earlier");
  await execute(page, "prefix");
  await action(page, "first", "Dependent");
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await expect(
    page.getByRole("tabpanel", { name: "Inputs", exact: true }),
  ).toContainText(":source");
  await request.post(`/api/sessions/${id}/definition`, {
    headers,
    data: {
      edn: pipeline.replace(":params [n 2]", ":params [n 3]"),
      revision: (await snapshot(request)).revision,
      configurationRevision: (await snapshot(request)).configurationRevision,
    },
  });
  await expect
    .poll(async () => (await snapshot(request)).tasks.source.status)
    .toBe("partial");
  await action(page, "first", "Dependent");
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await expect(
    page.getByRole("tabpanel", { name: "Inputs", exact: true }),
  ).toContainText("missing or stale");
});

test("invalid structural edits preserve autosaved source and the previous graph", async ({
  page,
  request,
}) => {
  await action(page);
  await replace(page, editor(page), "{:name");
  await expect
    .poll(async () => (await snapshot(request)).revision)
    .toBeGreaterThan(0);
  const before = await snapshot(request);
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  await page.getByRole("button", { name: "Blank action", exact: true }).click();
  await expect(page.getByRole("alert").first()).toBeVisible();
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("{:name");
  expect((await snapshot(request)).document.edn).toBe(before.document.edn);
  expect((await snapshot(request)).revision).toBe(before.revision);
});

test("new pipeline creates a task and two incremental actions from scratch", async ({
  page,
  request,
}) => {
  await choose(page, "New pipeline");
  await page
    .getByRole("button", { name: "Add first task", exact: true })
    .click();
  await page.getByRole("button", { name: /^1 value / }).click();
  await replace(
    page,
    editor(page),
    "{:name :orders :type :clj/identity :params [[{:id 1 :amount 5} {:id 2 :amount 8}]]}",
  );
  await execute(page);
  await expect(page.getByRole("table")).toContainText("amount");
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  await page.getByRole("button", { name: "Blank action", exact: true }).click();
  await replace(
    page,
    editor(page),
    "{:name :count :type :clj/count :selectors {rows [:state :orders]} :params [rows]}",
  );
  await execute(page);
  const sid = await page.evaluate(() => localStorage.getItem("collet.session"));
  const state = await (await request.get(`/api/sessions/${sid}`)).json();
  expect(state.tasks["task-1"].preview.value).toBe(2);
  expect(state.tasks["task-1"].actions.orders.preview.count).toBe(2);
});

test("task dependencies, reorder and removal preserve semantic fields", async ({
  page,
  request,
}) => {
  await task(page);
  await page
    .getByRole("button", { name: "Options for second", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "Move up", exact: true }).click();
  await expect
    .poll(
      async () => (await snapshot(request)).document.tasks[0].actions[0].name,
    )
    .toBe("second");
  await task(page, "Dependent");
  await page.getByRole("tab", { name: "Settings", exact: true }).click();
  await page.getByRole("button", { name: "Disconnect", exact: true }).click();
  await expect
    .poll(async () => (await snapshot(request)).document.tasks[1].inputs)
    .toEqual([]);
  await page
    .getByRole("combobox", { name: "Add dependency", exact: true })
    .click();
  await page.getByRole("option", { name: "Source", exact: true }).click();
  await expect
    .poll(async () => (await snapshot(request)).document.tasks[1].inputs)
    .toEqual(["source"]);
  expect((await snapshot(request)).document.edn).toContain(":preserved true");
  await page.getByRole("button", { name: "Task options", exact: true }).click();
  await page
    .getByRole("menuitem", { name: "Delete task", exact: true })
    .click();
  await expect
    .poll(async () => (await snapshot(request)).document.tasks)
    .toHaveLength(1);
});

test("automatic saved-version drafting publishes only on Save and keeps earlier versions immutable", async ({
  page,
  request,
}) => {
  const published = await save(page);
  const version = published.document.version;
  await page
    .getByRole("button", {
      name: "Open saved revision " + version,
      exact: true,
    })
    .click();
  await action(page);
  await expect(editor(page)).toBeEditable();
  await replace(
    page,
    editor(page),
    "{:name :first :type :clj/inc :params [10]}",
  );
  await canvas(page);
  await expect(
    page.getByText(/^Draft(?: · saving…| autosaved)$/),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: /Edit as draft/ })).toHaveCount(
    0,
  );
  const next = await save(page);
  expect(next.document.version).toBeGreaterThan(version);
  const previous = await (
    await request.get(`/api/definitions/${published.document.name}/${version}`)
  ).json();
  expect(previous.edn).not.toContain(":params [10]");
});

test("opening saved pipelines repeatedly creates a draft only on the first edit", async ({
  page,
  request,
}) => {
  const published = await save(page);
  const drafts = async () =>
    (await allDrafts(request))
      .filter((draft) => draft.name === published.document.name)
      .map((draft) => draft.id)
      .sort();
  const original = await drafts();
  const openSaved = async () => {
    await canvas(page);
    await page
      .getByRole("button", {
        name: `Open saved revision ${published.savedVersion}`,
        exact: true,
      })
      .click();
    await expect(page).toHaveURL(/[&#]pipeline=/);
  };
  for (let i = 0; i < 3; i++) {
    await openSaved();
    await action(page);
    await execute(page);
    expect(await drafts()).toEqual(original);
    await choose(page, "Open current draft");
  }
  await openSaved();
  await page.reload();
  await expect(page).toHaveURL(/[&#]pipeline=/);
  await action(page, "second");
  expect(await drafts()).toEqual(original);
  const saved = page.waitForResponse((response) =>
    response.url().endsWith("/save"),
  );
  await page.keyboard.press("ControlOrMeta+s");
  expect((await saved).ok()).toBeTruthy();
  expect(
    await page.evaluate(() => localStorage.getItem("collet.session")),
  ).toBe(id);
  expect(await drafts()).toEqual(original);
  await replace(
    page,
    editor(page),
    ";; keep my source\n{:name :second :type :clj/identity :params [9]}",
  );
  await expect
    .poll(async () => (await drafts()).length)
    .toBe(original.length + 1);
  const editedId = await page.evaluate(() =>
    localStorage.getItem("collet.session"),
  );
  const edited = await (await request.get(`/api/sessions/${editedId}`)).json();
  expect(edited.document.edn).toContain(";; keep my source");
  expect(edited.origin).toEqual({
    name: published.document.name,
    version: published.savedVersion,
  });
  await replace(
    page,
    editor(page),
    "{:name :second :type :clj/identity :params [10]}",
  );
  await expect
    .poll(async () => (await drafts()).length)
    .toBe(original.length + 1);
});

test("a saved pipeline link in a fresh browser creates no background draft", async ({
  page,
  request,
  browser,
}) => {
  const published = await save(page);
  const drafts = async () =>
    (await allDrafts(request)).map((draft) => draft.id).sort();
  const original = await drafts();
  const fresh = await browser.newContext({
    baseURL: new URL(page.url()).origin,
  });
  try {
    const tab = await fresh.newPage();
    const url = `/#pipeline=${published.document.name}&version=${published.savedVersion}`;
    await tab.goto(url);
    await action(tab);
    await execute(tab);
    expect(await drafts()).toEqual(original);
    await tab.evaluate(() =>
      localStorage.setItem("collet.session", crypto.randomUUID()),
    );
    await tab.reload();
    await expect(
      tab.getByRole("textbox", { name: "Action EDN editor", exact: true }),
    ).toBeEditable();
    expect(await drafts()).toEqual(original);
    const run = await runPipeline(tab, published.savedVersion);
    await waitRun(request, run);
    await tab.getByRole("button", { name: "Build", exact: true }).click();
    await expect(tab).toHaveURL(/[&#]pipeline=/);
    await action(tab);
    expect(await drafts()).toEqual(original);
    await replace(
      tab,
      editor(tab),
      "{:name :first :type :clj/inc :params [20]}",
    );
    await expect
      .poll(async () => (await drafts()).length)
      .toBe(original.length + 1);
  } finally {
    await fresh.close();
  }
});

test("saved task and action previews run directly without publishing or replacing unfinished work", async ({
  page,
  request,
}) => {
  const published = await save(page);
  await action(page);
  await replace(page, editor(page), "{:name");
  await canvas(page);
  await page
    .getByRole("button", {
      name: "Open saved revision " + published.document.version,
      exact: true,
    })
    .click();
  await action(page);
  await execute(page);
  await choose(page, "Open current draft");
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("{:name");
  expect((await snapshot(request)).document.edn).toBe(published.document.edn);
});

test("saved-version runs ignore invalid draft text and scratch results; returning restores the draft", async ({
  page,
  request,
}) => {
  const published = await save(page);
  await action(page);
  await execute(page);
  await replace(page, editor(page), "{:name");
  const before = await snapshot(request);
  let writes = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && /\/(definition|save)$/.test(r.url())) writes++;
  });
  const run = await runPipeline(page, published.document.version);
  await waitRun(request, run);
  expect(writes).toBe(0);
  const after = await snapshot(request);
  expect(after.tasks.source.actions).toEqual(before.tasks.source.actions);
  expect(after.tasks.source.preview).toEqual(before.tasks.source.preview);
  expect(after.tasks.source.through).toBe(before.tasks.source.through);
  expect(after.currentResults).toBe(false);
  await page.getByRole("button", { name: "Build", exact: true }).click();
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("{:name");
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText("{:name");
});

test("Run pipeline chooses versions, cancellation has no effects, and historical Source is read-only", async ({
  page,
  request,
}) => {
  const first = await save(page);
  await source(page);
  await replace(
    page,
    editor(page, "Pipeline"),
    pipeline.replace(":params [n 2]", ":params [n 3]"),
  );
  const second = await save(page);
  let runs = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && r.url().endsWith("/run")) runs++;
  });
  await page.getByRole("button", { name: "Run pipeline", exact: true }).click();
  await page
    .getByRole("dialog", { name: "Run pipeline", exact: true })
    .getByRole("button", { name: "Cancel" })
    .click();
  expect(runs).toBe(0);
  const run = await runPipeline(page, first.document.version);
  await waitRun(request, run);
  expect(run.document.version).toBe(first.document.version);
  expect(second.document.version).toBeGreaterThan(first.document.version);
  await source(page);
  await expect(editor(page, "Pipeline")).not.toBeEditable();
  await expect(editor(page, "Pipeline")).not.toContainText(":params [n 3]");
  await page
    .getByRole("button", { name: "Open in Build", exact: true })
    .click();
  await expect(editor(page, "Pipeline")).toBeEditable();
});

test("full pipeline runs start independently of scratch results and report failures beside tasks", async ({
  page,
  request,
}) => {
  await action(page);
  await execute(page);
  await source(page);
  await replace(
    page,
    editor(page, "Pipeline"),
    pipeline.replace(
      ":type :clj/inc :selectors {n [:inputs :source]} :params [n]",
      ":type :clj// :params [1 0]",
    ),
  );
  const run = await runPipeline(page);
  await waitRun(request, run);
  await task(page, "Dependent");
  await expect(page.getByRole("alert")).toHaveCount(1);
  await page.getByRole("tab", { name: "Output", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveCount(1);
  await expect(page.getByRole("alert")).toContainText("Divide by zero");
  await expect(
    page.getByRole("button", { name: "Run task", exact: true }),
  ).toHaveCount(0);
});

test("concurrent source changes preserve local text for explicit reconciliation", async ({
  page,
  request,
}) => {
  await action(page);
  await replace(
    page,
    editor(page),
    "{:name :first :type :clj/inc :params [5]}",
  );
  const changed = await request.post(`/api/sessions/${id}/source`, {
    headers,
    data: {
      edn: pipeline.replace(":params [n 2]", ":params [n 3]"),
      revision: 0,
      author: "agent",
    },
  });
  expect(changed.ok()).toBeTruthy();
  await expect(
    page.getByRole("dialog", { name: "Draft changed in another client" }),
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "Your conflicting source" }),
  ).toHaveValue(/:params \[5\]/);
  await expect(
    page.getByRole("textbox", { name: "Shared conflicting source" }),
  ).toHaveValue(/:params \[n 3\]/);
  expect(
    Object.values((await snapshot(request)).tasks).every(
      (task) => !task.status && !task.preview,
    ),
  ).toBe(true);
  await page
    .getByRole("button", {
      name: "Replace current with my source",
      exact: true,
    })
    .click();
  await expect.poll(async () => (await snapshot(request)).revision).toBe(2);
  expect((await snapshot(request)).document.edn).toContain(":params [5]");
});

test("typing during a structural edit survives reconciliation and reload", async ({
  page,
  request,
}) => {
  let release, started;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const received = new Promise((resolve) => {
    started = resolve;
  });
  await page.route(`**/api/sessions/${id}/source`, async (route) => {
    if (route.request().postDataJSON()?.op === "add-task") {
      started();
      await gate;
    }
    await route.continue();
  });
  const pending = pipeline + "\n; typed while the graph edit was pending";
  try {
    await page.getByRole("button", { name: "Add task", exact: true }).click();
    await received;
    await source(page);
    await replace(page, editor(page, "Pipeline"), pending);
  } finally {
    release();
  }
  const conflict = page.getByRole("dialog", {
    name: "Draft changed in another client",
  });
  await expect(conflict).toBeVisible();
  await expect(
    conflict.getByRole("textbox", { name: "Your conflicting source" }),
  ).toHaveValue(pending);
  expect((await snapshot(request)).document.edn).not.toContain("typed while");
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("collet.pending.restore")).edn,
    ),
  ).toBe(pending);
  await page.reload();
  await expect(conflict).toBeVisible();
  await expect(
    conflict.getByRole("textbox", { name: "Your conflicting source" }),
  ).toHaveValue(pending);
  await conflict
    .getByRole("button", {
      name: "Replace current with my source",
      exact: true,
    })
    .click();
  await expect
    .poll(async () => (await snapshot(request)).document.edn)
    .toBe(pending);
});

test("pipeline switcher preserves separate invalid drafts and restores them after reload", async ({
  page,
  request,
}) => {
  await action(page);
  await replace(page, editor(page), "{:name :first");
  await choose(page, "New pipeline");
  await source(page);
  await replace(page, editor(page, "Pipeline"), "{:name :other");
  await choose(page, "Open draft Ui");
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("{:name :first");
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText("{:name :first");
});

test("legacy pending action storage migrates safely after the API session ends", async ({
  page,
  request,
}) => {
  await page.evaluate(
    (s) =>
      localStorage.setItem(
        "collet.pending.restore",
        JSON.stringify({
          kind: "action",
          id: s,
          task: "source",
          index: 1,
          revision: 0,
          edn: "{:name :legacy :type :clj/identity :params [77]}",
        }),
      ),
    id,
  );
  await request.post(`/api/sessions/${id}/close`, { headers, data: {} });
  await page.reload();
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText(":name :legacy");
  await expect(editor(page, "Pipeline")).toContainText(":custom");
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText(":name :legacy");
});

test("SSE reconnect refreshes snapshots; offline editing and reload keep pending text", async ({
  page,
  request,
  context,
}) => {
  await action(page);
  await execute(page);
  await context.setOffline(true);
  await expect(
    page.getByRole("status", { name: "Reconnecting", exact: true }),
  ).toBeVisible();
  await replace(page, editor(page), "{:name :offline");
  await expect(
    page.getByRole("button", { name: "Run action", exact: true }),
  ).toBeDisabled();
  await context.setOffline(false);
  await expect(
    page.getByRole("status", { name: "Connected", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(editor(page)).toHaveText("{:name :offline");
  expect((await snapshot(request)).tasks.source.preview.value).toBe(5);
});

test("table, tree, EDN, selector copying and Portal inspect real data before the next action runs", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await action(page);
  await replace(
    page,
    editor(page),
    "{:name :rows :type :clj/identity :params [[{:id 1 :amount 5} {:id 2 :amount 8}]]}",
  );
  await execute(page);
  await page
    .getByRole("button", { name: "Copy selector for amount", exact: true })
    .click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("[:state :rows 0 :amount]");
  await page.getByRole("combobox", { name: "Result format" }).click();
  await page.getByRole("option", { name: "EDN", exact: true }).click();
  await expect(page.locator(".edn-result")).toContainText(":amount");
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  await page.getByRole("button", { name: "Blank action", exact: true }).click();
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await expect(
    page.getByRole("tabpanel", { name: "Inputs", exact: true }),
  ).toContainText(":rows");
  await page.getByRole("button", { name: "Inputs tools", exact: true }).click();
  const popup = page.waitForEvent("popup");
  await page
    .getByRole("menuitem", { name: "Inspect in Portal", exact: true })
    .click();
  const portal = await popup;
  await expect(portal).toHaveURL(/^http:\/\/localhost:\d+/);
  await portal.close();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Close", exact: true })
    .click();
});

test("whole-task iterator previews keep Run task available inside action focus", async ({
  page,
  request,
}) => {
  const spec =
    "{:name :parallel-ui :tasks [{:name :loop :state-format :latest :setup [{:name :seed :type :clj/identity :params [0]}] :actions [{:name :item :type :clj/inc :selectors {n [:state :seed]} :params [n]}] :iterator {:next [:< [:state :item] 1]}}]}";
  const changed = await request.post(`/api/sessions/${id}/definition`, {
    headers,
    data: {
      edn: spec,
      revision: (await snapshot(request)).revision,
      configurationRevision: (await snapshot(request)).configurationRevision,
    },
  });
  expect(changed.ok(), await changed.text()).toBeTruthy();
  await page.reload();
  await action(page, "item", "Loop");
  await expect(
    page.getByRole("button", { name: "Run task", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Run action", exact: true }),
  ).toHaveCount(0);
  await execute(page, "task");
  expect((await snapshot(request)).tasks.loop.status).toBe("completed");
});

test("command palette, keyboard run/save and browser Back preserve unapplied edits", async ({
  page,
  request,
}) => {
  await page.keyboard.press("ControlOrMeta+k");
  await page
    .getByRole("dialog")
    .getByRole("combobox")
    .fill("go to action source first");
  await page.keyboard.press("Enter");
  await expect(editor(page)).toBeVisible();
  await replace(
    page,
    editor(page),
    "{:name :first :type :clj/inc :params [8]}",
  );
  await source(page);
  await page.goBack();
  await expect(editor(page)).toContainText(":params [8]");
  const finished = page.waitForResponse((r) => r.url().endsWith("/execute"));
  await editor(page).press("ControlOrMeta+Enter");
  await waitJob(page, await (await finished).json());
  await expect(
    page.getByRole("button", { name: "Save version", exact: true }),
  ).toBeEnabled();
  expect((await snapshot(request)).tasks.source.preview.value).toBe(9);
  const saved = page.waitForResponse((r) => r.url().endsWith("/save"));
  await editor(page).press("ControlOrMeta+s");
  await saved;
  expect((await snapshot(request)).document.version).toBeGreaterThan(0);
});

for (const width of [1440, 1100, 760, 480])
  test(`compact layout, readable controls and scrolling at ${width}px`, async ({
    page,
    request,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await source(page);
    const actions = Array.from(
      { length: 60 },
      (_, i) => `{:name :step-${i} :type :clj/identity :params [${i}]}`,
    ).join("\n");
    const long = `{:name :long-source :tasks [{:name :source :actions [${actions}]}]}`;
    await replace(page, editor(page, "Pipeline"), long);
    const scroll = page.locator(".source-code .cm-scroller");
    await expect
      .poll(() => scroll.evaluate((el) => el.scrollHeight > el.clientHeight))
      .toBe(true);
    await scroll.hover();
    await page.mouse.wheel(0, 1000);
    await expect
      .poll(() => scroll.evaluate((el) => el.scrollTop))
      .toBeGreaterThan(0);
    expect(await page.evaluate(() => scrollY)).toBe(0);
    await apply(page);
    await action(page, "step-0");
    if (width < 1200) {
      await expect(
        page.getByRole("tab", { name: "Action EDN", exact: true }),
      ).toBeVisible();
      await page.getByRole("tab", { name: "Inputs", exact: true }).click();
      await expect(
        page.getByRole("tabpanel", { name: "Inputs", exact: true }),
      ).toBeVisible();
    } else
      await expect(
        page.getByRole("complementary", { name: "Pipeline outline" }),
      ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    expect(
      await page
        .locator(".topbar")
        .evaluate((el) => el.getBoundingClientRect().height),
    ).toBeLessThanOrEqual(width < 600 ? 120 : width < 900 ? 90 : 44);
  });

test("Source cursor selects a task without stealing editor focus; inspectors close and reopen", async ({
  page,
}) => {
  await source(page);
  await editor(page, "Pipeline")
    .locator(".cm-line")
    .filter({ hasText: ":name :dependent" })
    .click();
  await expect(
    page
      .getByRole("complementary", { name: "Task details" })
      .getByRole("heading", { name: "Dependent", exact: true }),
  ).toBeVisible();
  await expect(editor(page, "Pipeline")).toBeFocused();
  await page
    .getByRole("button", { name: "Close inspector", exact: true })
    .click();
  await expect(
    page.getByRole("complementary", { name: "Task details" }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Open inspector", exact: true })
    .click();
  await expect(
    page.getByRole("complementary", { name: "Pipeline inspector" }),
  ).toBeVisible();
});

test("reordered and renamed actions execute the selected source form rather than its old index", async ({
  page,
  request,
}) => {
  const a = "{:name :first :type :clj/identity :params [11]}",
    b = "{:name :second :type :clj/identity :params [22]}";
  const original = `{:name :reorder-source :tasks [{:name :source :actions [${a} ${b}]}]}`;
  const changed = await request.post(`/api/sessions/${id}/definition`, {
    headers,
    data: {
      edn: original,
      revision: (await snapshot(request)).revision,
      configurationRevision: (await snapshot(request)).configurationRevision,
    },
  });
  expect(changed.ok()).toBeTruthy();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Switch pipeline", exact: true }),
  ).toHaveText("Reorder source");
  await expect(
    page.getByRole("status", { name: "Connected", exact: true }),
  ).toBeVisible();
  await source(page);
  await replace(
    page,
    editor(page, "Pipeline"),
    original.replace(`${a} ${b}`, `${b} ${a}`),
  );
  await action(page, "second");
  await execute(page);
  expect((await snapshot(request)).tasks.source.preview.value).toBe(22);
  expect((await snapshot(request)).document.tasks[0].actions[0].name).toBe(
    "second",
  );
});

test("previous samples remain visibly stale after Apply while Inputs expose only valid context", async ({
  page,
}) => {
  await action(page, "second");
  await execute(page, "prefix");
  await replace(
    page,
    editor(page),
    "{:name :second :type :clj/* :selectors {n [:state :first]} :params [n 3]}",
  );
  await apply(page);
  await expect(
    page.getByText("Previous sample · produced by an earlier revision", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  const inputs = page.getByRole("tabpanel", { name: "Inputs", exact: true });
  await expect(inputs).toContainText(":first");
  await expect(inputs).not.toContainText(":second");
});

test("configuration edits invalidate scratch context and apply without publishing", async ({
  page,
  request,
}) => {
  await action(page);
  await execute(page);
  await source(page);
  await page
    .getByRole("button", { name: "Configuration", exact: true })
    .click();
  await replace(page, editor(page, "Configuration"), "{:factor 3}");
  await apply(page);
  const state = await snapshot(request);
  expect(state.configEdn).toContain(":factor 3");
  expect(state.tasks.source.status).toBe("stale");
  expect(state.document.version).toBeFalsy();
});

test("bounded samples expose truncation and available data formats", async ({
  page,
}) => {
  await action(page);
  const rows = Array.from(
    { length: 120 },
    (_, i) => `{:id ${i} :amount ${i * 2}}`,
  ).join(" ");
  await replace(
    page,
    editor(page),
    `{:name :rows :type :clj/identity :params [[${rows}]]}`,
  );
  await execute(page);
  await expect(
    page.getByText("Showing a bounded sample", { exact: true }),
  ).toBeVisible();
  expect(await page.getByRole("row").count()).toBe(101);
  await page
    .getByRole("combobox", { name: "Result format", exact: true })
    .click();
  await page.getByRole("option", { name: "Tree", exact: true }).click();
  await expect(page.locator(".tree").first()).toBeVisible();
});

test("storage failures keep text visible and enable unload protection", async ({
  page,
}) => {
  await action(page);
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key === "collet.pending.restore")
        throw new DOMException("Full", "QuotaExceededError");
      return original.call(this, key, value);
    };
  });
  await editor(page).click();
  await editor(page).press("ControlOrMeta+A");
  await page.keyboard.insertText("{:name :kept");
  await expect(editor(page)).toHaveText("{:name :kept");
  await expect(page.getByRole("alert")).toContainText(
    "Browser storage is full",
  );
  expect(
    await page.evaluate(() => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    }),
  ).toBe(true);
});

test("orders visual checkpoints match reference dimensions and retain usable contrast", async ({
  page,
  context,
}, testInfo) => {
  await page.setViewportSize({ width: 1282, height: 802 });
  await choose(page, "Orders example");
  for (const name of [
    "Load orders",
    "Normalize",
    "Enrich customers",
    "Daily totals",
    "Report",
  ]) {
    await task(page, name);
    await execute(page, "task");
  }
  await task(page, "Normalize");
  const settingsBox = await page
    .getByRole("tab", { name: "Settings", exact: true })
    .boundingBox();
  expect(settingsBox.x + settingsBox.width).toBeLessThanOrEqual(1282);
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  await page.screenshot({ path: testInfo.outputPath("workspace-task.png") });
  const sourcePage = await context.newPage();
  await sourcePage.setViewportSize({ width: 1440, height: 1000 });
  await sourcePage.goto(
    new URL("../../design/workspace-reference.html", import.meta.url).href,
  );
  await sourcePage
    .locator("#S2 .dv-card")
    .screenshot({ path: testInfo.outputPath("workspace-reference-task.png") });
  await sourcePage.locator("#S3 .dv-card").screenshot({
    path: testInfo.outputPath("workspace-reference-action.png"),
  });
  await sourcePage.close();
  await page.getByRole("button", { name: /^1 parse-amount / }).click();
  await execute(page);
  await page.getByRole("tab", { name: "Result", exact: true }).click();
  const contrast = await page
    .getByRole("button", { name: "Run action", exact: true })
    .evaluate(buttonContrast);
  expect(contrast).toBeGreaterThanOrEqual(4.5);
  await replace(
    page,
    editor(page),
    ";; Explore the next transformation\n" + (await editor(page).innerText()),
  );
  await page.screenshot({ path: testInfo.outputPath("workspace-action.png") });
  await source(page);
  await page.screenshot({ path: testInfo.outputPath("workspace-source.png") });
  await canvas(page);
  const run = await runPipeline(page);
  await waitRun(page.request, run);
  await task(page, "Report");
  await page.getByRole("tab", { name: "Output", exact: true }).click();
  await page.screenshot({ path: testInfo.outputPath("workspace-run.png") });
  await page.getByRole("button", { name: "Build", exact: true }).click();
  await action(page, "parse-amount", "Normalize");
  await page.setViewportSize({ width: 760, height: 900 });
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  await page.screenshot({ path: testInfo.outputPath("workspace-narrow.png") });
});

test("switching away from a saved working copy preserves its unexecuted pending edits", async ({
  page,
}) => {
  await save(page);
  await action(page);
  await replace(page, editor(page), "{:name :unfinished");
  await choose(page, "New pipeline");
  await choose(page, "Open draft Ui");
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("{:name :unfinished");
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText("{:name :unfinished");
});

test("agent edits refresh the shared draft without moving focus and history restores source", async ({
  page,
  request,
}) => {
  await source(page);
  await editor(page, "Pipeline").focus();
  const edited = pipeline.replace(":params [n 2]", ":params [n 7]");
  const response = await request.post(`/api/sessions/${id}/source`, {
    headers,
    data: {
      revision: 0,
      edn: edited,
      author: "agent",
      summary: "Change multiplier",
    },
  });
  expect(response.ok()).toBeTruthy();
  await expect(editor(page, "Pipeline")).toContainText(":params [n 7]");
  await expect(editor(page, "Pipeline")).toBeFocused();
  await expect(page).toHaveURL(/view=source/);
  await page.getByRole("button", { name: "History", exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: "Draft history and changes",
  });
  await expect(dialog).toContainText("agent");
  await dialog.getByRole("button", { name: /^r0 / }).click();
  await expect(
    dialog.getByRole("textbox", { name: "Historical source" }),
  ).toHaveValue(pipeline);
  await expect(
    dialog.getByRole("textbox", { name: "Current source" }),
  ).toHaveValue(edited);
  await dialog
    .getByRole("button", { name: "Restore selected source", exact: true })
    .click();
  await expect.poll(async () => (await snapshot(request)).revision).toBe(2);
  expect((await snapshot(request)).document.edn).toBe(pipeline);
  const history = await (
    await request.get(`/api/sessions/${id}/history`)
  ).json();
  expect(history.revisions).toHaveLength(3);
});

test("configuration stays in application memory and never enters browser recovery storage", async ({
  page,
  request,
}) => {
  await source(page);
  await page
    .getByRole("button", { name: "Configuration", exact: true })
    .click();
  await replace(
    page,
    editor(page, "Configuration"),
    '{:api-key "private-browser-test-token"}',
  );
  await apply(page);
  expect((await snapshot(request)).configEdn).toContain(
    "private-browser-test-token",
  );
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "private-browser-test-token",
  );
  await replace(
    page,
    editor(page, "Configuration"),
    '{:api-key "replacement-test-token"}',
  );
  await apply(page);
  expect((await snapshot(request)).configurationRevision).toBe(2);
  await page.reload();
  await expect(editor(page, "Configuration")).toContainText(
    "replacement-test-token",
  );
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "replacement-test-token",
  );
});

test("action catalogue inserts a documented integration with its exact DSL type", async ({
  page,
  request,
}) => {
  await action(page);
  await page
    .getByRole("button", { name: "Add next action", exact: true })
    .click();
  const dialog = page.getByRole("dialog", { name: "Add action", exact: true });
  await dialog
    .getByRole("textbox", { name: "Search actions" })
    .fill("http/request");
  await expect(dialog).toContainText("network");
  await dialog
    .getByRole("button", {
      name: "Use collet.actions.http/request",
      exact: true,
    })
    .click();
  await expect(editor(page)).toContainText(":collet.actions.http/request");
  await expect(editor(page)).toContainText(":return [:body]");
  expect((await snapshot(request)).document.tasks[0].actions[1].type).toBe(
    "collet.actions.http/request",
  );
});

test("a shared draft deep link opens in another browser context", async ({
  page,
  browser,
  request,
}) => {
  const context = await browser.newContext();
  const other = await context.newPage();
  try {
    await other.goto(`/#draft=${id}&view=source`);
    await expect(editor(other, "Pipeline")).toContainText(pipeline);
    await expect(other).toHaveURL(new RegExp(`draft=${id}`));
    await expect(
      other.getByRole("button", { name: "Save version", exact: true }),
    ).toBeVisible();
    const draft = await snapshot(request);
    const response = await request.post(`/api/sessions/${id}/execute`, {
      headers,
      data: {
        instanceId: draft.instanceId,
        idempotencyKey: randomUUID(),
        revision: draft.revision,
        configurationRevision: draft.configurationRevision,
        scope: "action",
        "task-name": "source",
        index: 0,
      },
    });
    expect(response.ok()).toBeTruthy();
    const job = await waitJob(page, await response.json());
    await other.goto(`/${new URL(job.url).hash}`);
    await expect(editor(other)).toContainText(":name :first");
    await expect(other).toHaveURL(/view=action.*task=source.*action=first/);
  } finally {
    await context.close();
  }
});

test("logs and metrics are focused reads and logs export on demand", async ({
  page,
}) => {
  await action(page);
  await page.getByRole("button", { name: "Run action", exact: true }).click();
  const inspector = page.getByRole("region", {
    name: "Data inspector",
    exact: true,
  });
  await inspector.getByRole("tab", { name: "Logs", exact: true }).click();
  await expect(inspector).toContainText("action-completed");
  await expect(inspector).toContainText("Detailed events are bounded");
  const download = page.waitForEvent("download");
  await inspector
    .getByRole("button", { name: "Export retained logs", exact: true })
    .click();
  expect((await download).suggestedFilename()).toBe("collet-events.ndjson");
  await inspector.getByRole("tab", { name: "Metrics", exact: true }).click();
  await expect(inspector).toContainText('"completed": 1');
  await expect(inspector).toContainText('"durationMs"');
});

test("Inputs follow local and remote execution, configuration, reset and reconnect without navigation", async ({
  page,
  request,
  context,
}) => {
  await action(page, "second");
  await page.getByRole("tab", { name: "Inputs", exact: true }).click();
  const inputs = page.getByRole("tabpanel", { name: "Inputs", exact: true });
  await expect(inputs).toContainText("Run earlier actions");
  const original = await snapshot(request);
  await execute(page, "prefix");
  await expect(
    page.getByRole("tab", { name: "Inputs", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await expect(inputs).toContainText(":first");
  await expect(inputs).not.toContainText("Run earlier actions");
  await expect(inputs).not.toContainText("Previous context");

  const remoteRun = async () => {
    const s = await snapshot(request);
    const response = await request.post(`/api/sessions/${id}/execute`, {
      headers,
      data: {
        instanceId: s.instanceId,
        idempotencyKey: randomUUID(),
        revision: s.revision,
        configurationRevision: s.configurationRevision,
        scope: "prefix",
        "task-name": "source",
        index: 1,
      },
    });
    expect(response.ok()).toBeTruthy();
    expect((await waitJob(page, await response.json())).status).toBe(
      "completed",
    );
  };
  await request.post(`/api/sessions/${id}/configuration`, {
    headers,
    data: { configurationRevision: 0, configEdn: "{:changed true}" },
  });
  await expect(inputs).toContainText("Previous context");
  await remoteRun();
  await expect(inputs).not.toContainText("Previous context");
  await expect(inputs).toContainText(":first");
  await request.post(`/api/sessions/${id}/reset`, { headers, data: {} });
  await expect(inputs).toContainText("Run earlier actions");
  await expect(inputs).not.toContainText(":first");

  await context.setOffline(true);
  await expect(
    page.getByRole("status", { name: "Reconnecting", exact: true }),
  ).toBeVisible();
  await remoteRun();
  await context.setOffline(false);
  await expect(inputs).toContainText(":first");
  await expect(inputs).not.toContainText("Run earlier actions");
  await expect(inputs).not.toContainText("Previous context");
  expect((await snapshot(request)).revision).toBe(original.revision);
});

test("Inputs discard a delayed snapshot from an earlier scratch revision", async ({
  page,
  request,
}) => {
  let release;
  let captured;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  const ready = new Promise((resolve) => {
    captured = resolve;
  });
  let intercepted = false;
  await page.route(`**/api/sessions/${id}/inputs/source/1`, async (route) => {
    if (intercepted) return route.continue();
    intercepted = true;
    const response = await route.fetch();
    captured();
    await held;
    await route.fulfill({ response });
  });
  try {
    await action(page, "second");
    await ready;
    await execute(page, "prefix");
    await page.getByRole("tab", { name: "Inputs", exact: true }).click();
    const inputs = page.getByRole("tabpanel", { name: "Inputs", exact: true });
    await expect(inputs).toContainText(":first");
    release();
    await expect(inputs).not.toContainText("Run earlier actions");
    await expect(inputs).not.toContainText("Previous context");
    expect((await snapshot(request)).revision).toBe(0);
  } finally {
    release();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("editing a saved version can publish repeatedly without rewriting raw source", async ({
  page,
  request,
}) => {
  const saved = await save(page);
  const original = await (
    await request.get(
      `/api/definitions/${saved.document.name}/${saved.savedVersion}`,
    )
  ).json();
  await page
    .getByRole("button", {
      name: `Open saved revision ${saved.savedVersion}`,
      exact: true,
    })
    .click();
  await action(page);
  for (const n of [10, 20]) {
    await replace(
      page,
      editor(page),
      `{:name :first :type :clj/inc :params [${n}]} ;; retained`,
    );
    id = await page.evaluate(() => localStorage.getItem("collet.session"));
    await expect
      .poll(async () =>
        (await snapshot(request)).document.edn.includes(`:params [${n}]`),
      )
      .toBe(true);
    const text = (await snapshot(request)).document.edn;
    const next = await save(page);
    expect(next.savedVersion).toBe(saved.savedVersion + n / 10);
    expect(next.document.edn).toBe(text);
    expect((await save(page)).savedVersion).toBe(next.savedVersion);
  }
  expect(
    await (
      await request.get(
        `/api/definitions/${saved.document.name}/${saved.savedVersion}`,
      )
    ).json(),
  ).toEqual(original);
});

test("Manage deletes a draft only after confirmation and does not recreate it on reload", async ({
  page,
  request,
}) => {
  const before = await allDrafts(request);
  const original = await snapshot(request);
  await page.getByRole("button", { name: "Manage", exact: true }).click();
  let dialog = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await dialog
    .getByRole("textbox", { name: "Filter cleanup list" })
    .fill(original.document.name);
  const removeName = `Delete draft ${original.document.name} ${id.slice(0, 8)}`;
  await dialog.getByRole("button", { name: removeName, exact: true }).click();
  await page
    .getByRole("dialog", { name: "Delete draft", exact: true })
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
  expect((await request.get(`/api/sessions/${id}`)).ok()).toBeTruthy();
  dialog = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await dialog.getByRole("button", { name: removeName, exact: true }).click();
  await page
    .getByRole("dialog", { name: "Delete draft", exact: true })
    .getByRole("button", { name: "Delete draft", exact: true })
    .click();
  await expect
    .poll(async () => (await request.get(`/api/sessions/${id}`)).status())
    .toBe(404);
  dialog = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await expect(
    dialog.getByRole("button", { name: removeName, exact: true }),
  ).toHaveCount(0);
  await dialog.getByRole("button", { name: "Close", exact: true }).click();
  await page.reload();
  await expect(
    page.getByRole("region", { name: "Pipeline graph", exact: true }),
  ).toBeVisible();
  const after = await allDrafts(request);
  expect(after.map((d) => d.id).sort()).toEqual(
    before
      .filter((d) => d.id !== id)
      .map((d) => d.id)
      .sort(),
  );
  expect(
    await page.evaluate(() =>
      JSON.parse(localStorage.getItem("collet.drafts") || "{}"),
    ),
  ).not.toHaveProperty(id);
});

test("Manage removes saved versions with their runs, preserves drafts, and rejects a changed pipeline", async ({
  page,
  request,
}) => {
  const initial = await snapshot(request);
  const name = initial.document.name;
  const publish = async (revision) =>
    (
      await request.post(`/api/sessions/${id}/save`, {
        headers,
        data: { revision },
      })
    ).json();
  expect((await publish(0)).savedVersion).toBe(1);
  const job = await (
    await request.post("/api/executions", {
      headers,
      data: {
        scope: "pipeline",
        pipeline: name,
        version: 1,
        configuration: { source: "empty" },
        instanceId: initial.instanceId,
        idempotencyKey: randomUUID(),
      },
    })
  ).json();
  const done = await waitJob(page, job);
  expect(done.status).toBe("completed");
  const secondSource =
    initial.document.edn.replace(":params [4]", ":params [6]") +
    "\n; preserve this draft comment\n";
  expect(
    (
      await request.post(`/api/sessions/${id}/source`, {
        headers,
        data: { revision: 0, edn: secondSource },
      })
    ).ok(),
  ).toBeTruthy();
  expect((await publish(1)).savedVersion).toBe(2);
  await page.getByRole("button", { name: "Manage", exact: true }).click();
  let manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await manager
    .getByRole("button", { name: "Saved pipelines", exact: true })
    .click();
  await manager
    .getByRole("textbox", { name: "Filter cleanup list" })
    .fill(name);
  await manager
    .getByRole("button", { name: `Delete version ${name} v1`, exact: true })
    .click();
  await page
    .getByRole("dialog", { name: "Delete saved version", exact: true })
    .getByRole("button", { name: "Delete saved version", exact: true })
    .click();
  await expect
    .poll(async () =>
      (await request.get(`/api/definitions/${name}/1`)).status(),
    )
    .toBe(404);
  expect((await request.get(`/api/runs/${done.runId}`)).status()).toBe(404);
  expect((await request.get(`/api/definitions/${name}/2`)).status()).toBe(200);
  expect((await snapshot(request)).document.edn).toBe(secondSource);
  manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await manager
    .getByRole("button", { name: `Delete pipeline ${name}`, exact: true })
    .click();
  const thirdSource = secondSource.replace(":params [6]", ":params [8]");
  await request.post(`/api/sessions/${id}/source`, {
    headers,
    data: { revision: 1, edn: thirdSource },
  });
  expect((await publish(2)).savedVersion).toBe(3);
  let confirmation = page.getByRole("dialog", {
    name: "Delete pipeline",
    exact: true,
  });
  await confirmation
    .getByRole("button", { name: "Delete pipeline", exact: true })
    .click();
  await expect(confirmation.getByRole("alert")).toContainText(
    "versions changed",
  );
  expect((await request.get(`/api/definitions/${name}/2`)).status()).toBe(200);
  expect((await request.get(`/api/definitions/${name}/3`)).status()).toBe(200);
  await confirmation
    .getByRole("button", { name: "Refresh list", exact: true })
    .click();
  manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await manager
    .getByRole("button", { name: `Delete pipeline ${name}`, exact: true })
    .click();
  confirmation = page.getByRole("dialog", {
    name: "Delete pipeline",
    exact: true,
  });
  await confirmation
    .getByRole("button", { name: "Delete pipeline", exact: true })
    .click();
  await expect
    .poll(async () =>
      (await request.get(`/api/definitions/${name}/3`)).status(),
    )
    .toBe(404);
  expect((await snapshot(request)).document.edn).toBe(thirdSource);
  expect((await publish(2)).savedVersion).toBe(4);
});

test("deleting a saved preview preserves unacknowledged text in another working draft", async ({
  page,
  request,
}) => {
  await source(page);
  const raw = "; keep this unacknowledged text\n{:unfinished";
  let release;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  await page.route(`**/api/sessions/${id}/source`, async (route) => {
    await held;
    await route.continue();
  });
  try {
    await replace(page, editor(page, "Pipeline"), raw);
    const name = `cleanup-preserve-${randomUUID()}`;
    const other = await (
      await request.post("/api/sessions", {
        headers,
        data: { edn: `{:name :${name} :tasks []}` },
      })
    ).json();
    sessions.add(other.id);
    await request.post(`/api/sessions/${other.id}/save`, {
      headers,
      data: { revision: 0 },
    });
    const display = name
      .replaceAll("-", " ")
      .replace(/^./, (x) => x.toUpperCase());
    await choose(page, `Open ${display}`);
    await page.getByRole("button", { name: "Manage", exact: true }).click();
    const manager = page.getByRole("dialog", {
      name: "Manage pipelines and drafts",
      exact: true,
    });
    await manager
      .getByRole("button", { name: "Saved pipelines", exact: true })
      .click();
    await manager
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(name);
    await manager
      .getByRole("button", { name: `Delete pipeline ${name}`, exact: true })
      .click();
    await page
      .getByRole("dialog", { name: "Delete pipeline", exact: true })
      .getByRole("button", { name: "Delete pipeline", exact: true })
      .click();
    await expect
      .poll(async () =>
        (await request.get(`/api/definitions/${name}/1`)).status(),
      )
      .toBe(404);
    const pending = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("collet.pending.restore") || "null"),
    );
    expect(pending?.edn).toBe(raw);
    expect(pending?.id).toBe(id);
  } finally {
    release();
  }
});

for (const reconnect of [false, true]) {
  test(`external deletion removes cached draft entries ${reconnect ? "after reconnect" : "while connected"} and after reload`, async ({
    page,
    request,
    context,
  }) => {
    const original = await snapshot(request);
    const other = await (
      await request.post("/api/sessions", {
        headers,
        data: { edn: `{:name :cleanup-other-${randomUUID()} :tasks []}` },
      })
    ).json();
    sessions.add(other.id);
    await page.goto(`/#draft=${other.id}`);
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem("collet.session")))
      .toBe(other.id);
    let writes = 0;
    page.on("request", (r) => {
      if (
        r.method() === "POST" &&
        /\/api\/(sessions|executions)$/.test(r.url())
      )
        writes++;
    });
    if (reconnect) await context.setOffline(true);
    expect(
      (
        await request.post(`/api/sessions/${id}/delete`, {
          headers,
          data: { revision: original.revision },
        })
      ).ok(),
    ).toBeTruthy();
    const before = (await allDrafts(request)).map((d) => d.id).sort();
    if (reconnect) {
      await context.setOffline(false);
      await page.evaluate(() => window.dispatchEvent(new Event("online")));
    }
    const display = original.document.name
      .replaceAll("-", " ")
      .replace(/^./, (x) => x.toUpperCase());
    for (let n = 0; n < 2; n++) {
      await expect
        .poll(() =>
          page.evaluate(
            (id) =>
              JSON.parse(localStorage.getItem("collet.drafts"))[id]?.deleted,
            id,
          ),
        )
        .toBe(true);
      await page
        .getByRole("button", { name: "Switch pipeline", exact: true })
        .click();
      const menu = page.getByRole("dialog", {
        name: "Switch pipeline",
        exact: true,
      });
      await menu.getByRole("combobox").fill(display);
      await expect(
        menu.getByRole("option", {
          name: new RegExp(`^(Open draft|Recover text) ${display}`),
        }),
      ).toHaveCount(0);
      await page.keyboard.press("Escape");
      expect(
        await page.evaluate(() => localStorage.getItem("collet.session")),
      ).toBe(other.id);
      if (n === 0) await page.reload();
    }
    expect((await allDrafts(request)).map((d) => d.id).sort()).toEqual(before);
    expect(writes).toBe(0);
  });
}

for (const fixture of [
  {
    name: "incomplete",
    raw: "; keep spacing\n{:name :unfinished\n :tasks [",
    preview: false,
  },
  { name: "empty-current", raw: "", preview: false, current: true },
  {
    name: "source-only-preview",
    raw: "; exact preview text\n{:name :local-preview :tasks []}\n",
    preview: true,
    sourceOnly: true,
  },
]) {
  test(`deleted ${fixture.name} copy preserves raw text in Recovery without automatic recreation`, async ({
    page,
    request,
  }) => {
    const missing = randomUUID();
    const before = (await allDrafts(request)).map((d) => d.id).sort();
    let creates = 0;
    let sourceWrites = 0;
    page.on("request", (r) => {
      if (r.method() !== "POST") return;
      if (/\/api\/sessions$/.test(r.url())) creates++;
      if (/\/(source|execute|save)$/.test(r.url())) sourceWrites++;
    });
    await page.addInitScript(
      ({ missing, fixture }) => {
        if (sessionStorage.getItem("draft-list-fixture")) return;
        sessionStorage.setItem("draft-list-fixture", "seeded");
        const pending = fixture.sourceOnly
          ? null
          : { id: missing, kind: "source", revision: 0, edn: fixture.raw };
        const copies = JSON.parse(
          localStorage.getItem("collet.drafts") || "{}",
        );
        copies[missing] = {
          id: missing,
          name: `recovery-${fixture.name}`,
          edn: fixture.raw,
          preview: fixture.preview,
          pending,
        };
        localStorage.setItem("collet.drafts", JSON.stringify(copies));
        if (fixture.current) {
          localStorage.setItem("collet.session", missing);
          localStorage.setItem(
            "collet.pending.restore",
            JSON.stringify(pending),
          );
        }
      },
      { missing, fixture },
    );
    await page.goto(
      `/?draft-list-fixture=${missing}#draft=${fixture.current ? missing : id}`,
    );
    const recoveryName = `Recover text Recovery ${fixture.name.replaceAll("-", " ")}`;
    for (let n = 0; n < 2; n++) {
      await expect
        .poll(() =>
          page.evaluate(
            (missing) =>
              JSON.parse(localStorage.getItem("collet.drafts"))[missing]
                ?.deleted,
            missing,
          ),
        )
        .toBe(true);
      const copy = await page.evaluate(
        (missing) => JSON.parse(localStorage.getItem("collet.drafts"))[missing],
        missing,
      );
      expect(copy.edn).toBe(fixture.raw);
      if (!fixture.sourceOnly) expect(copy.pending.edn).toBe(fixture.raw);
      await page
        .getByRole("button", { name: "Switch pipeline", exact: true })
        .click();
      const menu = page.getByRole("dialog", {
        name: "Switch pipeline",
        exact: true,
      });
      await menu
        .getByRole("combobox")
        .fill(`recovery ${fixture.name.replaceAll("-", " ")}`);
      await expect(menu.getByText("Recovery", { exact: true })).toBeVisible();
      await expect(
        menu.getByRole("option", { name: new RegExp(`^${recoveryName}`) }),
      ).toBeVisible();
      await expect(
        menu.getByRole("option", { name: /^Open (current )?draft Recovery / }),
      ).toHaveCount(0);
      await page.keyboard.press("Escape");
      if (n === 0) await page.reload();
    }
    expect(creates).toBe(fixture.current ? 1 : 0);
    expect(sourceWrites).toBe(0);
    expect((await allDrafts(request)).map((d) => d.id).sort()).toEqual(before);
    await choose(page, recoveryName);
    await expect(editor(page, "Pipeline")).toBeVisible();
    const recoveredId = await page.evaluate(() =>
      localStorage.getItem("collet.session"),
    );
    expect(recoveredId).not.toBe(missing);
    const recovered = await (
      await request.get(`/api/sessions/${recoveredId}`)
    ).json();
    expect(recovered.preview).toBe(true);
    expect(recovered.document.edn).toBe(fixture.raw);
    expect((await request.get(`/api/sessions/${missing}`)).status()).toBe(404);
    await page.reload();
    await expect(editor(page, "Pipeline")).toBeVisible();
    const pending = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("collet.pending.restore") || "null"),
    );
    expect(
      pending?.edn ??
        (await (await request.get(`/api/sessions/${recoveredId}`)).json())
          .document.edn,
    ).toBe(fixture.raw);
    expect(sourceWrites).toBe(0);
    expect((await allDrafts(request)).map((d) => d.id).sort()).toEqual(before);
  });
}

test("navigating away from a missing saved preview keeps its raw Recovery copy", async ({
  page,
  request,
}) => {
  const raw =
    "; retain original formatting\n{:name :recovery-saved-preview :version 1\n :tasks []}\n";
  const preview = await (
    await request.post("/api/sessions", {
      headers,
      data: { edn: raw, configEdn: "{}", preview: true },
    })
  ).json();
  sessions.add(preview.id);
  await page.goto(`/#draft=${preview.id}`);
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem("collet.session")))
    .toBe(preview.id);
  await source(page);
  await expect(editor(page, "Pipeline")).toContainText("retain original formatting");
  await canvas(page);
  expect(
    (
      await request.post(`/api/sessions/${preview.id}/close`, {
        headers,
        data: {},
      })
    ).ok(),
  ).toBeTruthy();
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect
    .poll(() =>
      page.evaluate(
        (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id]?.deleted,
        preview.id,
      ),
    )
    .toBe(true);
  await choose(page, "Open draft Ui");
  await page
    .getByRole("button", { name: "Switch pipeline", exact: true })
    .click();
  const menu = page.getByRole("dialog", {
    name: "Switch pipeline",
    exact: true,
  });
  await menu.getByRole("combobox").fill("Recover text Recovery saved preview");
  await expect(
    menu.getByRole("option", { name: /^Recover text Recovery saved preview/ }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id]?.edn,
      preview.id,
    ),
  ).toBe(raw);
});

for (const status of [200, 503]) {
  test(`a cached draft absent from the catalog remains available when its lookup returns ${status}`, async ({
    page,
    request,
  }) => {
    const original = await snapshot(request);
    const other = await (
      await request.post("/api/sessions", {
        headers,
        data: { edn: `{:name :pagination-${randomUUID()} :tasks []}` },
      })
    ).json();
    sessions.add(other.id);
    await page.goto(`/#draft=${other.id}`);
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem("collet.session")))
      .toBe(other.id);
    await page.route("**/api/catalog", async (route) => {
      const response = await route.fetch();
      const catalog = await response.json();
      await route.fulfill({
        response,
        json: { ...catalog, drafts: catalog.drafts.filter((d) => d.id !== id) },
      });
    });
    let probes = 0;
    await page.route(`**/api/sessions/${id}`, async (route) => {
      probes++;
      if (status === 503)
        await route.fulfill({ status, json: { message: "temporary outage" } });
      else await route.continue();
    });
    await page.evaluate(() => window.dispatchEvent(new Event("online")));
    await expect.poll(() => probes).toBeGreaterThan(0);
    const display = original.document.name
      .replaceAll("-", " ")
      .replace(/^./, (x) => x.toUpperCase());
    await page
      .getByRole("button", { name: "Switch pipeline", exact: true })
      .click();
    const menu = page.getByRole("dialog", {
      name: "Switch pipeline",
      exact: true,
    });
    await menu.getByRole("combobox").fill(display);
    await expect(
      menu.getByRole("option", { name: new RegExp(`^Open draft ${display}`) }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id]?.deleted,
        id,
      ),
    ).not.toBe(true);
    expect((await snapshot(request)).document.edn).toBe(original.document.edn);
  });
}

test("deletion checks and explicit recovery preserve text typed while responses are delayed", async ({
  page,
  request,
}) => {
  await source(page);
  await page.route(`**/api/sessions/${id}/source`, (route) => route.abort());
  const first = "; first pending text\n{:name :unfinished :tasks [";
  const second = "; typed during deletion lookup\n{:name :unfinished :tasks [ ";
  const latest = "; typed during recovery\n{:name :unfinished :tasks [  ";
  await replace(page, editor(page, "Pipeline"), first);
  let release;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  let releaseSnapshot;
  const staleSnapshot = new Promise((resolve) => {
    releaseSnapshot = resolve;
  });
  let snapshotHeld = false;
  let checked = false;
  await page.route(`**/api/sessions/${id}`, async (route) => {
    const response = await route.fetch();
    if (response.status() === 200 && !snapshotHeld) {
      snapshotHeld = true;
      await staleSnapshot;
    }
    if (response.status() === 404) {
      checked = true;
      await held;
    }
    await route.fulfill({ response });
  });
  try {
    await page.evaluate(() => window.dispatchEvent(new Event("online")));
    await expect.poll(() => snapshotHeld).toBe(true);
    const original = await snapshot(request);
    expect(
      (
        await request.post(`/api/sessions/${id}/delete`, {
          headers,
          data: { revision: original.revision },
        })
      ).ok(),
    ).toBeTruthy();
    await expect.poll(() => checked).toBe(true);
    await replace(page, editor(page, "Pipeline"), second);
  } finally {
    release();
  }
  await expect
    .poll(() =>
      page.evaluate(
        (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id]?.deleted,
        id,
      ),
    )
    .toBe(true);
  expect(
    await page.evaluate(
      (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id].pending.edn,
      id,
    ),
  ).toBe(second);
  const delayed = page.waitForResponse(
    (r) => r.url().endsWith(`/api/sessions/${id}`) && r.status() === 200,
  );
  releaseSnapshot();
  await delayed;
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  expect(
    await page.evaluate(
      (id) => JSON.parse(localStorage.getItem("collet.drafts"))[id]?.deleted,
      id,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      (id) =>
        JSON.parse(localStorage.getItem("collet.drafts"))[id]?.pending?.edn,
      id,
    ),
  ).toBe(second);
  let recover;
  const recovering = new Promise((resolve) => {
    recover = resolve;
  });
  let recoveryStarted = false;
  await page.route("**/api/sessions", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch();
    recoveryStarted = true;
    await recovering;
    await route.fulfill({ response });
  });
  try {
    await page
      .getByRole("button", { name: "Switch pipeline", exact: true })
      .click();
    const menu = page.getByRole("dialog", {
      name: "Switch pipeline",
      exact: true,
    });
    await menu.getByRole("combobox").fill("Recover text");
    await menu.getByRole("option", { name: /^Recover text / }).click();
    await expect.poll(() => recoveryStarted).toBe(true);
    await replace(page, editor(page, "Pipeline"), latest);
  } finally {
    recover();
  }
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem("collet.session")))
    .not.toBe(id);
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          JSON.parse(localStorage.getItem("collet.pending.restore") || "null")
            ?.edn,
      ),
    )
    .toBe(latest);
  await page.reload();
  await expect(editor(page, "Pipeline")).toContainText("typed during recovery");
  expect((await request.get(`/api/sessions/${id}`)).status()).toBe(404);
});

for (const width of [1440, 1100, 760, 480]) {
  test(`Manage keeps long names and confirmation controls usable at ${width}px`, async ({
    page,
    request,
  }, testInfo) => {
    const name = `international-inventory-reconciliation-for-european-and-north-american-marketplaces-${randomUUID()}`;
    const initial = await snapshot(request);
    const changed = await request.post(`/api/sessions/${id}/source`, {
      headers,
      data: {
        revision: initial.revision,
        edn: initial.document.edn.replace(
          `:${initial.document.name}`,
          `:${name}`,
        ),
      },
    });
    expect(changed.ok()).toBeTruthy();
    const saved = await request.post(`/api/sessions/${id}/save`, {
      headers,
      data: { revision: (await changed.json()).revision },
    });
    expect(saved.ok()).toBeTruthy();
    await page.setViewportSize({ width, height: 860 });
    await page.getByRole("button", { name: "Manage", exact: true }).click();
    const dialog = page.getByRole("dialog", {
      name: "Manage pipelines and drafts",
      exact: true,
    });
    await dialog
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(name);
    const draft = dialog.getByRole("article").filter({ hasText: name });
    await expect(draft).toBeVisible();
    const fits = async (scope) =>
      scope.evaluate((element) => {
        const box = element.getBoundingClientRect();
        return (
          box.left >= 0 &&
          box.right <= innerWidth &&
          box.top >= 0 &&
          box.bottom <= innerHeight &&
          element.scrollWidth <= element.clientWidth + 1
        );
      });
    expect(await fits(dialog)).toBe(true);
    const text = await draft.locator("strong").boundingBox();
    const remove = await draft
      .getByRole("button", { name: /^Delete draft/ })
      .boundingBox();
    expect(text.x + text.width).toBeLessThanOrEqual(remove.x);
    await dialog
      .getByRole("button", { name: "Saved pipelines", exact: true })
      .press("Enter");
    await dialog
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(name);
    const version = dialog.getByRole("button", {
      name: `Delete version ${name} v1`,
      exact: true,
    });
    await expect(version).toBeVisible();
    expect(await fits(dialog)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`manage-${width}.png`) });
    await version.click();
    const confirmation = page.getByRole("dialog", {
      name: "Delete saved version",
      exact: true,
    });
    await expect(confirmation).toContainText(name);
    expect(await fits(confirmation)).toBe(true);
    await expect(
      confirmation.getByRole("button", {
        name: "Delete saved version",
        exact: true,
      }),
    ).toBeInViewport();
    expect(
      await confirmation
        .getByRole("button", { name: "Delete saved version", exact: true })
        .evaluate(buttonContrast),
    ).toBeGreaterThanOrEqual(4.5);
    await confirmation
      .getByRole("button", { name: "Cancel", exact: true })
      .click();
    expect((await request.get(`/api/definitions/${name}/1`)).status()).toBe(
      200,
    );
    await dialog
      .getByRole("button", { name: "Drafts", exact: true })
      .press("Enter");
    await dialog
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(name);
    await expect(
      dialog.getByRole("button", {
        name: `Delete draft ${name} ${id.slice(0, 8)}`,
        exact: true,
      }),
    ).toBeVisible();
    expect(await fits(dialog)).toBe(true);
    await dialog
      .getByRole("button", { name: "Saved pipelines", exact: true })
      .press("Enter");
    await dialog
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(name);
    await expect(version).toBeVisible();
    await dialog.getByRole("button", { name: "Close", exact: true }).click();
    await page
      .getByRole("button", { name: "Run pipeline", exact: true })
      .click();
    const runChoice = page.getByRole("dialog", {
      name: "Run pipeline",
      exact: true,
    });
    await expect(runChoice).toBeVisible();
    expect(await fits(runChoice)).toBe(true);
    await runChoice
      .getByRole("button", { name: "Cancel", exact: true })
      .click();
  });
}

for (const cache of ["legacy document", "draft copy"]) {
  test(`expired legacy action recovery preserves raw source from ${cache}`, async ({
    page,
    request,
  }) => {
    const expired = randomUUID();
    const raw = ";; retain this full pipeline verbatim\n" + pipeline;
    const fragment =
      ";; incomplete legacy edit\n{:name :legacy :type :clj/identity :params [";
    const oldAction =
      ":name :second :type :clj/* :selectors {n [:state :first]} :params [n 2]";
    const expected = raw.replace(`{${oldAction}}`, fragment);
    expect(expected).not.toBe(raw);
    await page.addInitScript(
      ({ expired, raw, fragment, cache }) => {
        if (sessionStorage.getItem("legacy-recovery-seeded")) return;
        sessionStorage.setItem("legacy-recovery-seeded", "true");
        localStorage.clear();
        localStorage.setItem("collet.session", expired);
        const pending = {
          kind: "action",
          id: expired,
          task: "source",
          index: 1,
          revision: 0,
          edn: fragment,
        };
        localStorage.setItem("collet.pending.restore", JSON.stringify(pending));
        if (cache === "legacy document")
          localStorage.setItem("collet.document", JSON.stringify({ edn: raw }));
        else
          localStorage.setItem(
            "collet.drafts",
            JSON.stringify({
              [expired]: { id: expired, name: "legacy", edn: raw, pending },
            }),
          );
      },
      { expired, raw, fragment, cache },
    );
    expect((await request.get(`/api/sessions/${expired}`)).status()).toBe(404);
    const before = (await allDrafts(request)).map((d) => d.id).sort();
    await page.goto(
      `/?legacy-recovery=${expired}#view=source&draft=${expired}`,
    );
    await expect(editor(page, "Pipeline")).toContainText(
      "incomplete legacy edit",
    );
    const recoveredId = await page.evaluate(() =>
      localStorage.getItem("collet.session"),
    );
    const recovered = await (
      await request.get(`/api/sessions/${recoveredId}`)
    ).json();
    expect(recovered.document.edn).toBe(expected);
    expect(recovered.preview).toBe(true);
    await page.reload();
    await expect(editor(page, "Pipeline")).toContainText(
      "retain this full pipeline verbatim",
    );
    const reopened = await (
      await request.get(`/api/sessions/${recoveredId}`)
    ).json();
    expect(reopened.document.edn).toBe(expected);
    expect((await allDrafts(request)).map((d) => d.id).sort()).toEqual(before);
  });
}

test("unplaceable legacy actions retain their full source and fragment across reload", async ({
  page,
  request,
}) => {
  const expired = randomUUID();
  const raw =
    ";; retain ambiguous source\n" +
    pipeline.replace(":name :second", ":name :first");
  const fragment = "{:name :unplaced :params [";
  await page.addInitScript(
    ({ expired, raw, fragment }) => {
      if (sessionStorage.getItem("legacy-recovery-seeded")) return;
      sessionStorage.setItem("legacy-recovery-seeded", "true");
      localStorage.clear();
      localStorage.setItem("collet.session", expired);
      localStorage.setItem("collet.document", JSON.stringify({ edn: raw }));
      localStorage.setItem(
        "collet.pending.restore",
        JSON.stringify({
          kind: "action",
          id: expired,
          task: "source",
          index: 1,
          revision: 0,
          edn: fragment,
        }),
      );
    },
    { expired, raw, fragment },
  );
  let sourceWrites = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && r.url().endsWith("/source")) sourceWrites++;
  });
  for (let n = 0; n < 2; n++) {
    if (n) await page.reload();
    else
      await page.goto(
        `/?legacy-recovery=${expired}#view=source&draft=${expired}`,
      );
    await expect(editor(page, "Pipeline")).toContainText(
      "retain ambiguous source",
    );
    const pending = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("collet.pending.restore")),
    );
    expect(pending.edn).toBe(raw);
    expect(pending.unplacedAction.edn).toBe(fragment);
    const currentId = await page.evaluate(() =>
      localStorage.getItem("collet.session"),
    );
    expect(
      (await (await request.get(`/api/sessions/${currentId}`)).json()).document
        .edn,
    ).toBe(raw);
  }
  expect(sourceWrites).toBe(0);
});

test("deleting a working draft in Runs and reloading creates no replacement draft", async ({
  page,
  request,
}) => {
  const { draft, runId } = await fixtureRun(page, request);
  await page.getByRole("button", { name: "Runs", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Open in Build", exact: true }),
  ).toBeVisible();
  const confirmation = await draftDeletion(page, draft.document.name);
  await confirmation
    .getByRole("button", { name: "Delete draft", exact: true })
    .click();
  const manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await manager.getByRole("button", { name: "Close", exact: true }).click();
  expect((await request.get(`/api/sessions/${id}`)).status()).toBe(404);
  const before = (await allDrafts(request)).map((d) => d.id).sort();
  for (let n = 0; n < 2; n++) {
    await page.reload();
    await expect(
      page.getByRole("button", { name: "Open in Build", exact: true }),
    ).toBeVisible();
    expect(page.url()).toContain(`run=${runId}`);
    expect((await allDrafts(request)).map((d) => d.id).sort()).toEqual(before);
  }
  expect((await request.get(`/api/runs/${runId}`)).status()).toBe(200);
});

test("storage failure after deletion commits clears the selection and reports success with a warning", async ({
  page,
  request,
}) => {
  const original = await snapshot(request);
  const confirmation = await draftDeletion(page, original.document.name);
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key === "collet.drafts")
        throw new DOMException(
          "Recovery storage blocked",
          "QuotaExceededError",
        );
      return original.call(this, key, value);
    };
  });
  await confirmation
    .getByRole("button", { name: "Delete draft", exact: true })
    .click();
  const manager = page.getByRole("dialog", {
    name: "Manage pipelines and drafts",
    exact: true,
  });
  await expect(manager).toBeVisible();
  await expect(manager.getByRole("alert")).toContainText("Deletion succeeded");
  await expect(manager.getByRole("alert")).toContainText("recovery storage");
  expect((await request.get(`/api/sessions/${id}`)).status()).toBe(404);
  expect(
    await page.evaluate(() => localStorage.getItem("collet.session")),
  ).not.toBe(id);
  await expect(
    manager.getByRole("button", {
      name: `Delete draft ${original.document.name} ${id.slice(0, 8)}`,
      exact: true,
    }),
  ).toHaveCount(0);
});

test("deletion waits for a pending reset before changing the current draft", async ({
  page,
  request,
}) => {
  const original = await snapshot(request);
  let release, committed;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  const resetCommitted = new Promise((resolve) => {
    committed = resolve;
  });
  await page.route(`**/api/sessions/${id}/reset`, async (route) => {
    const response = await route.fetch();
    committed();
    await held;
    await route.fulfill({ response });
  });
  try {
    await page
      .getByRole("button", { name: "Switch pipeline", exact: true })
      .click();
    await page
      .getByRole("dialog", { name: "Switch pipeline", exact: true })
      .getByRole("option", { name: /^Reset preview results/ })
      .click();
    await resetCommitted;
    const confirmation = await draftDeletion(page, original.document.name);
    const remove = confirmation.getByRole("button", {
      name: "Delete draft",
      exact: true,
    });
    await expect(remove).toBeDisabled();
    expect((await request.get(`/api/sessions/${id}`)).status()).toBe(200);
    release();
    await expect(remove).toBeEnabled();
    await remove.click();
    await expect(
      page.getByRole("dialog", {
        name: "Manage pipelines and drafts",
        exact: true,
      }),
    ).toBeVisible();
    const selectedId = await page.evaluate(() =>
      localStorage.getItem("collet.session"),
    );
    expect(selectedId).not.toBe(id);
    expect(
      (await (await request.get(`/api/sessions/${selectedId}`)).json()).preview,
    ).toBe(true);
    expect((await request.get(`/api/sessions/${id}`)).status()).toBe(404);
  } finally {
    release();
  }
});

test("version deletion waits for a pending run snapshot and never redisplays deleted history", async ({
  page,
  request,
}) => {
  const { draft, runId } = await fixtureRun(page, request);
  let release, committed;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  const snapshotRead = new Promise((resolve) => {
    committed = resolve;
  });
  let first = true;
  await page.route(`**/api/runs/${runId}`, async (route) => {
    const response = await route.fetch();
    if (first) {
      first = false;
      committed();
      await held;
    }
    await route.fulfill({ response });
  });
  try {
    await page.getByRole("button", { name: "Runs", exact: true }).click();
    await snapshotRead;
    await page.getByRole("button", { name: "Manage", exact: true }).click();
    const manager = page.getByRole("dialog", {
      name: "Manage pipelines and drafts",
      exact: true,
    });
    await manager
      .getByRole("button", { name: "Saved pipelines", exact: true })
      .click();
    await manager
      .getByRole("textbox", { name: "Filter cleanup list" })
      .fill(draft.document.name);
    await manager
      .getByRole("button", {
        name: `Delete version ${draft.document.name} v1`,
        exact: true,
      })
      .click();
    const remove = page
      .getByRole("dialog", { name: "Delete saved version", exact: true })
      .getByRole("button", { name: "Delete saved version", exact: true });
    await expect(remove).toBeDisabled();
    expect((await request.get(`/api/runs/${runId}`)).status()).toBe(200);
    release();
    await expect(remove).toBeEnabled();
    await remove.click();
    await manager.getByRole("button", { name: "Close", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`draft=${id}`));
    expect((await request.get(`/api/runs/${runId}`)).status()).toBe(404);
    expect(
      (await request.get(`/api/definitions/${draft.document.name}/1`)).status(),
    ).toBe(404);
    expect((await snapshot(request)).document.edn).toBe(draft.document.edn);
    await page.reload();
    await expect(
      page.getByRole("region", { name: "Pipeline graph", exact: true }),
    ).toBeVisible();
    expect(page.url()).not.toContain(`run=${runId}`);
  } finally {
    release();
  }
});

test("fixture runs from the action playground keep live results separate", async ({ page, request }) => {
  await action(page, "second");
  await page.getByRole("button", { name: "Fixtures…", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Run with fixtures" });
  await dialog.getByRole("textbox", { name: "Name", exact: true }).fill("Mock the second action");
  await dialog.getByRole("textbox", { name: "Fixture EDN" }).fill("{:mocks {:second {:value 42}}}");
  await dialog.getByRole("button", { name: "Save case" }).click();
  await dialog.getByRole("button", { name: "Run action", exact: true }).click();
  await expect(dialog.getByRole("region", { name: "Fixture result" })).toContainText("Mocked: second");
  await expect(dialog.getByRole("region", { name: "Fixture result" })).toContainText('"value": 42');
  expect((await snapshot(request)).tasks.source).toBeUndefined();
});
