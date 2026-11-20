import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Flex, TextField } from "@radix-ui/themes";
import { api } from "./api";

export function ActionCatalogue({ target, onClose, onChoose }) {
  const [actions, setActions] = useState([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState(null);
  const [details, setDetails] = useState({});
  const [nextCursor, setNextCursor] = useState(null);
  useEffect(() => {
    if (target) {
      setQuery("");
      setDetails({});
    }
  }, [target]);
  useEffect(() => {
    if (!target) return;
    let active = true;
    const timer = setTimeout(
      () =>
        api(`/actions?query=${encodeURIComponent(query)}`)
          .then((v) => {
            if (active) {
              setActions(v.actions);
              setNextCursor(v.nextCursor);
              setError(null);
            }
          })
          .catch((e) => {
            if (active) setError(e);
          }),
      180,
    );
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [target, query]);
  const describe = async (type) => {
    const detail =
      details[type] ||
      (await api(`/actions/${encodeURIComponent(type.replace(/^:/, ""))}`));
    setDetails((old) => ({ ...old, [type]: detail }));
    return detail;
  };
  const choose = async (a) => {
    try {
      onChoose(await describe(a.type));
    } catch (e) {
      setError(e);
    }
  };
  const more = async () => {
    try {
      const page = await api(
        `/actions?query=${encodeURIComponent(query)}&cursor=${nextCursor}`,
      );
      setActions((old) => [...old, ...page.actions]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Dialog.Root open={!!target} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Content maxWidth="760px">
        <Dialog.Title>Add action</Dialog.Title>
        <Dialog.Description>
          Choose from the DSL catalogue. Running an action can have external
          effects.
        </Dialog.Description>
        <TextField.Root
          aria-label="Search actions"
          placeholder="Search by purpose, type or package"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <Button variant="soft" onClick={() => onChoose(null)}>
          Blank action
        </Button>
        {error && <p role="alert">{error.message}</p>}
        <div className="knowledge-list">
          {actions.map((a) => (
            <article key={a.type}>
              <strong>{a.type}</strong>
              <p>{a.purpose}</p>
              <small>
                {a["dependency-present"]
                  ? "Dependency present"
                  : `Requires ${a.package}`}{" "}
                · {a.effects}
              </small>
              <details
                onToggle={(e) => {
                  if (e.currentTarget.open) describe(a.type).catch(setError);
                }}
              >
                <summary>Parameters and constraints</summary>
                <pre>{details[a.type]?.example}</pre>
                <pre>{details[a.type]?.dependencyEdn}</pre>
                <pre>
                  {JSON.stringify(details[a.type]?.["params-schema"], null, 2)}
                </pre>
                <p>{details[a.type]?.notes}</p>
              </details>
              <Button onClick={() => choose(a)}>Use {a.type}</Button>
            </article>
          ))}
          {nextCursor && <Button onClick={more}>More actions</Button>}
        </div>
      </Dialog.Content>
    </Dialog.Root>
  );
}

export function DraftHistory({ session, open, onClose, onRestore }) {
  const [history, setHistory] = useState([]);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    if (!open || !session) return;
    setSelected(null);
    setError(null);
    api(`/sessions/${session.id}/history`)
      .then((v) => setHistory(v.revisions))
      .catch(setError);
  }, [open, session?.id, session?.revision]);
  const choose = async (revision) => {
    try {
      setSelected(await api(`/sessions/${session.id}/history/${revision}`));
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Dialog.Root open={open} onOpenChange={(value) => !value && onClose()}>
      <Dialog.Content maxWidth="960px">
        <Dialog.Title>Draft history and changes</Dialog.Title>
        <Dialog.Description>
          Compare accepted source revisions. Restoring creates a new revision
          and preserves history.
        </Dialog.Description>
        {error && <p role="alert">{error.message}</p>}
        <div className="history-entries">
          {history.map((r) => (
            <Button
              key={r["draft.edit/revision"]}
              variant="soft"
              onClick={() => choose(r["draft.edit/revision"])}
            >
              r{r["draft.edit/revision"]} · {r["draft.edit/author"]} ·{" "}
              {r["draft.edit/summary"]}
            </Button>
          ))}
        </div>
        {selected && (
          <>
            <div className="source-comparison">
              <label>
                Selected revision r{selected["draft.edit/revision"]}
                <textarea
                  aria-label="Historical source"
                  readOnly
                  value={selected["draft.edit/source"]}
                />
              </label>
              <label>
                Current revision r{session.revision}
                <textarea
                  aria-label="Current source"
                  readOnly
                  value={session.document.edn}
                />
              </label>
            </div>
            <Button
              disabled={selected["draft.edit/revision"] === session.revision}
              onClick={() => onRestore(selected["draft.edit/revision"])}
            >
              Restore selected source
            </Button>
          </>
        )}
      </Dialog.Content>
    </Dialog.Root>
  );
}

export function DraftConflict({ conflict, onCurrent, onReplace }) {
  return (
    <Dialog.Root open={!!conflict}>
      <Dialog.Content maxWidth="960px">
        <Dialog.Title>Draft changed in another client</Dialog.Title>
        <Dialog.Description>
          Your text is preserved. Compare it with the current source before
          choosing what to keep.
        </Dialog.Description>
        <div className="source-comparison">
          <label>
            Your unacknowledged source
            <textarea
              aria-label="Your conflicting source"
              readOnly
              value={conflict?.local.edn || ""}
            />
          </label>
          <label>
            Current shared source
            <textarea
              aria-label="Shared conflicting source"
              readOnly
              value={conflict?.current.document.edn || ""}
            />
          </label>
        </div>
        <div className="dialog-actions">
          <Button variant="soft" onClick={onCurrent}>
            Use current source
          </Button>
          <Button onClick={onReplace}>Replace current with my source</Button>
        </div>
      </Dialog.Content>
    </Dialog.Root>
  );
}

export function AgentHelp({ open, session, selection, onClose }) {
  const [endpoint, setEndpoint] = useState(null);
  const [copyStatus, setCopyStatus] = useState("");
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setEndpoint(null);
    setCopyStatus("");
    api("/health", undefined, { signal: controller.signal })
      .then(({ mcpUrl }) => setEndpoint(mcpUrl || ""))
      .catch(() => {
        if (!controller.signal.aborted) setEndpoint("");
      });
    return () => controller.abort();
  }, [open]);
  const copy = async (value, label) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopyStatus(`Copied ${label}.`);
    } catch {
      setCopyStatus("Could not copy. Check your browser clipboard permissions.");
    }
  };
  const clients = [
    {
      name: "Codex",
      command: `codex mcp add collet --url ${endpoint}`,
    },
    {
      name: "Claude Code",
      command: `claude mcp add --transport http --scope local collet ${endpoint}`,
      note: "Run from the project folder where you use Claude Code.",
    },
  ];
  const reference = `${session?.url || location.href}&view=${selection?.action ? "action" : "canvas"}${selection?.task ? `&task=${encodeURIComponent(selection.task)}` : ""}${selection?.action ? `&action=${encodeURIComponent(selection.action)}` : ""}`;
  return (
    <Dialog.Root open={open} onOpenChange={(value) => !value && onClose()}>
      <Dialog.Content maxWidth="640px">
        <Dialog.Title>Connect agents</Dialog.Title>
        <Dialog.Description>
          Run the command for your client in a terminal on this machine.
        </Dialog.Description>
        {endpoint ? (
          <Flex direction="column" gap="4">
            {clients.map(({ name, command, note }) => (
              <Flex key={name} direction="column" gap="2">
                <Flex align="center" justify="between" gap="2">
                  <h3>{name}</h3>
                  <Button
                    variant="soft"
                    aria-label={`Copy ${name} command`}
                    onClick={() => copy(command, `${name} command`)}
                  >
                    Copy command
                  </Button>
                </Flex>
                {note && <p>{note}</p>}
                <pre className="agent-setup-command">
                  <code>{command}</code>
                </pre>
              </Flex>
            ))}
            <p>
              Start a new agent session and use <code>/mcp</code> to check that{" "}
              <code>collet</code> is connected.
            </p>
          </Flex>
        ) : endpoint === null ? (
          <p role="status">Loading setup commands…</p>
        ) : (
          <p role="alert">
            Could not load setup commands. Reopen this dialog to retry.
          </p>
        )}
        <div className="dialog-actions">
          <span className="agent-setup-status" role="status">
            {copyStatus}
          </span>
          <Button
            variant="soft"
            disabled={!session}
            onClick={() =>
              copy(
                `Continue draft ${session.id}, source revision ${session.revision}. ${reference}`,
                "draft reference",
              )
            }
          >
            Copy draft reference
          </Button>
        </div>
      </Dialog.Content>
    </Dialog.Root>
  );
}

