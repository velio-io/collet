import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { clojure } from "@nextjournal/lang-clojure";
import { EditorView, keymap } from "@codemirror/view";
import { isolateHistory } from "@codemirror/commands";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags } from "@lezer/highlight";
import {
  Badge,
  Button,
  Callout,
  Dialog,
  DropdownMenu,
  IconButton,
  Select,
  Tabs,
  Tooltip,
} from "@radix-ui/themes";
import {
  Play,
  Plus,
  Save,
  X,
  FileCode2,
  Pause,
  Square,
  Braces,
  LoaderCircle,
  MoreHorizontal,
  ArrowLeft,
  ChevronRight,
  Search,
  ChevronDown,
  ChevronLeft,
  PanelRight,
  List,
} from "lucide-react";
import Graph, { Status } from "./Graph";
import Inspector, { DataPanel, ErrorNotice } from "./Inspector";
import PipelineNav from "./PipelineNav";
import FixtureDialog from "./FixtureDialog";
import {
  ActionCatalogue,
  DraftHistory,
  DraftConflict,
  AgentHelp,
  LibraryManager,
} from "./SharedDraftTools";
import {
  sourceIndex,
  actionRange,
  mapRegions,
  replaceAction,
  migratePending,
} from "./draft";
import { api, label, title } from "./api";

const blank = "{:name :untitled-pipeline\n :tasks []}\n";
const pendingKey = "collet.pending.restore";
const copiesKey = "collet.drafts";
const editorTheme = EditorView.theme({}, { dark: true });
const codeColors = syntaxHighlighting(
  HighlightStyle.define([
    { tag: [tags.atom, tags.keyword], color: "#a8baff" },
    { tag: tags.number, color: "#f0b86e" },
    { tag: [tags.string, tags.regexp], color: "#91c9a6" },
    { tag: tags.comment, color: "#9b9fa9" },
    { tag: tags.variableName, color: "#d4d7df" },
    { tag: tags.bracket, color: "#a9aeb9" },
  ]),
);
let initialSession;
const sourceJSON = (value) =>
  JSON.stringify(value, (key, item) =>
    key === "configEdn" ? undefined : item,
  );
async function waitExecution(path, command) {
  let job = await api(path, command);
  while (["preparing", "running"].includes(job.status) && !job.runId) {
    job = await api(
      `/executions/${job.id}?waitMs=10000&afterSequence=${job.sequence || 0}`,
    );
  }
  if (job.error)
    throw Object.assign(new Error(job.error.message), { details: job.error });
  return job;
}
function overview(path, task) {
  return api(path, undefined, {
    headers: {
      "X-Collet-View": "overview",
      ...(task ? { "X-Collet-Task": encodeURIComponent(task) } : {}),
    },
  });
}
function mergeDraftText(previous, next) {
  if (previous?.id !== next.id || previous.instanceId !== next.instanceId)
    return next;
  return {
    ...next,
    document: {
      ...next.document,
      edn: next.document.edn ??
        (previous.revision === next.revision ? previous.document.edn : undefined),
    },
    configEdn: next.configEdn ??
      (previous.configurationRevision === next.configurationRevision ? previous.configEdn : undefined),
  };
}
function mergeRunText(previous, next) {
  if (previous?.id !== next.id) return next;
  return {
    ...next,
    document: { ...next.document, edn: next.document.edn ?? previous.document.edn },
    configEdn: next.configEdn ?? previous.configEdn,
  };
}
async function readDraftOverview(id, pending, task) {
  // Legacy action recovery needs the complete source to reattach a fragment.
  return pending?.kind === "action"
    ? api(`/sessions/${id}`)
    : overview(`/sessions/${id}`, task);
}
function openSavedPreview(name, version) {
  return api("/sessions", {
    preview: true,
    origin: { name, version: Number(version) },
    view: "overview",
  });
}
function cached(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || "null");
  } catch {
    return null;
  }
}
const hasRecovery = (copy) => copy?.pending?.edn != null || copy?.edn != null;
async function recoverSession(id, pending, edn, options = {}) {
  const recoveredPending = migratePending(pending, {
    document: { edn },
    configEdn: options.configEdn ?? "{}",
  });
  return {
    ...(await api("/sessions", {
      ...options,
      edn: recoveredPending?.edn ?? edn,
      preview: true,
    })),
    replaced: id,
    recovered: true,
    recoveredPending,
  };
}
function restoreSession() {
  if (!initialSession)
    initialSession = (async () => {
      const id = localStorage.getItem("collet.session");
      const route = new URLSearchParams(location.hash.slice(1));
      const copy = cached(copiesKey)?.[id];
      if (id) {
        try {
          return await readDraftOverview(id, cached(pendingKey) || copy?.pending, route.get("task"));
        } catch (error) {
          if (error.status !== 404) throw error;
        }
      }
      const pending = cached(pendingKey) || copy?.pending;
      const legacy = cached("collet.document");
      const recovery = pending || copy?.edn != null || legacy?.edn != null;
      if (
        (route.get("run") || (route.get("pipeline") && route.get("version"))) &&
        !recovery
      )
        return null;
      if (
        copy &&
        (copy.deleted ||
          copy.preview === false ||
          (copy.preview == null && copy.edn == null))
      )
        return {
          ...(await api("/sessions", {
            edn: blank,
            configEdn: "{}",
            preview: true,
          })),
          deletedCopy: {
            ...copy,
            pending: pending?.id === id ? pending : copy.pending,
            ...(legacy?.edn != null ? { edn: legacy.edn } : {}),
            deleted: true,
          },
        };
      if (id || recovery)
        return recoverSession(
          id,
          pending,
          copy?.edn ?? legacy?.edn ?? (copy?.origin ? undefined : blank),
          legacy || { origin: copy?.origin },
        );
      return api("/sessions", { view: "overview" });
    })().catch((error) => {
      initialSession = null;
      throw error;
    });
  return initialSession;
}
function EdnEditor({ value, onChange, readOnly, name, documentId, onCursor, cursor }) {
  const editor = useRef(null);
  const identity = useRef(null);
  const editorId = `${documentId}:${name}`;
  identity.current = editorId;
  const [formatting, setFormatting] = useState(false);
  const [formatError, setFormatError] = useState(null);
  const format = async () => {
    const view = editor.current;
    if (!view || readOnly || formatting) return;
    const document = view.state.doc;
    const target = identity.current;
    setFormatting(true);
    setFormatError(null);
    try {
      const { edn } = await api("/format", { edn: document.toString() });
      if (
        editor.current !== view ||
        identity.current !== target ||
        !view.dom.isConnected ||
        view.state.doc !== document ||
        view.state.readOnly
      )
        return;
      if (edn !== document.toString())
        view.dispatch({
          changes: { from: 0, to: document.length, insert: edn },
          annotations: isolateHistory.of("full"),
          userEvent: "input.format",
        });
      view.focus();
    } catch (error) {
      setFormatError(error.message);
    } finally {
      setFormatting(false);
    }
  };
  useEffect(() => {
    if (cursor == null || !editor.current) return;
    const view = editor.current;
    const anchor = Math.min(cursor, view.state.doc.length);
    view.dispatch({
      selection: { anchor },
      effects: EditorView.scrollIntoView(anchor, { y: "center" }),
    });
  }, [cursor]);
  const extensions = useMemo(
    () => [
      clojure(),
      codeColors,
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ "aria-label": name }),
    ],
    [name],
  );
  return (
    <div className="edn-editor-shell">
      {!readOnly && (
        <div className="edn-editor-toolbar">
          <span
            className="edn-format-error"
            role={formatError ? "alert" : undefined}
          >
            {formatError}
          </span>
          <Button
            variant="ghost"
            color="gray"
            disabled={formatting}
            aria-label={`Format ${name.replace(" editor", "")}`}
            title="Format EDN (⌘/Ctrl+Shift+F)"
            onClick={format}
          >
            <Braces size={13} />
            {formatting ? "Formatting…" : "Format"}
          </Button>
        </div>
      )}
      <CodeMirror
        key={editorId}
        className="edn-editor"
        theme={editorTheme}
        onCreateEditor={(view) => {
          editor.current = view;
        }}
        onUpdate={(update) => {
          if (update.selectionSet && update.view.hasFocus)
            onCursor?.(update.state.selection.main.head);
        }}
        value={value}
        onChange={(...args) => {
          setFormatError(null);
          onChange?.(...args);
        }}
        height="100%"
        extensions={[
          ...extensions,
          keymap.of([
            {
              key: "Mod-Shift-f",
              run: () => {
                void format();
                return true;
              },
            },
          ]),
        ]}
        readOnly={readOnly}
        basicSetup={{ foldGutter: true, tabSize: 2 }}
      />
    </div>
  );
}

