import { useEffect, useState } from "react";
import { Button, Dialog } from "@radix-ui/themes";
import { api } from "./api";

const blank = { name: "", edn: "{:config {}\n :inputs {}\n :state {}\n :bindings {}\n :mocks {}\n :allow #{}}\n" };

export default function FixtureDialog({ draftId, action, task, onClose, onRun }) {
  const [cases, setCases] = useState([]);
  const [fixture, setFixture] = useState(blank);
  const [savedEdn, setSavedEdn] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const endpoint = `/fixtures/${draftId}`;
  useEffect(() => {
    api(endpoint).then((value) => setCases(value.fixtures)).catch((e) => setError(e.message));
  }, [endpoint]);

  async function perform(operation) {
    setError("");
    setBusy(true);
    try { await operation(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  function choose(id) {
    perform(async () => {
      const value = id ? await api(`${endpoint}/${id}`) : blank;
      setFixture(value);
      setSavedEdn(value.fixtureId ? value.edn : null);
      setResult(null);
    });
  }

  const save = () => perform(async () => {
    const receipt = await api(endpoint, fixture);
    setCases((prior) => [...prior.filter((item) => item.fixtureId !== receipt.fixtureId), receipt]);
    setFixture({ ...fixture, ...receipt });
    setSavedEdn(fixture.edn);
    setResult(null);
  });

  function exportEdn() {
    const url = URL.createObjectURL(new Blob([fixture.edn], { type: "application/edn" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${fixture.name || "fixture"}.edn`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const runnable = !busy && fixture.fixtureId && fixture.edn === savedEdn;
  const run = (scope) => perform(async () => {
    const value = await onRun(scope, fixture);
    setResult(value);
  });

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
      <Dialog.Content maxWidth="680px">
        <Dialog.Title>Run with fixtures</Dialog.Title>
        <Dialog.Description>Use synthetic inputs and responses for {task}. Save the case before running it.</Dialog.Description>
        <div className="fixture-fields">
          <label>Fixture case
            <select value={fixture.fixtureId || ""} onChange={(event) => choose(event.target.value)} disabled={busy}>
              <option value="">New case</option>
              {cases.map((item) => <option key={item.fixtureId} value={item.fixtureId}>{item.name}</option>)}
            </select>
          </label>
          <label>Name<input value={fixture.name} onChange={(event) => setFixture({ ...fixture, name: event.target.value })} disabled={busy} /></label>
          <label>Fixture EDN<textarea rows={10} value={fixture.edn} spellCheck={false} disabled={busy} onChange={(event) => setFixture({ ...fixture, edn: event.target.value })} /></label>
          <label>Import EDN<input type="file" accept=".edn" disabled={busy} onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) perform(async () => setFixture({ ...fixture, edn: await file.text() }));
          }} /></label>
        </div>
        {error && <p role="alert">{error}</p>}
        <div className="fixture-buttons">
          <Button onClick={save} disabled={busy || !fixture.name.trim()}>Save case</Button>
          <Button onClick={exportEdn} variant="soft" disabled={busy}>Export EDN</Button>
          {action && <Button onClick={() => run("action")} disabled={!runnable}>Run action</Button>}
          {action && <Button onClick={() => run("prefix")} disabled={!runnable}>Run to here</Button>}
          <Button onClick={() => run("task")} disabled={!runnable}>Run task</Button>
          <Button onClick={onClose} variant="soft" disabled={busy}>Close</Button>
        </div>
        {result && <section aria-label="Fixture result">
          <p>Fixture result · {result.status} · revision {result.fixtureRevision}{result.fresh ? "" : " · stale"}</p>
          {result.mockedActions?.length > 0 && <p>Mocked: {result.mockedActions.join(", ")}</p>}
          <pre className="fixture-result">{JSON.stringify(result.sample ?? null, null, 2)}</pre>
        </section>}
      </Dialog.Content>
    </Dialog.Root>
  );
}