export function LibraryManager({
  open,
  onClose,
  onDeleted,
  commandBusy,
  runCommand,
}) {
  const [tab, setTab] = useState("drafts");
  const [listing, setListing] = useState({ tab: null, rows: [], cursor: null });
  const rows = listing.tab === tab ? listing.rows : [];
  const cursor = listing.tab === tab ? listing.cursor : null;
  const [query, setQuery] = useState("");
  const [target, setTarget] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [warning, setWarning] = useState(null);
  const generation = useRef(0);
  const load = async (next = null) => {
    const request = ++generation.current;
    setBusy(true);
    setError(null);
    try {
      const page = await api(
        `/${tab}?limit=100&maxBytes=65536${next ? `&cursor=${encodeURIComponent(next)}` : ""}`,
      );
      if (request !== generation.current) return;
      setListing((old) => ({
        tab,
        rows: next && old.tab === tab ? [...old.rows, ...page[tab]] : page[tab],
        cursor: page.nextCursor,
      }));
    } catch (e) {
      if (request === generation.current) setError(e);
    } finally {
      if (request === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    if (open) {
      setListing({ tab, rows: [], cursor: null });
      setTarget(null);
      setWarning(null);
      setQuery("");
      load();
    }
  }, [open, tab]);
  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      const receipt = await runCommand(async () => {
        const result =
          target.kind === "draft"
            ? await api(`/sessions/${target.id}/delete`, {
                revision: target.revision,
              })
            : await api(
                `/pipelines/${encodeURIComponent(target.name)}/delete`,
                target.kind === "version"
                  ? { version: target.version }
                  : { latestVersion: target.latestVersion },
              );
        const warnings = [result.cleanupWarning];
        try {
          warnings.push(await onDeleted(target, result));
        } catch (error) {
          warnings.push(
            `Deletion succeeded, but the workspace could not be refreshed: ${error.message}`,
          );
        }
        return {
          ...result,
          cleanupWarning: warnings.filter(Boolean).join(" "),
        };
      });
      setWarning(receipt.cleanupWarning || null);
      setTarget(null);
      await load();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const groups = new Map();
  for (const row of rows) {
    if (!row.name.toLowerCase().includes(query.toLowerCase())) continue;
    if (!groups.has(row.name)) groups.set(row.name, []);
    groups.get(row.name).push(row);
  }
  const caption =
    target?.kind === "draft"
      ? "Delete draft"
      : target?.kind === "version"
        ? "Delete saved version"
        : "Delete pipeline";
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(value) => !value && !busy && onClose()}
    >
      <Dialog.Content
        className="library-manager"
        maxWidth={target ? "440px" : "760px"}
      >
        <Dialog.Title>
          {target ? caption : "Manage pipelines and drafts"}
        </Dialog.Title>
        <Dialog.Description>
          {target
            ? target.kind === "draft"
              ? `Permanently delete draft ${target.name}, revision ${target.revision}, including its edit history and preview results. Saved versions and runs remain.`
              : `Permanently delete ${target.name}${target.kind === "version" ? ` v${target.version}` : ", all saved versions"} and all associated run history and internal outputs. Working drafts and files written to external destinations remain.`
            : "Remove working drafts, individual saved versions, or entire pipelines. Each deletion requires confirmation."}
        </Dialog.Description>
        {error && <p role="alert">{error.message}</p>}
        {warning && <p role="alert">{warning}</p>}
        {target ? (
          <div className="dialog-actions">
            <Button
              variant="soft"
              color="gray"
              disabled={busy}
              onClick={() => {
                setTarget(null);
                setError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              className="library-delete-confirm"
              color="red"
              disabled={busy || commandBusy}
              onClick={remove}
            >
              {caption}
            </Button>
            {error && (
              <Button
                variant="soft"
                color="gray"
                disabled={busy}
                onClick={() => {
                  setTarget(null);
                  load();
                }}
              >
                Refresh list
              </Button>
            )}
          </div>
        ) : (
          <>
            <div className="library-toolbar">
              <div
                className="library-tabs"
                role="group"
                aria-label="Library view"
              >
                <Button
                  variant="ghost"
                  color="gray"
                  aria-pressed={tab === "drafts"}
                  onClick={() => setTab("drafts")}
                >
                  Drafts
                </Button>
                <Button
                  variant="ghost"
                  color="gray"
                  aria-pressed={tab === "pipelines"}
                  onClick={() => setTab("pipelines")}
                >
                  Saved pipelines
                </Button>
              </div>
              <Button
                variant="ghost"
                color="gray"
                disabled={busy}
                onClick={() => load()}
              >
                Refresh
              </Button>
            </div>
            <TextField.Root
              aria-label="Filter cleanup list"
              placeholder="Filter by name"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <div className="knowledge-list library-list">
              {tab === "drafts"
                ? rows
                    .filter((r) =>
                      r.name.toLowerCase().includes(query.toLowerCase()),
                    )
                    .map((row) => (
                      <article className="library-entry" key={row.id}>
                        <div className="library-details">
                          <strong>{row.name}</strong>
                          <p className="library-meta">
                            r{row.revision} ·{" "}
                            {new Date(row.updatedAt).toLocaleString()} ·{" "}
                            <code>{row.id.slice(0, 8)}</code>
                          </p>
                        </div>
                        <Button
                          color="red"
                          variant="ghost"
                          disabled={busy}
                          aria-label={`Delete draft ${row.name} ${row.id.slice(0, 8)}`}
                          onClick={() => setTarget({ kind: "draft", ...row })}
                        >
                          Delete draft
                        </Button>
                      </article>
                    ))
                : [...groups].map(([name, versions]) => (
                    <article key={name}>
                      <div className="library-heading">
                        <strong>{name}</strong>
                        <Button
                          color="red"
                          variant="ghost"
                          disabled={busy}
                          aria-label={`Delete pipeline ${name}`}
                          onClick={() =>
                            setTarget({
                              kind: "pipeline",
                              name,
                              latestVersion: Math.max(
                                ...versions.map((v) => v.version),
                              ),
                            })
                          }
                        >
                          Delete pipeline
                        </Button>
                      </div>
                      <div className="library-versions">
                        {versions
                          .sort((a, b) => b.version - a.version)
                          .map((version) => (
                            <div
                              className="library-version"
                              key={version.version}
                            >
                              <span className="library-version-number">
                                v{version.version}
                              </span>
                              <Button
                                color="red"
                                variant="ghost"
                                disabled={busy}
                                aria-label={`Delete version ${name} v${version.version}`}
                                onClick={() =>
                                  setTarget({ kind: "version", ...version })
                                }
                              >
                                Delete version
                              </Button>
                            </div>
                          ))}
                      </div>
                    </article>
                  ))}
              {!busy && !rows.length && (
                <p className="library-empty">
                  No {tab === "drafts" ? "working drafts" : "saved pipelines"}.
                </p>
              )}
              {cursor && (
                <Button
                  variant="soft"
                  color="gray"
                  disabled={busy}
                  onClick={() => load(cursor)}
                >
                  Load more
                </Button>
              )}
            </div>
            <div className="dialog-actions">
              <Button
                variant="soft"
                color="gray"
                disabled={busy}
                onClick={onClose}
              >
                Close
              </Button>
            </div>
          </>
        )}
      </Dialog.Content>
    </Dialog.Root>
  );
}