export function App() {
  const [session, setSession] = useState(null);
  const [mode, setMode] = useState("Build");
  const [workspace, setWorkspace] = useState("canvas");
  const [selection, setSelection] = useState({ task: null, action: null });
  const [expanded, setExpanded] = useState(new Set());
  const [graphViews, setGraphViews] = useState({});
  const [draft, setDraft] = useState(null);
  const [conflict, setConflict] = useState(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [agentHelpOpen, setAgentHelpOpen] = useState(false);
  const [fixtureOpen, setFixtureOpen] = useState(false);
  const [actionTarget, setActionTarget] = useState(null);
  const flushQueue = useRef(Promise.resolve());
  const [sourceTab, setSourceTab] = useState("Definition");
  const [actionTab, setActionTab] = useState("Edit");
  const [panelTab, setPanelTab] = useState("Overview");
  const [palette, setPalette] = useState(null);
  const [manageOpen, setManageOpen] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const [runsOpen, setRunsOpen] = useState(false);
  const [runFilter, setRunFilter] = useState("All");
  const [sourceCursor, setSourceCursor] = useState(null);
  const [inspectorTab, setInspectorTab] = useState("Result");
  const [storageFailed, setStorageFailed] = useState(false);
  const [routeReady, setRouteReady] = useState(false);
  const routing = useRef(false);
  const lastHash = useRef("");
  const [run, setRun] = useState(null);
  const [saved, setSaved] = useState(null);
  const [runChoice, setRunChoice] = useState(null);
  const [catalog, setCatalog] = useState({ pipelines: [], runs: [] });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [validation, setValidation] = useState(null);
  const [checkedSource, setCheckedSource] = useState(null);
  const [connected, setConnected] = useState(false);
  const [diagnosticsRevision, setDiagnosticsRevision] = useState(0);
  const [output, setOutput] = useState(null);
  const [availableInputs, setAvailableInputs] = useState(null);
  const [portalUrl, setPortalUrl] = useState(null);
  const copies = useRef(null);
  if (!copies.current) copies.current = cached(copiesKey) || {};
  const [draftCopies, setDraftCopies] = useState(() =>
    Object.values(copies.current),
  );
  const current = useRef({});
  const executing = useRef(false);
  const heading = useRef(null);
  const focusAddedAction = useRef(false);
  const editingInPlace = useRef(false);
  const retainedSamples = useRef(new Map());
  const dirty = mode === "Build" && !saved && !!draft;
  const deletedDraft = !!copies.current[session?.id]?.deleted;
  const build = mode === "Build" && !saved;
  const editable = mode === "Build";
  const previewSession = saved ? saved.session : session;
  const definition =
    mode === "Runs" ? run?.document : previewSession?.document;
  const task = definition?.tasks.find((t) => t.name === selection.task);
  const action = task?.actions.find((a) => a.name === selection.action);
  current.current = { session, saved, mode, run, draft, selection };

  const accept = useCallback((s) => {
    if (copies.current[s.id]?.deleted) return;
    const previous = current.current.session;
    if (previous?.id === s.id && previous.instanceId === s.instanceId &&
        (previous.revision > s.revision || previous.configurationRevision > s.configurationRevision)) return;
    s = mergeDraftText(previous, s);
    current.current.session = s;
    setSession(s);
    const nextCopies = { ...copies.current };
    if (s.deletedCopy) nextCopies[s.deletedCopy.id] = s.deletedCopy;
    const recoveredPending =
      s.recoveredPending ?? nextCopies[s.replaced || s.id]?.pending;
    const recoveredBase = nextCopies[s.replaced || s.id]?.baseVersion;
    const recoveredView = nextCopies[s.replaced || s.id]?.view;
    if (s.replaced) delete nextCopies[s.replaced];
    if (previous?.id !== s.id) {
      const pending = recoveredPending
        ? {
            ...migratePending(recoveredPending, s),
            id: s.id,
            revision: s.replaced ? s.revision : recoveredPending.revision,
          }
        : null;
      current.current.draft = pending;
      setDraft(pending);
      try {
        if (pending) localStorage.setItem(pendingKey, sourceJSON(pending));
        else if (previous) localStorage.removeItem(pendingKey);
      } catch {
        setStorageFailed(true);
      }
    }
    if (
      previous &&
      previous.id !== s.id &&
      !nextCopies[previous.id]?.deleted &&
      previous.document.version &&
      previous.configurationProvided &&
      previous.configuration?.length === 0 &&
      !nextCopies[previous.id]?.pending &&
      !Object.values(previous.tasks).some((entry) => entry.status)
    ) {
      delete nextCopies[previous.id];
      api(`/sessions/${previous.id}/close`, {}).catch(() => {});
    }
    nextCopies[s.id] = {
      id: s.id,
      name: s.document.name,
      preview: !!s.preview,
      ...(s.preview ? { edn: s.document.edn ?? nextCopies[s.id]?.edn, origin: s.origin ?? nextCopies[s.id]?.origin } : {}),
      version: s.document.version,
      baseVersion: s.document.version || recoveredBase,
      view: recoveredView,
      pending:
        current.current.draft?.id === s.id
          ? current.current.draft
          : recoveredPending,
    };
    copies.current = nextCopies;
    setDraftCopies(Object.values(nextCopies));
    try {
      localStorage.setItem("collet.session", s.id);
      localStorage.removeItem("collet.document");
      localStorage.setItem(copiesKey, sourceJSON(nextCopies));
    } catch {
      setStorageFailed(true);
      setError({
        message:
          "Browser storage is full. Keep this tab open and save your work before reloading.",
      });
    }
  }, []);
  const markMissingDraft = useCallback((id) => {
    const copy = copies.current[id];
    if (!copy || copy.deleted) return;
    const active = current.current;
    const next = {
      ...copies.current,
      [id]: {
        ...copy,
        ...(active.session?.id === id && active.draft?.kind === "action"
          ? { edn: active.session.document.edn }
          : {}),
        deleted: true,
      },
    };
    copies.current = next;
    setDraftCopies(Object.values(next));
    try {
      localStorage.setItem(copiesKey, sourceJSON(next));
    } catch {
      setStorageFailed(true);
      setError({
        message:
          "Browser recovery storage could not be updated. Keep this tab open to preserve your text.",
      });
    }
  }, []);
  const acceptSaved = useCallback((s) => {
    setSaved((previous) =>
      previous?.session.id === s.id &&
      previous.session.revision <= s.revision &&
      previous.session.configurationRevision <= s.configurationRevision
        ? { ...previous, session: mergeDraftText(previous.session, s) }
        : previous,
    );
  }, []);
  const changeDraft = (value) => {
    current.current.draft = value;
    setDraft(value);
    setValidation(null);
    const id = value?.id || current.current.session?.id;
    if (id && copies.current[id]) {
      copies.current = {
        ...copies.current,
        [id]: { ...copies.current[id], pending: value },
      };
      setDraftCopies(Object.values(copies.current));
    }
    try {
      if (value) localStorage.setItem(pendingKey, sourceJSON(value));
      else localStorage.removeItem(pendingKey);
      localStorage.setItem(copiesKey, sourceJSON(copies.current));
      setStorageFailed(false);
    } catch {
      setStorageFailed(true);
      setError({
        message:
          "Browser storage is full. Keep this tab open and save your work before reloading.",
      });
    }
  };
  const perform = async (f, { propagate = false } = {}) => {
    if (executing.current) {
      if (propagate)
        throw new Error(
          "Wait for the current command to finish before deleting.",
        );
      return null;
    }
    executing.current = true;
    setBusy(true);
    setError(null);
    try {
      return await f();
    } catch (e) {
      if (propagate) throw e;
      setError(e.details || { message: e.message });
      return null;
    } finally {
      executing.current = false;
      setBusy(false);
    }
  };
  const navigate = (go) => {
    setError(null);
    go();
  };
  const openTask = (name, actionName = null) =>
    navigate(() => {
      setSelection({ task: name, action: actionName });
      setActionTab("Edit");
      setPanelTab("Overview");
      setInspectorTab("Result");
      setDrawer(true);
      setInspectorOpen(true);
      setWorkspace(
        actionName ? "action" : workspace === "source" ? "source" : "canvas",
      );
    });
  const goCanvas = () =>
    navigate(() => {
      setWorkspace("canvas");
      setSelection({ task: null, action: null });
      setPanelTab("Overview");
      setDrawer(false);
      setInspectorOpen(true);
    });
  const openSource = () =>
    navigate(() => {
      setSourceTab("Definition");
      setSelection((previous) => ({ ...previous, action: null }));
      setWorkspace("source");
    });
  const refresh = useCallback(async () => {
    const { session: s, saved: v, run: r, mode: m, selection } = current.current;
    try {
      const selectedTask = selection?.task;
      if (m === "Runs" && r) {
        const next = await overview(`/runs/${r.id}`, selectedTask);
        if (current.current.selection?.task === selectedTask)
          setRun((previous) => previous?.id === r.id ? mergeRunText(previous, next) : previous);
      } else if (v) {
        const next = await overview(`/sessions/${v.session.id}`, selectedTask);
        if (current.current.selection?.task === selectedTask) acceptSaved(next);
      } else if (s) {
        const next = await overview(`/sessions/${s.id}`, selectedTask);
        if (current.current.session?.id === s.id && current.current.selection?.task === selectedTask) accept(next);
      }
      setCatalog(await api("/catalog"));
    } catch (e) {
      if (v && v.session.id !== current.current.saved?.session.id) return;
      if (s && s.id !== current.current.session?.id) return;
      if (e.status === 404) {
        setCatalog(await api("/catalog"));
        setError({
          message:
            "This draft or run was deleted in another client. Select another item or open Manage pipelines and drafts.",
        });
        return;
      }
      setConnected(false);
      if (e.status) setError(e.details || { message: e.message });
    }
  }, [accept, acceptSaved]);
  useEffect(() => {
    if (!catalog.drafts) return;
    let live = true;
    const listed = new Set(catalog.drafts.map((draft) => draft.id));
    // The catalogue is paginated; absence alone does not prove deletion.
    for (const copy of Object.values(copies.current)) {
      if (copy.deleted || listed.has(copy.id)) continue;
      overview(`/sessions/${copy.id}`).catch((error) => {
        if (live && error.status === 404) markMissingDraft(copy.id);
      });
    }
    return () => {
      live = false;
    };
  }, [catalog, markMissingDraft]);
  useEffect(() => {
    const id = saved?.session.id;
    return () => {
      if (id && !copies.current[id])
        api(`/sessions/${id}/close`, {}).catch(() => {});
    };
  }, [saved?.session.id]);
  useEffect(() => {
    let live = true;
    restoreSession()
      .then(async (s) => {
        if (!live) return;
        if (!s) {
          setCatalog(await api("/catalog"));
          await restoreRoute();
          if (live) setRouteReady(true);
          return;
        }
        accept(s);
        const pending = cached(pendingKey) || copies.current[s.id]?.pending;
        if (pending && (pending.id === s.id || s.recovered)) {
          const restored = {
            ...migratePending(pending, s),
            id: s.id,
            revision: s.recovered
              ? s.revision
              : (pending.revision ?? s.revision),
          };
          changeDraft(restored);
          if (restored.migrationError)
            setError({
              message:
                "An older action draft could not be placed safely. Copy its text from these details before editing.",
              details: restored.unplacedAction || pending,
            });
          else {
            setWorkspace(pending.kind === "action" ? "action" : "source");
            if (pending.task) {
              const task = s.document.tasks.find(
                (t) => t.name === pending.task,
              );
              setSelection({
                task: pending.task,
                action: task?.actions[pending.index]?.name,
              });
            }
          }
        }
        const c = await api("/catalog");
        if (live) {
          setCatalog(c);
          await restoreRoute(s);
          setRouteReady(true);
        }
      })
      .catch((e) => live && setError(e.details || { message: e.message }));
    return () => {
      live = false;
    };
  }, [accept]);
  useEffect(() => {
    let timer;
    const stream = new EventSource("/api/events");
    let opened = false;
    const update = (message) => {
      if (!navigator.onLine) return;
      setConnected(true);
      const { mode, run, saved, session } = current.current;
      const scope = mode === "Runs" ? run?.id : (saved?.session || session)?.id;
      if (
        scope &&
        (!message || JSON.parse(message.data).some(
          (event) => event["run-id"] === scope || event["session-id"] === scope,
        ))
      ) {
        setDiagnosticsRevision((revision) => revision + 1);
      }
      clearTimeout(timer);
      timer = setTimeout(refresh, 180);
    };
    stream.onopen = () => {
      if (opened) update();
      else setConnected(navigator.onLine);
      opened = true;
    };
    stream.onerror = () => setConnected(false);
    const offline = () => setConnected(false);
    const online = () => {
      setConnected(stream.readyState === EventSource.OPEN);
      refresh();
    };
    window.addEventListener("offline", offline);
    window.addEventListener("online", online);
    stream.addEventListener("updates", update);
    const poll = setInterval(() => {
      if (current.current.mode === "Runs" && current.current.run) refresh();
    }, 2000);
    return () => {
      stream.close();
      window.removeEventListener("offline", offline);
      window.removeEventListener("online", online);
      clearTimeout(timer);
      clearInterval(poll);
    };
  }, [refresh]);
  useEffect(() => {
    if (mode !== "Build" || saved || !session || draft?.migrationError) return;
    const edn = draft?.edn ?? session.document.edn;
    if (edn == null) return;
    let live = true;
    const timer = setTimeout(
      () =>
        api("/validate", { edn })
          .then(() => {
            if (live) {
              setValidation(null);
              setCheckedSource({ edn, valid: true });
            }
          })
          .catch((e) => {
            if (live) {
              setValidation(e.details || { message: e.message });
              setCheckedSource({ edn, valid: false });
            }
          }),
      650,
    );
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [draft?.edn, session?.document.edn, mode, saved?.name]);
  useEffect(() => {
    if (!storageFailed) return;
    const prevent = (e) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", prevent);
    return () => window.removeEventListener("beforeunload", prevent);
  }, [storageFailed]);
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      if (editingInPlace.current) {
        editingInPlace.current = false;
        return;
      }
      if (workspace === "action" && focusAddedAction.current) {
        document.querySelector('[aria-label="Action EDN editor"]')?.focus();
        focusAddedAction.current = false;
        return;
      }
      const node =
        workspace === "canvas" &&
        document.getElementById(`open-task-${selection.task}`);
      (node || heading.current)?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [
    workspace,
    mode,
    saved?.name,
    saved?.version,
    session?.id,
    selection.action,
    action?.index,
  ]);

  useEffect(() => {
    const id = mode === "Runs" ? run?.id : previewSession?.id;
    if (!id || !selection.task) return;
    let live = true;
    const path = mode === "Runs" ? "/runs" : "/sessions";
    overview(`${path}/${id}`, selection.task).then((next) => {
      if (!live) return;
      if (mode === "Runs") {
        setRun((previous) => previous?.id === id ? mergeRunText(previous, next) : previous);
      } else if (saved) acceptSaved(next);
      else if (current.current.session?.id === id) accept(next);
    }).catch((e) => {
      if (live) setError(e.details || { message: e.message });
    });
    return () => { live = false; };
  }, [mode, run?.id, previewSession?.id, saved?.name, selection.task, accept, acceptSaved]);

  const textArea =
    workspace === "action" || (workspace === "source" && sourceTab === "Definition")
      ? "source"
      : (workspace === "source" && sourceTab === "Configuration") ||
        (drawer && panelTab === "Config")
        ? "configuration"
        : null;
  const textRevision = textArea === "configuration"
    ? previewSession?.configurationRevision
    : previewSession?.revision;
  const textPresent = textArea === "configuration"
    ? (mode === "Runs" ? run?.configEdn : previewSession?.configEdn) !== undefined
    : definition?.edn !== undefined;
  useEffect(() => {
    const id = mode === "Runs" ? run?.id : previewSession?.id;
    if (!id || !textArea || textPresent) return;
    let live = true;
    const path = mode === "Runs" ? "/runs" : "/sessions";
    api(`${path}/${id}/${textArea}`).then((value) => {
      if (!live) return;
      const addText = (s) => textArea === "source"
        ? { ...s, document: { ...s.document, edn: value.edn } }
        : { ...s, configEdn: value.configEdn };
      if (mode === "Runs")
        setRun((previous) => previous?.id === id ? addText(previous) : previous);
      else {
        const currentSession = saved ? current.current.saved?.session : current.current.session;
        if (currentSession?.id !== id) return;
        const matches = textArea === "source"
          ? value.revision === currentSession.revision
          : value.configurationRevision === currentSession.configurationRevision;
        if (matches) {
          if (saved) acceptSaved(addText(currentSession));
          else accept(addText(currentSession));
        } else refresh();
      }
    }).catch((e) => {
      if (live) setError(e.details || { message: e.message });
    });
    return () => { live = false; };
  }, [mode, run?.id, previewSession?.id, saved?.name, textArea, textPresent, textRevision, accept, acceptSaved, refresh]);

  const events =
    mode === "Runs" ? run?.events || [] : previewSession?.events || [];
  const states =
    mode === "Build"
      ? Object.fromEntries(
          Object.entries(previewSession?.tasks || {}).map(([name, entry]) => [
            name,
            { ...entry, actions: { ...entry.actions } },
          ]),
        )
      : Object.fromEntries(
          (run?.tasks || []).map((t) => [
            label(t["task/name"]),
            {
              status: label(t["task/status"]),
              error: t["task/error"],
              actions: {},
            },
          ]),
        );
  for (const event of events) {
    const name = label(event.task),
      actionName = label(event.action);
    if (
      !states[name] ||
      !actionName ||
      (mode === "Build" && event["execution-id"] !== states[name].executionId)
    )
      continue;
    const previous = states[name].actions[actionName] || {};
    const eventName = label(event["mulog/event-name"] || event["collet/event"]);
    const outcome = label(event["mulog/outcome"]);
    const status =
      eventName === "collet/action-skipped" || event["collet/skipped?"]
        ? "skipped"
        : outcome === "error"
          ? "failed"
          : outcome === "ok" || event.preview
            ? "completed"
            : eventName === "collet/action-start" &&
                previous.invocation !== event["invocation-id"]
              ? "running"
              : previous.status;
    states[name].actions[actionName] = {
      ...previous,
      invocation: event["invocation-id"],
      status,
      preview:
        mode === "Build" && previous.preview
          ? previous.preview
          : event.preview || previous.preview,
    };
  }
  const entry = states[task?.name];
  const actionEntry = entry?.actions?.[action?.name];
  const actionEvents = events.filter(
    (e) =>
      label(e.task) === task?.name &&
      label(e.action) === action?.name &&
      (mode === "Runs" || e["execution-id"] === entry?.executionId),
  );
  const resultEvent = [...actionEvents].reverse().find((e) => e.preview);
  const currentPreview = action
    ? actionEntry?.preview || resultEvent?.preview
    : mode === "Build"
      ? entry?.preview
      : output?.preview;
  const sampleKey = JSON.stringify([
    mode === "Runs" ? run?.id : previewSession?.id,
    task?.name,
    action?.name,
  ]);
  if (currentPreview) {
    retainedSamples.current.delete(sampleKey);
    retainedSamples.current.set(sampleKey, currentPreview);
    if (retainedSamples.current.size > 64)
      retainedSamples.current.delete(
        retainedSamples.current.keys().next().value,
      );
  }
  const preview =
    currentPreview ||
    (["stale", "partial"].includes(entry?.status)
      ? retainedSamples.current.get(sampleKey)
      : null);
  const authoringInputs =
    mode === "Build" && workspace === "action" && !!action && !task.complex;
  const inputKey = authoringInputs
    ? JSON.stringify([
        previewSession.instanceId,
        previewSession.id,
        previewSession.revision,
        previewSession.configurationRevision,
        previewSession.scratchRevision,
        task.name,
        action.index,
      ])
    : null;
  const inputView = availableInputs?.key === inputKey ? availableInputs : null;
  const inputs = action
    ? authoringInputs
      ? inputView?.preview
      : actionEntry?.inputs || resultEvent?.inputs
    : entry?.inputs;
  const status =
    entry?.status === "stale"
      ? "stale"
      : action
        ? actionEntry?.status
        : entry?.status;
  const unavailable =
    mode === "Runs" && !preview
      ? action
        ? "Action samples are available only in the app session that executed this run."
        : output?.reason
      : null;
  const taskStatusKey = (run?.tasks || [])
    .map((t) => t["task/status"])
    .join(",");
  useEffect(() => {
    if (!inputKey) return;
    let live = true;
    api(
      `/sessions/${previewSession.id}/inputs/${encodeURIComponent(task.name)}/${action.index}`,
    )
      .then((value) => {
        if (!live) return;
        if (
          value.instanceId !== previewSession.instanceId ||
          (value.sourceRevision ?? value.revision) !==
            previewSession.revision ||
          value.configurationRevision !==
            previewSession.configurationRevision ||
          value.scratchRevision !== previewSession.scratchRevision
        ) {
          refresh();
          return;
        }
        setAvailableInputs({ ...value, key: inputKey });
      })
      .catch((e) => {
        if (live)
          setAvailableInputs({
            key: inputKey,
            error: e.details || { message: e.message },
          });
      });
    return () => {
      live = false;
    };
  }, [inputKey, connected]);
  useEffect(() => {
    setOutput(null);
    let live = true;
    if (mode === "Runs" && run && task)
      api(`/runs/${run.id}/outputs/${encodeURIComponent(task.name)}`)
        .then((value) => live && setOutput(value))
        .catch((e) => live && setOutput({ reason: e.message }));
    return () => {
      live = false;
    };
  }, [mode, run?.id, task?.name, taskStatusKey]);

  const beginEditing = () => {
    const origin = current.current.saved;
    if (!origin) return current.current.session;
    const s = origin.session;
    accept(s);
    current.current.saved = null;
    setSaved(null);
    initialSession = Promise.resolve(s);
    setGraphViews((previous) => ({
      ...previous,
      [s.id]: previous[`saved-${origin.name}-${origin.version}`],
    }));
    return s;
  };
  const draftTarget = () => {
    const pending = current.current.saved ? null : current.current.draft;
    if (pending?.edn == null)
      return {
        task: selection.task,
        action: selection.action,
        index: action?.index,
      };
    const indexed = sourceIndex(pending.edn);
    if (action) {
      const located = actionRange(
        pending.edn,
        pending.regions,
        task.name,
        action.name,
      );
      const parent =
        located &&
        indexed.tasks.find((t) => located.from >= t.from && located.to <= t.to);
      const child = parent?.actions.find((a) => a.from === located.from);
      return child
        ? { task: parent.name, action: child.action, index: child.index }
        : null;
    }
    const parent = indexed.tasks.find((t) => t.name === selection.task);
    return parent ? { task: parent.name, action: null } : null;
  };
  const flushDraft = () => {
    const operation = async () => {
      const pending = current.current.draft;
      const destination = draftTarget();
      const startingSelection = current.current.selection;
      let active = current.current.session;
      if (current.current.saved) return current.current.saved.session;
      if (!active) return null;
      if (copies.current[active.id]?.deleted)
        throw new Error(
          "This draft was deleted. Recover local text from the switcher before continuing.",
        );
      if (conflict)
        throw new Error("Resolve the draft conflict before continuing.");
      if (pending?.migrationError)
        throw new Error(
          "Resolve the recovered action text in Source before continuing.",
        );
      try {
        if (pending?.edn != null && pending.edn !== active.document.edn) {
          active = await api(`/sessions/${active.id}/source`, {
            edn: pending.edn,
            revision: pending.revision,
          });
          const live = current.current.draft;
          if (live?.id === active.id) {
            current.current.draft = { ...live, revision: active.revision };
            setDraft(current.current.draft);
          }
        }
        const configEdn = pending?.configEdn ?? active.configEdn ?? "{}";
        if (!active.configurationProvided ||
            (pending?.configEdn != null && pending.configEdn !== active.configEdn)) {
          active = await api(`/sessions/${active.id}/configuration`, {
            configEdn,
            configurationRevision:
              pending?.configurationRevision ?? active.configurationRevision,
          });
          const live = current.current.draft;
          if (live?.id === active.id) {
            current.current.draft = {
              ...live,
              configurationRevision: active.configurationRevision,
            };
            setDraft(current.current.draft);
          }
        }
        if (copies.current[active.id]?.deleted)
          throw new Error(
            "This draft was deleted. Recover local text from the switcher before continuing.",
          );
        if (current.current.session?.id !== active.id) {
          const copy = copies.current[active.id];
          if (copy) {
            const acknowledged =
              copy.pending?.edn === pending?.edn &&
              copy.pending?.configEdn === pending?.configEdn;
            copies.current = {
              ...copies.current,
              [active.id]: {
                ...copy,
                pending: acknowledged
                  ? null
                  : copy.pending
                    ? {
                        ...copy.pending,
                        revision: active.revision,
                        configurationRevision: active.configurationRevision,
                      }
                    : null,
              },
            };
            setDraftCopies(Object.values(copies.current));
            localStorage.setItem(copiesKey, sourceJSON(copies.current));
          }
          return active;
        }
        accept(active);
        const live = current.current.draft;
        if (
          pending &&
          live?.id === active.id &&
          live.edn === pending.edn &&
          live.configEdn === pending.configEdn
        ) {
          changeDraft(null);
          if (
            destination &&
            current.current.selection?.task === startingSelection?.task &&
            current.current.selection?.action === startingSelection?.action
          ) {
            editingInPlace.current = true;
            setSelection({
              task: destination.task,
              action: destination.action,
            });
          }
        }
        return active;
      } catch (e) {
        if (e.status === 409 && pending) {
          const latest = await api(`/sessions/${active.id}`);
          setConflict({
            local: current.current.draft || pending,
            current: latest,
          });
        }
        throw e;
      }
    };
    const next = flushQueue.current.catch(() => {}).then(operation);
    flushQueue.current = next;
    return next;
  };
  const applyDraft = flushDraft;
  useEffect(() => {
    if (
      !draft ||
      draft.migrationError ||
      deletedDraft ||
      conflict ||
      saved ||
      mode !== "Build"
    )
      return;
    const timer = setTimeout(
      () =>
        flushDraft().catch((e) =>
          setError(e.details || { message: e.message }),
        ),
      600,
    );
    return () => clearTimeout(timer);
  }, [draft, deletedDraft, conflict, saved?.name, mode]);
  const runCommand = async (s, command) => {
    const job = await waitExecution(`/sessions/${s.id}/execute`, {
      ...command,
      revision: s.revision,
      configurationRevision: s.configurationRevision,
      instanceId: s.instanceId,
      idempotencyKey: crypto.randomUUID(),
    });
    return overview(`/sessions/${s.id}`, command["task-name"]);
  };
  const execute = (scope) =>
    perform(async () => {
      const destination = draftTarget();
      const active = saved ? saved.session : await applyDraft();
      const target = active.document.tasks.find(
        (t) => t.name === destination?.task,
      );
      if (!target || (scope !== "task" && !target.actions[destination.index]))
        throw new Error("This step changed. Select it again before running.");
      const next = await runCommand(active, {
        "task-name": target.name,
        index: destination?.index,
        mode: scope,
        revision: active.revision,
      });
      if (saved && current.current.saved?.session.id === active.id)
        acceptSaved(next);
      else if (!saved && current.current.session?.id === active.id)
        accept(next);
      if (
        (current.current.saved?.session || current.current.session)?.id ===
        active.id
      ) {
        setActionTab((tab) => (tab === "Edit" ? "Result" : tab));
      }
    });
  const edit = (request) =>
    perform(async () => {
      beginEditing();
      const active = await flushDraft();
      const s = await api(`/sessions/${active.id}/source`, {
        ...request,
        revision: active.revision,
      });
      if (current.current.session?.id !== s.id) return s;
      accept(s);
      const pending = current.current.draft;
      if (pending?.id === s.id) {
        if (pending.revision < current.current.session.revision)
          setConflict({ local: pending, current: current.current.session });
        return s;
      }
      if (request.op === "add-task") {
        setSelection({ task: s.document.tasks.at(-1).name, action: null });
        setWorkspace("canvas");
        setPanelTab("Overview");
        setDrawer(true);
      } else if (request.op === "add-action") {
        const actions = s.document.tasks.find(
          (t) => t.name === request.task,
        ).actions;
        focusAddedAction.current = true;
        setSelection({
          task: request.task,
          action: actions[request.index ?? actions.length - 1].name,
        });
        setActionTab("Edit");
        setInspectorTab("Inputs");
        setWorkspace("action");
      } else if (request.op === "remove-task") {
        setSelection({ task: null, action: null });
        setWorkspace("canvas");
      } else if (request.op === "remove-action")
        setSelection({ task: request.task, action: null });
      return s;
    });
  const newSession = async (definition = {}) => {
    const s = await api("/sessions", definition);
    accept(s);
    changeDraft(null);
    initialSession = Promise.resolve(s);
    setMode("Build");
    setSaved(null);
    setRun(null);
    setWorkspace("canvas");
    setSelection({ task: null, action: null });
    setExpanded(new Set());
    return s;
  };
  const selectRun = (id) =>
    perform(async () => {
      const r = await overview(`/runs/${id}`);
      setRun(r);
      setMode("Runs");
      setSaved(null);
      setWorkspace("canvas");
      setSelection({ task: null, action: null });
    });
  const switchMode = (name) => {
    if (name === mode) return;
    if (name === "Build" && mode === "Runs") return openCurrentDraft();
    navigate(() => {
      if (name === "Runs" && !run && catalog.runs.length)
        selectRun(catalog.runs[0]["run/id"]);
      else {
        setMode(name);
        if (session) setSaved(null);
        setWorkspace("canvas");
      }
    });
  };
  const listedVersions = catalog.pipelines
    .filter((p) => label(p["pipeline/name"]) === definition?.name)
    .map((p) => p["pipeline/version"]);
  const latestListedVersion = Math.max(0, ...listedVersions);
  const runVersions = [
    ...new Set([
      ...(definition?.version ? [definition.version] : []),
      ...listedVersions,
    ]),
  ].sort((a, b) => b - a);
  const unpublished = !saved && (dirty || !session?.document.version);
  const runPipeline = () => {
    setError(null);
    setRunChoice(unpublished ? "draft" : String(definition.version));
  };
  const startPipeline = () =>
    perform(async () => {
      let r;
      if (runChoice === "draft") {
        const s = await flushDraft();
        const published = await api(`/sessions/${s.id}/save`, {
          revision: s.revision,
        });
        accept(published);
        setRunChoice(String(published.document.version));
        const job = await waitExecution("/executions", {
          scope: "pipeline",
          configuration: {
            source: "draft",
            id: published.id,
            revision: published.configurationRevision,
          },
          pipeline: published.savedPipeline.name,
          version: published.savedVersion,
          instanceId: published.instanceId,
          idempotencyKey: crypto.randomUUID(),
        });
        r = await overview(`/runs/${job.runId}`);
      } else {
        const currentDraft = await flushDraft();
        const job = await waitExecution("/executions", {
          scope: "pipeline",
          configuration: {
            source: "draft",
            id: currentDraft.id,
            revision: currentDraft.configurationRevision,
          },
          pipeline: definition.name,
          version: Number(runChoice),
          instanceId: currentDraft.instanceId,
          idempotencyKey: crypto.randomUUID(),
        });
        r = await overview(`/runs/${job.runId}`);
      }
      setRun(r);
      setRunChoice(null);
      setSaved(null);
      setMode("Runs");
      setWorkspace("canvas");
      setSelection({ task: null, action: null });
      setCatalog(await api("/catalog"));
    });
  const inspect = (view) =>
    perform(async () => {
      const data = await api("/inspect", {
        [mode === "Build" ? "session-id" : "run-id"]:
          mode === "Build" ? previewSession.id : run.id,
        "task-name": task.name,
        "action-name": action?.name,
        view,
      });
      setPortalUrl(data.url);
      window.open(data.url, "_blank", "noopener,noreferrer");
    });
  const save = () =>
    perform(async () => {
      const s = await flushDraft();
      const published = await api(`/sessions/${s.id}/save`, {
        revision: s.revision,
      });
      if (current.current.saved?.session.id === s.id) acceptSaved(published);
      else accept(published);
      setCatalog(await api("/catalog"));
    });
  const openRevision = (pipeline, keepWorkspace = false) =>
    navigate(() =>
      perform(async () => {
        const preview = await openSavedPreview(label(pipeline["pipeline/name"]), pipeline["pipeline/version"]);
        setSaved({ ...preview.document, session: preview });
        setMode("Build");
        if (!keepWorkspace) {
          setWorkspace("canvas");
          setSelection({ task: null, action: null });
        }
      }),
    );
  const openCurrentDraft = () =>
    navigate(() => {
      if (!current.current.session && current.current.run)
        return openRunVersion();
      setMode("Build");
      if (current.current.session) setSaved(null);
      const view = copies.current[current.current.session?.id]?.view;
      setWorkspace(view?.workspace || "canvas");
      setSelection(view?.selection || { task: null, action: null });
    });
  const openStoredDraft = (copy) =>
    navigate(() =>
      perform(async () => {
        const pendingAtStart = copies.current[copy.id]?.pending;
        let s;
        if (copy.deleted) {
          s = await recoverSession(copy.id, copy.pending, copy.edn ?? (copy.origin ? undefined : blank), {
            configEdn: copy.configEdn,
            origin: copy.origin,
          });
          const pending = copies.current[copy.id]?.pending;
          if (pending && pending !== pendingAtStart)
            s.recoveredPending = migratePending(pending, {
              document: { edn: copy.edn ?? s.document.edn ?? blank },
              configEdn: s.configEdn,
            });
        } else
          try {
            s = await readDraftOverview(copy.id, copy.pending);
          } catch (e) {
            if (e.status !== 404) throw e;
            markMissingDraft(copy.id);
            throw new Error(
              "This draft was deleted. Choose another pipeline or recover local text from the switcher.",
            );
          }
        accept(s);
        initialSession = Promise.resolve(s);
        setMode("Build");
        setSaved(null);
        setWorkspace(copy.deleted ? "source" : "canvas");
        setSelection({ task: null, action: null });
        if (s.recoveredPending?.migrationError)
          setError({
            message:
              "An older action draft could not be placed safely. Copy its text from these details before editing.",
            details: s.recoveredPending.unplacedAction,
          });
      }),
    );
  const libraryDeleted = async (target) => {
    const active = current.current;
    if (target.kind === "draft") {
      const next = { ...copies.current };
      delete next[target.id];
      copies.current = next;
      setDraftCopies(Object.values(next));
    }
    const deletedDraft =
      target.kind === "draft" && active.session?.id === target.id;
    const selected =
      active.mode === "Runs" ? active.run?.document : active.saved;
    const deletedSaved =
      target.kind !== "draft" &&
      selected?.name === target.name &&
      (target.kind === "pipeline" || selected.version === target.version);
    if (deletedDraft) {
      changeDraft(null);
      setSession(null);
      current.current.session = null;
    }
    if (
      deletedSaved ||
      (deletedDraft && !active.saved && active.mode === "Build")
    ) {
      setSaved(null);
      setRun(null);
      setMode("Build");
      setWorkspace("canvas");
      setSelection({ task: null, action: null });
      if (deletedDraft || !active.session) {
        accept(
          await api("/sessions", {
            edn: blank,
            configEdn: "{}",
            preview: true,
          }),
        );
      }
    }
    let recoveryWarning = null;
    if (target.kind === "draft") {
      try {
        if (cached(pendingKey)?.id === target.id)
          localStorage.removeItem(pendingKey);
        if (localStorage.getItem("collet.session") === target.id)
          localStorage.removeItem("collet.session");
        localStorage.setItem(copiesKey, sourceJSON(copies.current));
      } catch {
        setStorageFailed(true);
        recoveryWarning =
          "Deletion succeeded, but browser recovery storage could not be updated. Keep this tab open until storage is available.";
      }
    }
    setCatalog(await api("/catalog"));
    setError(null);
    return recoveryWarning;
  };
  const newBlankPipeline = () =>
    navigate(() => perform(() => newSession({ edn: blank, configEdn: "{}" })));
  const resetResults = () =>
    navigate(() =>
      perform(async () => {
        const next = await api(`/sessions/${previewSession.id}/reset`, {});
        if (saved) acceptSaved(next);
        else accept(next);
      }),
    );
  const ordersExample = () => navigate(() => perform(() => newSession()));
  const openRunVersion = () =>
    openRevision(
      {
        "pipeline/name": definition.name,
        "pipeline/version": definition.version,
      },
      true,
    );
  const expand = useCallback(
    (name) =>
      setExpanded((previous) => {
        const next = new Set(previous);
        next.has(name) ? next.delete(name) : next.add(name);
        return next;
      }),
    [],
  );
  const rememberGraph = useCallback(
    (key, view) => setGraphViews((previous) => ({ ...previous, [key]: view })),
    [],
  );
  const graphKey =
    mode === "Runs"
      ? `run-${run?.id}`
      : saved
        ? `saved-${saved.name}-${saved.version}`
        : session?.id;
  const runStatus = label(run?.run?.["run/status"]);
  const runError = run?.run?.["run/error"];
  const taskShowsRunError =
    entry?.error?.message && entry.error.message === runError?.message;
  const activeRun =
    run?.owned && ["running", "paused", "pending"].includes(runStatus);
  const configSource =
    mode === "Runs"
      ? run?.configEdn
      : !saved && draft?.kind === "source"
        ? draft.configEdn ?? previewSession?.configEdn
        : previewSession?.configEdn;
  const draftSource = (mode === "Build" && !saved ? draft?.edn : undefined) ?? definition?.edn;
  const rawSource = draftSource ?? "";
  const range =
    action &&
    actionRange(rawSource, !saved && draft?.regions, task.name, action.name);
  const updateSource = (value, configuration, update) => {
    if (current.current.saved) editingInPlace.current = true;
    const active = beginEditing(),
      pending = current.current.draft,
      previous = pending?.edn ?? active.document.edn;
    const next = {
      kind: "source",
      id: active.id,
      revision: pending?.revision ?? active.revision,
      configurationRevision:
        pending?.configurationRevision ?? active.configurationRevision,
      edn: configuration ? previous : value,
      configEdn: configuration
        ? value
        : (pending?.configEdn ?? active.configEdn),
      regions: configuration
        ? pending?.regions
        : update
          ? mapRegions(previous, pending?.regions, update.changes, value)
          : sourceIndex(value).actions,
    };
    changeDraft(
      next.edn === active.document.edn && next.configEdn === active.configEdn
        ? null
        : next,
    );
  };
  const updateAction = (value) => {
    if (!range) return;
    if (current.current.saved) editingInPlace.current = true;
    const active = beginEditing(),
      pending = current.current.draft,
      previous = pending?.edn ?? active.document.edn;
    const next = {
      kind: "source",
      id: active.id,
      revision: pending?.revision ?? active.revision,
      configurationRevision:
        pending?.configurationRevision ?? active.configurationRevision,
      ...replaceAction(previous, pending?.regions, range, value),
      configEdn: pending?.configEdn ?? active.configEdn,
    };
    changeDraft(
      next.edn === active.document.edn && next.configEdn === active.configEdn
        ? null
        : next,
    );
  };
  const errorNotice = (
    <ErrorNotice
      error={
        error ||
        (mode === "Build" && (runChoice === null || runChoice === "draft")
          ? validation
          : null)
      }
      onDismiss={() => {
        setError(null);
        setValidation(null);
      }}
    />
  );
  const errorView = runChoice === null ? errorNotice : null;
  const inspectorProps = {
    task,
    action,
    preview,
    inputs,
    status: !currentPreview && preview ? "stale" : status,
    events,
    diagnosticsRevision,
    diagnosticsPath:
      mode === "Runs" && run?.id
        ? `/runs/${run.id}/diagnostics`
        : entry?.executionId
          ? `/executions/${entry.executionId}`
          : null,
    error: entry?.error,
    inspect,
    unavailable,
    disabled: busy,
  };
  const actionMenu = (a) => (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger>
        <IconButton
          variant="ghost"
          color="gray"
          aria-label={`Options for ${a.name}`}
          disabled={busy}
        >
          <MoreHorizontal size={18} />
        </IconButton>
      </DropdownMenu.Trigger>
      <DropdownMenu.Content>
        <DropdownMenu.Item
          disabled={a.index === 0}
          onSelect={() =>
            edit({
              op: "move-action",
              task: task.name,
              index: a.index,
              direction: "up",
            })
          }
        >
          Move up
        </DropdownMenu.Item>
        <DropdownMenu.Item
          disabled={a.index === task.actions.length - 1}
          onSelect={() =>
            edit({
              op: "move-action",
              task: task.name,
              index: a.index,
              direction: "down",
            })
          }
        >
          Move down
        </DropdownMenu.Item>
        <DropdownMenu.Separator />
        <DropdownMenu.Item
          color="red"
          onSelect={() =>
            edit({ op: "remove-action", task: task.name, index: a.index })
          }
        >
          Delete action
        </DropdownMenu.Item>
      </DropdownMenu.Content>
    </DropdownMenu.Root>
  );

  const localIndex = useMemo(() => sourceIndex(rawSource), [rawSource]);
  const sourceFocus = useRef(null);
  useEffect(() => {
    if (draftSource == null || workspace !== "source" || !sourceFocus.current) return;
    const item = localIndex.tasks.find((t) => t.name === sourceFocus.current);
    sourceFocus.current = null;
    setSourceCursor(item?.from ?? 0);
  }, [draftSource, workspace, localIndex]);
  const closeDetail = () => {
    setSelection({ task: null, action: null });
    setPanelTab("Overview");
    setDrawer(false);
    setInspectorOpen(false);
  };
  const sourceAtCursor = (position) => {
    const item = localIndex.tasks.find(
      (t) => position >= t.from && position <= t.to,
    );
    if (
      item &&
      definition?.tasks.some((t) => t.name === item.name) &&
      (selection.task !== item.name || selection.action)
    ) {
      editingInPlace.current = true;
      setSelection({ task: item.name, action: null });
    }
  };
  const focusTaskSource = () => {
    sourceFocus.current = task?.name;
    openSource();
  };
  const controlRun = (verb) =>
    perform(async () => setRun(await api(`/runs/${run.id}/${verb}`, {})));
  const previousSample =
    (dirty || !previewSession?.currentResults || !currentPreview) && preview;
  const inspector = (
    <Inspector
      {...inspectorProps}
      tab={inspectorTab}
      onTabChange={setInspectorTab}
      inputsStatus={authoringInputs ? undefined : status}
      previous={previousSample}
      notices={inputView?.notices}
      inputsError={inputView?.error}
    />
  );
  const applyControl = editable && (
    <Button
      variant="surface"
      color="gray"
      disabled={busy || !connected}
      onClick={() =>
        perform(async () => {
          const s = await flushDraft();
          const v = await api("/validate", { edn: s.document.edn });
          setValidation(v.valid ? null : v);
          setCheckedSource({ edn: s.document.edn, valid: v.valid });
        })
      }
    >
      Check
    </Button>
  );
  const fixtureButton = editable && !saved && task && (
    <Button variant="surface" color="gray" disabled={busy || !connected} onClick={() => setFixtureOpen(true)}>
      Fixtures…
    </Button>
  );
  const runStep = (scope, primary = true) => (
    <Tooltip
      content={
        scope === "task"
          ? "Run this task only. Upstream tasks must already have results."
          : "Run only this action using valid earlier results."
      }
    >
      <Button
        variant={primary ? "solid" : "surface"}
        disabled={busy || !connected}
        onClick={() => execute(scope)}
      >
        {busy ? (
          <LoaderCircle className="spin" size={13} />
        ) : (
          <Play size={13} />
        )}
        {`Run ${scope}`}
      </Button>
    </Tooltip>
  );
  const taskPane = () => (
    <>
      <div className="detail-title">
        <span>
          TASK
          {workspace === "source"
            ? " · AT CURSOR"
            : mode === "Runs"
              ? " IN RUN"
              : ""}
        </span>
        <IconButton
          variant="ghost"
          color="gray"
          aria-label="Close inspector"
          onClick={closeDetail}
        >
          <X size={14} />
        </IconButton>
      </div>
      <div className="detail-heading task-detail-heading">
        <div>
          <h1 ref={heading} tabIndex={-1}>
            {title(task.name)}
          </h1>
          <Status status={entry?.status} />
        </div>
        {editable && runStep("task")}
        {fixtureButton}
      </div>
      {panelTab === "Overview" && <ErrorNotice error={entry?.error} />}
      <Tabs.Root
        value={panelTab}
        onValueChange={setPanelTab}
        className="detail-tabs"
      >
        <Tabs.List>
          {["Overview", "Output", "Inputs", "Logs", "Metrics", "Settings"].map(
            (tab) => (
              <Tabs.Trigger key={tab} value={tab}>
                {tab}
                {tab === "Output" && entry?.preview?.count != null && (
                  <small>{entry.preview.count}</small>
                )}
              </Tabs.Trigger>
            ),
          )}
        </Tabs.List>
        <Tabs.Content value={panelTab} className="detail-content">
          {panelTab === "Overview" ? (
            <>
              <div className="section-heading">
                <h2>Actions · {task.actions.length}</h2>
                <span className="muted">run in order</span>
              </div>
              <div className="task-actions">
                {task.actions.map((a) => (
                  <div className="task-action" key={a.name}>
                    <button
                      className="task-action-link"
                      onClick={() => openTask(task.name, a.name)}
                    >
                      <span className="step-number">{a.index + 1}</span>
                      <span className="action-caption">
                        <strong>{a.name}</strong>
                        <small>
                          {localIndex.actions[
                            JSON.stringify([task.name, a.name])
                          ]?.call ||
                            a.type ||
                            "custom action"}
                        </small>
                      </span>
                      <Status
                        compact
                        status={
                          entry?.status === "stale"
                            ? "stale"
                            : entry?.actions?.[a.name]?.status
                        }
                      />
                      <ChevronRight size={13} />
                    </button>
                    {editable && actionMenu(a)}
                  </div>
                ))}
                {editable && (
                  <Button
                    className="add-action"
                    variant="ghost"
                    color="gray"
                    disabled={busy || !connected}
                    onClick={() => setActionTarget({ task: task.name })}
                  >
                    <Plus size={13} />
                    Add action
                    <kbd>A</kbd>
                  </Button>
                )}
              </div>
              <div className="dependency-columns">
                <section>
                  <h2>Reads from</h2>
                  <div className="dependency-chips">
                    {task.inputs.map((name) => (
                      <button key={name} onClick={() => openTask(name)}>
                        <Status compact status={states[name]?.status} />
                        {title(name)}
                      </button>
                    ))}
                    {!task.inputs.length && (
                      <span className="muted">No upstream tasks</span>
                    )}
                  </div>
                </section>
                <section>
                  <h2>Feeds</h2>
                  <div className="dependency-chips">
                    {definition.tasks
                      .filter((t) => t.inputs.includes(task.name))
                      .map((t) => (
                        <button key={t.name} onClick={() => openTask(t.name)}>
                          <Status compact status={states[t.name]?.status} />
                          {title(t.name)}
                        </button>
                      ))}
                  </div>
                </section>
              </div>
              <div className="section-heading">
                <h2>
                  Output
                  {preview?.count != null ? ` · ${preview.count} rows` : ""}
                </h2>
                <Button
                  variant="ghost"
                  color="gray"
                  onClick={() => setPanelTab("Output")}
                >
                  Open full <ChevronRight size={12} />
                </Button>
              </div>
              <div className="inline-output">
                <DataPanel
                  data={preview}
                  status={status}
                  view="Result"
                  base={`:inputs :${task.name}`}
                  inspect={inspect}
                  unavailable={unavailable}
                  disabled={busy}
                />
              </div>
            </>
          ) : panelTab === "Settings" ? (
            <>
              <p className="muted">
                Task settings and return expressions are edited in the pipeline
                EDN.
              </p>
              <Button variant="surface" color="gray" onClick={focusTaskSource}>
                <FileCode2 size={14} />
                {editable ? "Edit task settings in source" : "View task source"}
              </Button>
              {task.complex && (
                <Callout.Root>
                  <Callout.Text>
                    Iterator and parallel actions use whole-task previews.
                  </Callout.Text>
                </Callout.Root>
              )}
              <h2>Dependencies</h2>
              <p className="muted">
                Run upstream tasks explicitly before this task.
              </p>
              {task.inputs.map((name) => (
                <div className="dependency" key={name}>
                  <button
                    className="inline-link"
                    onClick={() => openTask(name)}
                  >
                    {title(name)}
                  </button>
                  <Status status={states[name]?.status} />
                  {editable && (
                    <Button
                      variant="ghost"
                      color="gray"
                      disabled={busy}
                      onClick={() =>
                        edit({
                          op: "disconnect",
                          source: name,
                          target: task.name,
                        })
                      }
                    >
                      Disconnect
                    </Button>
                  )}
                </div>
              ))}
              {editable && (
                <Select.Root
                  value=""
                  onValueChange={(value) =>
                    edit({ op: "connect", source: value, target: task.name })
                  }
                  disabled={busy || !connected}
                >
                  <Select.Trigger
                    placeholder={"Add dependency…"}
                    aria-label="Add dependency"
                  />
                  <Select.Content>
                    {definition.tasks
                      .filter(
                        (t) =>
                          t.name !== task.name && !task.inputs.includes(t.name),
                      )
                      .map((t) => (
                        <Select.Item key={t.name} value={t.name}>
                          {title(t.name)}
                        </Select.Item>
                      ))}
                  </Select.Content>
                </Select.Root>
              )}
              {editable && (
                <DropdownMenu.Root>
                  <DropdownMenu.Trigger>
                    <Button variant="ghost" color="gray">
                      Task options
                    </Button>
                  </DropdownMenu.Trigger>
                  <DropdownMenu.Content>
                    <DropdownMenu.Item
                      color="red"
                      disabled={busy}
                      onSelect={() =>
                        edit({ op: "remove-task", task: task.name })
                      }
                    >
                      Delete task
                    </DropdownMenu.Item>
                  </DropdownMenu.Content>
                </DropdownMenu.Root>
              )}
            </>
          ) : (
            <Inspector
              {...inspectorProps}
              withTabs={false}
              tab={panelTab === "Output" ? "Result" : panelTab}
              onTabChange={(value) =>
                setPanelTab(value === "Result" ? "Output" : value)
              }
              previous={previousSample}
            />
          )}
        </Tabs.Content>
      </Tabs.Root>
    </>
  );
  const pipelinePane = () => (
    <>
      <div className="detail-title">
        <span>{mode === "Runs" ? "RUN" : "PIPELINE"}</span>
        <IconButton
          variant="ghost"
          color="gray"
          aria-label="Close inspector"
          onClick={() => {
            setDrawer(false);
            setInspectorOpen(false);
          }}
        >
          <X size={14} />
        </IconButton>
      </div>
      <div className="detail-heading">
        <div>
          <h1 ref={heading} tabIndex={-1}>
            {title(definition?.name || "Pipeline")}
          </h1>
          <span className="muted">
            {mode === "Runs"
              ? run?.id.slice(0, 8)
              : "Build and inspect one step at a time."}
          </span>
        </div>
        {mode === "Runs" && <Status status={runStatus} />}
      </div>
      <Tabs.Root
        value={panelTab}
        onValueChange={setPanelTab}
        className="detail-tabs"
      >
        <Tabs.List>
          {[
            "Overview",
            "Config",
            ...(mode === "Build" ? ["Versions"] : []),
            "Logs",
            "Metrics",
          ].map((tab) => (
            <Tabs.Trigger key={tab} value={tab}>
              {tab}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value={panelTab} className="detail-content">
          {panelTab === "Overview" ? (
            <>
              <div className="pipeline-stats">
                <div>
                  <span>Tasks</span>
                  <strong>{definition?.tasks.length || 0}</strong>
                </div>
                <div>
                  <span>Actions</span>
                  <strong>
                    {definition?.tasks.reduce(
                      (n, t) => n + t.actions.length,
                      0,
                    ) || 0}
                  </strong>
                </div>
                <div>
                  <span>Version</span>
                  <strong>
                    {definition?.version ? `v${definition.version}` : "Draft"}
                  </strong>
                </div>
              </div>
              <div className="section-heading">
                <h2>
                  {mode === "Runs" ? "Execution" : "Pipeline configuration"}
                </h2>
                <Button
                  variant="ghost"
                  color="gray"
                  onClick={() => setPanelTab("Config")}
                >
                  Open <ChevronRight size={12} />
                </Button>
              </div>
              {mode === "Runs" ? (
                <>
                  <Status status={runStatus} />
                  <p className="muted">
                    Select a task in the graph to inspect this execution’s
                    output and events.
                  </p>
                </>
              ) : (
                <p className="muted">
                  {previewSession?.configuration?.length || 0} configuration entries
                </p>
              )}
              {mode === "Build" && (
                <>
                  <div className="section-heading">
                    <h2>Versions</h2>
                    <Button
                      variant="ghost"
                      color="gray"
                      onClick={() => setPanelTab("Versions")}
                    >
                      View all <ChevronRight size={12} />
                    </Button>
                  </div>
                  {versionList()}
                </>
              )}
              <div className="pipeline-validation">
                {dirty
                  ? "Saving draft text…"
                  : checkedSource?.edn === rawSource && checkedSource.valid
                    ? "Draft autosaved · Validation passed"
                    : "Draft autosaved · Check validates without execution"}
              </div>
              {applyControl}
            </>
          ) : panelTab === "Config" ? (
            <>
              <p className="muted">
                {mode === "Runs"
                  ? "Configuration captured for this run."
                  : "Runtime configuration · kept in application memory until restart."}
              </p>
              <div className="panel-config-editor">
                <EdnEditor
                  name="Configuration EDN editor"
                  documentId={previewSession?.id ?? run?.id}
                  value={configSource || "; Configuration unavailable"}
                  readOnly={!editable || configSource == null}
                  onChange={(value, update) =>
                    updateSource(value, true, update)
                  }
                />
              </div>
              {applyControl}
            </>
          ) : panelTab === "Versions" ? (
            versionList()
          ) : (
            <Inspector
              {...inspectorProps}
              task={null}
              action={null}
              tab={panelTab}
              withTabs={false}
              onTabChange={setPanelTab}
            />
          )}
        </Tabs.Content>
      </Tabs.Root>
    </>
  );
  const versionList = () => (
    <div className="version-list">
      {runVersions.length ? (
        runVersions.map((version) => (
          <button
            key={version}
            aria-label={`Open saved revision ${version}`}
            onClick={() =>
              openRevision({
                "pipeline/name": definition.name,
                "pipeline/version": version,
              })
            }
          >
            <strong>v{version}</strong>
            <span className="muted">Saved revision</span>
            <span>{definition.version === version ? "current" : "Open"}</span>
          </button>
        ))
      ) : (
        <p className="muted">Save to publish your first version.</p>
      )}
    </div>
  );

  const restoreRoute = async (initial) => {
    if (!location.hash) return;
    const params = new URLSearchParams(location.hash.slice(1));
    routing.current = true;
    lastHash.current = location.hash;
    try {
      if (params.get("run")) {
        const snapshot = await overview(
          `/runs/${encodeURIComponent(params.get("run"))}`, params.get("task"),
        );
        setRun(snapshot);
        setMode("Runs");
        setSaved(null);
      } else if (params.get("pipeline") && params.get("version")) {
        const context = await openSavedPreview(params.get("pipeline"), params.get("version"));
        setSaved({ ...context.document, session: context });
        setMode("Build");
      } else {
        const id = params.get("draft");
        const copy = copies.current[id];
        if (copy?.deleted)
          throw new Error(
            "This draft was deleted. Choose another pipeline or recover local text from the switcher.",
          );
        if (
          id &&
          id !== (initial || current.current.session)?.id &&
          id !== initial?.replaced
        ) {
          let context;
          try {
            context = await readDraftOverview(id, copy?.pending, params.get("task"));
          } catch (e) {
            if (e.status === 404) markMissingDraft(id);
            throw e;
          }
          accept(context);
        }
        setMode("Build");
        setSaved(null);
      }
      setSourceTab(
        params.get("tab") === "configuration" ? "Configuration" : "Definition",
      );
      setWorkspace(
        ["canvas", "action", "source"].includes(params.get("view"))
          ? params.get("view")
          : "canvas",
      );
      setSelection({ task: params.get("task"), action: params.get("action") });
      setDrawer(!!params.get("task"));
      setPanelTab("Overview");
    } catch (e) {
      setError(e.details || { message: e.message });
    } finally {
      routing.current = false;
    }
  };
  useEffect(() => {
    const back = () => {
      if (location.hash !== lastHash.current) restoreRoute();
    };
    window.addEventListener("popstate", back);
    return () => window.removeEventListener("popstate", back);
  }, []);
  useEffect(() => {
    if ((!session && !saved && !run) || !routeReady || routing.current) return;
    const params = new URLSearchParams({ view: workspace });
    if (workspace === "source" && sourceTab === "Configuration")
      params.set("tab", "configuration");
    if (mode === "Runs" && run) params.set("run", run.id);
    else if (saved) {
      params.set("pipeline", saved.name);
      params.set("version", String(saved.version));
    } else params.set("draft", session.id);
    if (selection.task) params.set("task", selection.task);
    if (selection.action) params.set("action", selection.action);
    const hash = `#${params}`;
    if (hash !== location.hash) {
      const previousView = new URLSearchParams(lastHash.current.slice(1)).get(
        "view",
      );
      if (
        !lastHash.current ||
        (workspace === "source" && previousView === "source")
      )
        history.replaceState(null, "", hash);
      else history.pushState(null, "", hash);
      lastHash.current = hash;
    }
    if (mode === "Build" && !saved && copies.current[session.id]) {
      copies.current[session.id].view = { workspace, selection };
      try {
        localStorage.setItem(copiesKey, sourceJSON(copies.current));
      } catch {
        setStorageFailed(true);
      }
    }
  }, [
    workspace,
    sourceTab,
    selection.task,
    selection.action,
    mode,
    run?.id,
    saved?.name,
    saved?.version,
    session?.id,
    routeReady,
  ]);
  const up = () => {
    if (workspace === "action") {
      setWorkspace("canvas");
      setSelection({ task: task?.name || null, action: null });
      setDrawer(true);
    } else goCanvas();
  };
  useEffect(() => {
    const keyboard = (event) => {
      const command = event.metaKey || event.ctrlKey;
      if (command && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPalette("commands");
        return;
      }
      if (document.querySelector('[role="dialog"]:not([data-state="closed"])'))
        return;
      if (command && event.key.toLowerCase() === "s" && editable) {
        event.preventDefault();
        if (!busy && connected) save();
        return;
      }
      if (command && event.key === "Enter" && editable) {
        event.preventDefault();
        if (!busy && connected)
          task
            ? execute(action && !task.complex ? "action" : "task")
            : runPipeline();
        return;
      }
      if (command && event.key.toLowerCase() === "e") {
        event.preventDefault();
        workspace === "source" ? goCanvas() : openSource();
        return;
      }
      if (
        event.altKey &&
        ["ArrowUp", "ArrowDown"].includes(event.key) &&
        action
      ) {
        event.preventDefault();
        const next =
          task.actions[action.index + (event.key === "ArrowUp" ? -1 : 1)];
        if (next) openTask(task.name, next.name);
        return;
      }
      const typing = event.target.closest?.(
        'input,textarea,[contenteditable="true"],.cm-editor',
      );
      if (event.key === "Escape" && !event.target.closest?.(".cm-tooltip")) {
        event.preventDefault();
        up();
        return;
      }
      if (typing || command || event.altKey || !editable) return;
      if (event.key.toLowerCase() === "n" && !busy) edit({ op: "add-task" });
      if (event.key.toLowerCase() === "a" && task && !busy)
        edit({ op: "add-action", task: task.name });
      if (event.key.toLowerCase() === "r" && !busy && connected)
        task
          ? execute(action && !task.complex ? "action" : "task")
          : runPipeline();
    };
    window.addEventListener("keydown", keyboard);
    return () => window.removeEventListener("keydown", keyboard);
  });
  const latest = new Map();
  catalog.pipelines.forEach((p) => {
    const name = label(p["pipeline/name"]);
    if (
      !latest.has(name) ||
      latest.get(name)["pipeline/version"] < p["pipeline/version"]
    )
      latest.set(name, p);
  });
  const pipelineCommands = [
    ...(session && !copies.current[session.id]?.deleted
      ? [
          {
            label: `Open current draft ${title(session.document.name)}`,
            detail: draft ? "Unacknowledged text" : "Working copy",
            group: "Working drafts",
            run: openCurrentDraft,
          },
        ]
      : []),
    ...[
      ...draftCopies,
      ...(catalog.drafts || [])
        .filter((d) => !draftCopies.some((copy) => copy.id === d.id))
        .map((d) => ({ ...d, name: d.name || `Shared ${d.id.slice(0, 8)}` })),
    ]
      .sort((a, b) => Number(!!a.deleted) - Number(!!b.deleted))
      .filter((copy) =>
        copy.deleted ? hasRecovery(copy) : copy.id !== session?.id,
      )
      .map((copy) => ({
        label: `${copy.deleted ? "Recover text" : "Open draft"} ${title(copy.name)}`,
        detail: copy.deleted
          ? "Local text from a deleted draft"
          : copy.pending
            ? "Unacknowledged text"
            : copy.baseVersion
              ? `Draft from v${copy.baseVersion}`
              : "Working copy",
        group: copy.deleted ? "Recovery" : "Working drafts",
        run: () => openStoredDraft(copy),
      })),
    ...[...latest.values()].map((p) => ({
      label: `Open ${title(label(p["pipeline/name"]))}, saved revision ${p["pipeline/version"]}`,
      detail: `v${p["pipeline/version"]}`,
      group: "Saved pipelines",
      run: () => openRevision(p),
    })),
    { label: "New pipeline", group: "Create", run: newBlankPipeline },
    { label: "Orders example", group: "Create", run: ordersExample },
    {
      label: "Manage pipelines and drafts",
      group: "Manage",
      run: () => setManageOpen(true),
    },
    {
      label: "Reset preview results",
      group: "Working draft",
      disabled: busy || !editable,
      run: resetResults,
    },
  ];
  const commands = [
    ...(editable
      ? [
          {
            label: "Save version",
            detail: "Publish a version",
            run: save,
            disabled: busy || !connected || !!saved,
          },
          {
            label: "Run pipeline",
            detail: "Choose a saved version or Save & run",
            run: runPipeline,
            disabled: busy || !connected,
          },
          ...(task
            ? [
                {
                  label: `Run task ${title(task.name)}`,
                  detail: "This task only",
                  run: () => execute("task"),
                  disabled: busy || !connected,
                },
                { label: "Run with fixtures", run: () => setFixtureOpen(true), disabled: busy || !connected || !!saved },
                ...(action && !task.complex
                  ? [
                      {
                        label: `Run action ${action.name}`,
                        detail: "This action only",
                        run: () => execute("action"),
                        disabled: busy || !connected,
                      },
                      {
                        label: `Run to here ${action.name}`,
                        detail: "Setup and actions 1 through this action",
                        run: () => execute("prefix"),
                        disabled: busy || !connected,
                      },
                    ]
                  : []),
              ]
            : []),
        ]
      : []),
    { label: "Pipeline canvas", run: goCanvas },
    { label: "Pipeline source", run: openSource },
    ...(definition?.tasks || []).flatMap((t) => [
      { label: `Go to task ${title(t.name)}`, run: () => openTask(t.name) },
      ...t.actions.map((a) => ({
        label: `Go to action ${t.name} / ${a.name}`,
        run: () => openTask(t.name, a.name),
      })),
    ]),
    ...pipelineCommands,
  ];
  const graph = definition && (
    <Graph
      key={graphKey}
      document={definition}
      states={states}
      selection={selection}
      onSelect={openTask}
      onOpen={openTask}
      expanded={expanded}
      onExpand={expand}
      edit={edit}
      build={editable}
      disabled={busy || dirty || !connected}
      view={graphViews[graphKey]}
      onViewChange={(view) => rememberGraph(graphKey, view)}
    />
  );

  if (!routeReady && !error)
    return (
      <main aria-busy="true" role="status">
        Opening Workbench…
      </main>
    );
  return (
    <div className="workbench">
      <LibraryManager
        open={manageOpen}
        onClose={() => setManageOpen(false)}
        onDeleted={libraryDeleted}
        commandBusy={busy}
        runCommand={(command) => perform(command, { propagate: true })}
      />
      <header className="topbar">
        <button
          className="brand"
          aria-label="Collet Workbench"
          onClick={goCanvas}
        >
          <Braces size={20} />
        </button>
        <div className="pipeline-identity">
          <span className="brand-name">Collet /</span>
          <button
            className="pipeline-switch"
            aria-label="Switch pipeline"
            onClick={() => setPalette("pipelines")}
          >
            {title(definition?.name || "Workbench")}
            <ChevronDown size={12} />
          </button>
          <Badge
            color={
              dirty || (!saved && !session?.document.version && editable)
                ? "amber"
                : "gray"
            }
            variant="soft"
          >
            {dirty
              ? "Draft · saving…"
              : definition?.version
                ? `v${definition.version} · ${mode === "Runs" ? "run" : "saved"}`
                : "Draft autosaved"}
          </Badge>
        </div>
        <nav aria-label="Workbench mode">
          {["Build", "Runs"].map((name) => (
            <Button
              key={name}
              variant={mode === name ? "soft" : "ghost"}
              color="gray"
              aria-current={mode === name ? "page" : undefined}
              onClick={() => switchMode(name)}
              disabled={!session && !saved && !run}
            >
              {name}
            </Button>
          ))}
        </nav>
        <span
          className={`connection ${connected ? "online" : ""}`}
          role="status"
          aria-label={connected ? "Connected" : "Reconnecting"}
        >
          <span />
          {connected ? "Engine" : "Offline"}
        </span>
        <Button
          variant="ghost"
          color="gray"
          onClick={() => setAgentHelpOpen(true)}
        >
          Connect agents
        </Button>
        <Button
          variant="ghost"
          color="gray"
          onClick={() => setManageOpen(true)}
        >
          Manage
        </Button>
        <button
          className="command-trigger"
          onClick={() => setPalette("commands")}
          aria-label="Search or run a command"
        >
          <Search size={13} />
          <span>Search or run a command</span>
          <kbd>⌘K</kbd>
        </button>
        {editable && (
          <>
            <Button
              variant="surface"
              color="gray"
              onClick={save}
              aria-label="Save version"
              disabled={busy || !!saved || !session || !connected}
            >
              <Save size={13} />
              <span className="save-label">Save version</span>
            </Button>
            <Button
              variant="surface"
              color="gray"
              onClick={() => setHistoryOpen(true)}
              disabled={!session || !!saved}
            >
              History
            </Button>
            <small className="muted">
              r{previewSession?.revision} ·{" "}
              {previewSession?.lastChange?.["draft.edit/author"] || "browser"}
            </small>
            <Button
              className="pipeline-run"
              variant={workspace === "action" || task ? "surface" : "solid"}
              onClick={runPipeline}
              disabled={busy || !previewSession || !connected}
            >
              <Play size={13} />
              <span>Run pipeline</span>
              <ChevronDown size={12} />
            </Button>
          </>
        )}
        {mode === "Runs" && activeRun && (
          <>
            <Button
              variant="surface"
              disabled={busy || runStatus === "pending"}
              onClick={() =>
                controlRun(runStatus === "paused" ? "resume" : "pause")
              }
            >
              {runStatus === "paused" ? (
                <Play size={13} />
              ) : (
                <Pause size={13} />
              )}{" "}
              {runStatus === "paused" ? "Resume" : "Pause"}
            </Button>
            <Button
              variant="soft"
              color="red"
              disabled={busy}
              onClick={() => controlRun("stop")}
            >
              <Square size={12} />
              Stop
            </Button>
          </>
        )}
      </header>
      {!session && !saved && !run ? (
        <main className="loading">
          <LoaderCircle className="spin" />
          <p>Opening your workbench…</p>
          {errorView}
        </main>
      ) : (
        <main
          className={`workspace workspace-${workspace} ${mode === "Runs" ? "runs-workspace" : ""}`}
        >
          {mode === "Runs" && (
            <>
              <Button
                className="run-list-toggle"
                variant="surface"
                color="gray"
                onClick={() => setRunsOpen(!runsOpen)}
              >
                <List size={14} />
                Runs
              </Button>
              <aside
                className={`run-list ${runsOpen ? "drawer-open" : ""}`}
                aria-label="Run list"
              >
                <div className="run-list-heading">
                  <h2>Runs</h2>
                  <IconButton
                    className="drawer-close"
                    variant="ghost"
                    color="gray"
                    aria-label="Close run list"
                    onClick={() => setRunsOpen(false)}
                  >
                    <X size={14} />
                  </IconButton>
                </div>
                <div className="run-filters">
                  {["All", "Failed"].map((filter) => (
                    <Button
                      key={filter}
                      variant={runFilter === filter ? "soft" : "ghost"}
                      color="gray"
                      onClick={() => setRunFilter(filter)}
                    >
                      {filter}
                    </Button>
                  ))}
                </div>
                <div className="run-list-items">
                  {catalog.runs
                    .filter(
                      (r) =>
                        runFilter !== "Failed" ||
                        label(r["run/status"]) === "failed",
                    )
                    .map((r) => (
                      <button
                        key={r["run/id"]}
                        className={r["run/id"] === run?.id ? "active" : ""}
                        onClick={() => {
                          selectRun(r["run/id"]);
                          setRunsOpen(false);
                        }}
                      >
                        <Status compact status={label(r["run/status"])} />
                        <span>
                          <strong>{r["run/id"].slice(0, 8)}</strong>
                          <small>
                            v{r["run/pipeline"]?.["pipeline/version"]} ·{" "}
                            {title(label(r["run/pipeline"]?.["pipeline/name"]))}
                          </small>
                        </span>
                      </button>
                    ))}
                </div>
              </aside>
            </>
          )}
          {workspace === "action" && action ? (
            <>
              <aside className="pipeline-outline" aria-label="Pipeline outline">
                <div className="outline-heading">
                  <span>Pipeline</span>
                  <Button
                    variant="ghost"
                    color="gray"
                    onClick={() => {
                      setWorkspace("canvas");
                      setSelection({ task: task.name, action: null });
                    }}
                  >
                    Canvas <kbd>Esc</kbd>
                  </Button>
                </div>
                <div className="outline-tasks">
                  {definition.tasks.map((t) => (
                    <div key={t.name}>
                      <button
                        className="outline-task"
                        onClick={() => openTask(t.name)}
                      >
                        <Status compact status={states[t.name]?.status} />
                        {title(t.name)}
                      </button>
                      {t.name === task.name &&
                        t.actions.map((a) => (
                          <button
                            className={`outline-action ${a.name === action.name ? "active" : ""}`}
                            key={a.name}
                            onClick={() => openTask(t.name, a.name)}
                          >
                            <span>{a.index + 1}</span>
                            {a.name}
                          </button>
                        ))}
                    </div>
                  ))}
                </div>
                <p className="outline-help">⌥↑ / ⌥↓ previous / next action</p>
              </aside>
              <div className="action-layout" data-panel={actionTab}>
                <div className="action-toolbar">
                  <Button
                    className="mobile-canvas"
                    variant="ghost"
                    color="gray"
                    onClick={up}
                  >
                    <ArrowLeft size={13} />
                    Pipeline
                  </Button>
                  <button
                    className="task-breadcrumb"
                    onClick={() => openTask(task.name)}
                  >
                    {title(task.name)}
                  </button>
                  <ChevronRight size={12} />
                  <h1 ref={heading} tabIndex={-1}>
                    {action.name}
                  </h1>
                  <div className="mobile-action-picker">
                    <Select.Root
                      value={action.name}
                      onValueChange={(value) => openTask(task.name, value)}
                    >
                      <Select.Trigger aria-label="Select action" />
                      <Select.Content>
                        {task.actions.map((a) => (
                          <Select.Item key={a.name} value={a.name}>
                            {a.index + 1}. {a.name}
                          </Select.Item>
                        ))}
                      </Select.Content>
                    </Select.Root>
                  </div>
                  <div className="action-pagination">
                    <IconButton
                      variant="ghost"
                      color="gray"
                      aria-label="Previous action"
                      disabled={action.index === 0}
                      onClick={() =>
                        openTask(task.name, task.actions[action.index - 1].name)
                      }
                    >
                      <ChevronLeft size={12} />
                    </IconButton>
                    <span>
                      {action.index + 1} of {task.actions.length}
                    </span>
                    <IconButton
                      variant="ghost"
                      color="gray"
                      aria-label="Next action"
                      disabled={action.index === task.actions.length - 1}
                      onClick={() =>
                        openTask(task.name, task.actions[action.index + 1].name)
                      }
                    >
                      <ChevronRight size={12} />
                    </IconButton>
                  </div>
                  <div className="action-run-controls">
                    {applyControl}
                    {fixtureButton}
                    {editable && !task.complex && (
                      <>
                        <Tooltip content="Explicitly runs task setup and every action through this action. Upstream tasks do not run automatically.">
                          <Button
                            variant="surface"
                            color="gray"
                            disabled={busy || !connected}
                            onClick={() => execute("prefix")}
                          >
                            Run to here
                          </Button>
                        </Tooltip>
                        {runStep("action")}
                      </>
                    )}
                    {editable && task.complex && runStep("task")}
                    {!editable && (
                      <Button variant="surface" onClick={openRunVersion}>
                        Open in Build
                      </Button>
                    )}
                  </div>
                </div>
                <div
                  className="mobile-action-tabs"
                  role="tablist"
                  aria-label="Action panels"
                >
                  {["Edit", "Inputs", "Result", "Logs", "Metrics"].map(
                    (tab) => (
                      <Button
                        role="tab"
                        aria-selected={actionTab === tab}
                        key={tab}
                        variant={actionTab === tab ? "soft" : "ghost"}
                        color="gray"
                        onClick={() => {
                          setActionTab(tab);
                          if (tab !== "Edit") setInspectorTab(tab);
                        }}
                      >
                        {tab === "Edit" ? "Action EDN" : tab}
                      </Button>
                    ),
                  )}
                </div>
                <section className="action-code" aria-label="Action editor">
                  {errorView}
                  <div className="inputs-summary">
                    <div>
                      <strong>Available Inputs</strong>
                      <span>
                        {inputView?.notices?.length
                          ? "Prerequisites need attention"
                          : "Earlier results and configuration"}
                      </span>
                    </div>
                    <Button
                      variant="ghost"
                      color="gray"
                      onClick={() => {
                        setInspectorTab("Inputs");
                        setActionTab("Inputs");
                      }}
                    >
                      Peek <ChevronRight size={12} />
                    </Button>
                  </div>
                  {inputView?.notices?.length > 0 && (
                    <Callout.Root color="amber" size="1">
                      <Callout.Text>
                        {inputView.notices.join(" ")}
                        <span className="scope-note">
                          Upstream tasks never run automatically.
                        </span>
                      </Callout.Text>
                    </Callout.Root>
                  )}
                  {task.complex && (
                    <Callout.Root size="1">
                      <Callout.Text>
                        This iterator or parallel task uses a whole-task
                        preview. Run task executes its complete semantics.
                      </Callout.Text>
                    </Callout.Root>
                  )}
                  {dirty && (
                    <span className="draft-context">
                      Pending source · Inputs show the previous prepared context
                    </span>
                  )}
                  <div className="action-code-editor">
                    {draftSource == null ? (
                      <p className="muted" role="status">Loading source…</p>
                    ) : range ? (
                      <EdnEditor
                        name="Action EDN editor"
                        documentId={`${previewSession?.id}:${task.name}:${action.name}`}
                        value={rawSource.slice(range.from, range.to)}
                        readOnly={!editable}
                        onChange={updateAction}
                      />
                    ) : (
                      <div className="empty-state">
                        <h2>Edit this action in Source</h2>
                        <p>
                          Its range cannot be identified safely in the current
                          text. Your complete draft is preserved.
                        </p>
                        <Button variant="surface" onClick={openSource}>
                          Open Source
                        </Button>
                      </div>
                    )}
                  </div>
                  <footer className="editor-status">
                    <span>
                      {dirty
                        ? "Unacknowledged text"
                        : saved
                          ? "Saved version"
                          : "Draft autosaved"}
                    </span>
                    <span>
                      {busy
                        ? "Executing…"
                        : validation
                          ? "Definition needs attention"
                          : checkedSource?.edn === rawSource &&
                              checkedSource.valid
                            ? "Validation passed · runtime checks remain"
                            : "Run action uses valid earlier results"}
                    </span>
                  </footer>
                </section>
                <section className="action-result" aria-label="Action result">
                  {inspector}
                  {editable && (
                    <div className="result-continuation">
                      <Button
                        variant="surface"
                        color="gray"
                        disabled={busy || !connected}
                        onClick={() =>
                          setActionTarget({
                            task: task.name,
                            index: action.index + 1,
                          })
                        }
                      >
                        <Plus size={13} />
                        Add next action
                      </Button>
                    </div>
                  )}
                </section>
              </div>
            </>
          ) : (
            <>
              <section
                className={`primary-workspace ${workspace === "source" ? "source-workspace" : ""}`}
                aria-label={
                  workspace === "source" ? "Pipeline source" : "Pipeline canvas"
                }
              >
                <div className="canvas-toolbar">
                  <div className="view-switch">
                    <Button
                      variant={workspace === "canvas" ? "soft" : "ghost"}
                      color="gray"
                      onClick={() => setWorkspace("canvas")}
                    >
                      Canvas
                    </Button>
                    <Button
                      variant={workspace === "source" ? "soft" : "ghost"}
                      color="gray"
                      aria-label="Pipeline source"
                      onClick={openSource}
                    >
                      EDN
                    </Button>
                  </div>
                  {editable && workspace !== "source" && (
                    <Button
                      variant="surface"
                      color="gray"
                      disabled={busy || !connected}
                      onClick={() => edit({ op: "add-task" })}
                    >
                      <Plus size={13} />
                      Add task
                    </Button>
                  )}
                  {workspace === "source" && (
                    <>
                      <div className="view-switch">
                        {["Definition", "Configuration"].map((tab) => (
                          <Button
                            key={tab}
                            variant={sourceTab === tab ? "soft" : "ghost"}
                            color="gray"
                            onClick={() => setSourceTab(tab)}
                          >
                            {tab}
                          </Button>
                        ))}
                      </div>
                      {applyControl}
                    </>
                  )}
                  {mode === "Runs" && (
                    <>
                      <Status status={runStatus} />
                      <span className="muted">
                        {run?.id.slice(0, 8)} · v{definition?.version}
                      </span>
                      <Button
                        variant="surface"
                        color="gray"
                        onClick={openRunVersion}
                      >
                        Open in Build
                      </Button>
                    </>
                  )}
                  <span className="canvas-context">
                    {dirty
                      ? "Saving source · retained results may belong to an earlier revision"
                      : mode === "Runs"
                        ? "Exact run revision"
                        : "Select a task to inspect it"}
                  </span>
                  <IconButton
                    className="inspector-toggle"
                    variant="surface"
                    color="gray"
                    aria-label="Open inspector"
                    onClick={() => {
                      setDrawer(true);
                      setInspectorOpen(true);
                    }}
                  >
                    <PanelRight size={14} />
                  </IconButton>
                </div>
                {workspace === "source" ? (
                  <>
                    <div className="source-errors">{errorView}</div>
                    <div className="source-code">
                      <EdnEditor
                        documentId={previewSession?.id ?? run?.id}
                        name={
                          sourceTab === "Definition"
                            ? "Pipeline EDN editor"
                            : "Configuration EDN editor"
                        }
                        value={
                          sourceTab === "Definition"
                            ? rawSource
                            : configSource || "; Configuration unavailable"
                        }
                        readOnly={!editable || (sourceTab === "Definition" ? draftSource == null : configSource == null)}
                        onChange={(value, update) =>
                          updateSource(
                            value,
                            sourceTab === "Configuration",
                            update,
                          )
                        }
                        onCursor={
                          sourceTab === "Definition"
                            ? sourceAtCursor
                            : undefined
                        }
                        cursor={
                          sourceTab === "Definition" ? sourceCursor : null
                        }
                      />
                    </div>
                    <footer className="editor-status">
                      <span>
                        {dirty
                          ? "Saving draft text…"
                          : editable
                            ? "Draft autosaved"
                            : "Historical revision · read-only"}
                      </span>
                      <span>⌘E Canvas / EDN</span>
                    </footer>
                  </>
                ) : (
                  <>
                    <div className="canvas-messages">
                      {errorView}
                      {mode === "Runs" && !taskShowsRunError && (
                        <ErrorNotice error={runError} />
                      )}
                    </div>
                    <section
                      className="graph-panel"
                      aria-label="Pipeline graph"
                    >
                      {graph || (
                        <div className="empty-state">
                          <h2>No runs yet</h2>
                          <p>
                            Run a pipeline from Build to create an execution.
                          </p>
                        </div>
                      )}
                    </section>
                    <footer className="canvas-footer">
                      <span>Space pan · Enter open · R run selected</span>
                      <span>{definition?.tasks.length || 0} tasks</span>
                    </footer>
                  </>
                )}
              </section>
              {inspectorOpen && (
                <aside
                  className={`context-panel ${drawer ? "drawer-open" : ""}`}
                  aria-label={task ? "Task details" : "Pipeline inspector"}
                >
                  {task ? taskPane() : pipelinePane()}
                </aside>
              )}
            </>
          )}
        </main>
      )}
      <PipelineNav
        open={!!palette}
        onOpenChange={(open) => !open && setPalette(null)}
        title={
          palette === "pipelines"
            ? "Switch pipeline"
            : "Search or run a command"
        }
        entries={palette === "pipelines" ? pipelineCommands : commands}
      />
      {!connected && previewSession && (
        <div className="offline-note" role="status">
          Engine disconnected · unacknowledged text is protected locally. Check,
          Save and execution will be available after reconnecting.
        </div>
      )}
      <AgentHelp
        open={agentHelpOpen}
        session={previewSession}
        selection={selection}
        onClose={() => setAgentHelpOpen(false)}
      />
      <ActionCatalogue
        target={actionTarget}
        onClose={() => setActionTarget(null)}
        onChoose={(entry) => {
          const target = actionTarget;
          setActionTarget(null);
          edit({
            ...target,
            op: "add-action",
            ...(entry ? { actionEdn: entry.example } : {}),
          });
        }}
      />
      <DraftHistory
        session={session}
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        onRestore={(fromRevision) =>
          perform(async () => {
            const active = await flushDraft();
            accept(
              await api(`/sessions/${active.id}/restore`, {
                revision: active.revision,
                fromRevision,
              }),
            );
            setHistoryOpen(false);
          })
        }
      />
      <DraftConflict
        conflict={conflict}
        onCurrent={() => {
          changeDraft(null);
          accept(conflict.current);
          setConflict(null);
        }}
        onReplace={() => {
          const pending = {
            ...conflict.local,
            revision: conflict.current.revision,
            configurationRevision: conflict.current.configurationRevision,
          };
          accept(conflict.current);
          changeDraft(pending);
          setConflict(null);
        }}
      />
      {fixtureOpen && session && task && !saved && (
        <FixtureDialog
          draftId={session.id}
          task={task.name}
          action={action}
          onClose={() => setFixtureOpen(false)}
          onRun={async (scope, fixture) => {
            const active = await flushDraft();
            const destination = draftTarget();
            const job = await waitExecution(`/sessions/${active.id}/execute`, {
              scope,
              "task-name": destination.task,
              ...(scope === "task" ? {} : { index: destination.index }),
              revision: active.revision,
              instanceId: active.instanceId,
              idempotencyKey: crypto.randomUUID(),
              fixtureId: fixture.fixtureId,
              fixtureRevision: fixture.fixtureRevision,
            });
            return api(`/executions/${job.id}?view=result`);
          }}
        />
      )}
      <Dialog.Root
        open={runChoice !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setRunChoice(null);
        }}
      >
        <Dialog.Content maxWidth="440px">
          <Dialog.Title>Run pipeline</Dialog.Title>
          <Dialog.Description>
            {runChoice === "draft"
              ? "Save the current draft as a version, then start a fresh run."
              : "Run the selected saved version. Draft changes are kept separately."}
          </Dialog.Description>
          <div style={{ marginTop: 16 }}>
            <label htmlFor="pipeline-run-version">Pipeline version</label>
            <Select.Root
              value={runChoice || ""}
              onValueChange={setRunChoice}
              disabled={busy}
            >
              <Select.Trigger
                id="pipeline-run-version"
                aria-label="Pipeline version"
                style={{ width: "100%", marginTop: 8 }}
              />
              <Select.Content>
                {unpublished && (
                  <Select.Item value="draft">
                    Current draft · save changes
                  </Select.Item>
                )}
                {runVersions.map((version) => (
                  <Select.Item key={version} value={String(version)}>
                    Version {version}
                    {version === latestListedVersion ? " · latest" : ""}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>
          </div>
          {errorNotice}
          <div className="dialog-actions">
            <Dialog.Close>
              <Button variant="soft" color="gray" disabled={busy}>
                Cancel
              </Button>
            </Dialog.Close>
            <Button
              disabled={busy || runChoice === null}
              onClick={startPipeline}
            >
              <Play size={15} />
              {runChoice === "draft" ? "Save & run" : "Run version"}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Root>
      <Dialog.Root
        open={!!portalUrl}
        onOpenChange={(open) => !open && setPortalUrl(null)}
      >
        <Dialog.Content maxWidth="420px">
          <Dialog.Title>Inspect in Portal</Dialog.Title>
          <Dialog.Description>
            Portal opens in a companion tab. If it was blocked, use the link
            below.
          </Dialog.Description>
          <p className="portal-link">
            <a href={portalUrl || "#"} target="_blank" rel="noreferrer">
              Open Portal inspector
            </a>
          </p>
          <div className="dialog-actions">
            <Dialog.Close>
              <Button variant="soft" color="gray">
                Close
              </Button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Root>
    </div>
  );
}
