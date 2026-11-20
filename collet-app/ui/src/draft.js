import { clojureLanguage } from "@nextjournal/lang-clojure";
import { ChangeSet } from "@codemirror/state";

const ignored = new Set(["{", "}", "[", "]", "LineComment", "Discard"]);
function forms(node) {
  const result = [];
  for (let child = node?.firstChild; child; child = child.nextSibling)
    if (!ignored.has(child.name)) result.push(child);
  return result;
}
function field(node, text, key) {
  const children = forms(node);
  for (let i = 0; i < children.length; i += 2)
    if (text.slice(children[i].from, children[i].to) === `:${key}`)
      return children[i + 1];
}
function name(node, text) {
  const value = field(node, text, "name");
  return value?.name === "Keyword"
    ? text.slice(value.from + 1, value.to)
    : null;
}
const key = (task, action) => JSON.stringify([task, action]);

// Syntax ranges only; the server remains responsible for EDN semantics.
export function sourceIndex(text) {
  const top = forms(clojureLanguage.parser.parse(text).topNode);
  const root = top.length === 1 ? top[0] : null;
  const tasks = field(root, text, "tasks");
  const result = { tasks: [], actions: {} };
  if (root?.name !== "Map" || tasks?.name !== "Vector") return result;
  for (const task of forms(tasks)) {
    if (task.name !== "Map") continue;
    const taskName = name(task, text);
    if (!taskName) continue;
    const entry = { name: taskName, from: task.from, to: task.to, actions: [] };
    const actions = field(task, text, "actions");
    function collect(node) {
      if (node.name === "Vector") forms(node).forEach(collect);
      else if (node.name === "Map") {
        const actionName = name(node, text);
        if (!actionName) return;
        const range = {
          from: node.from,
          to: node.to,
          index: entry.actions.length,
          task: taskName,
          action: actionName,
        };
        const callable = field(node, text, "fn") || field(node, text, "type");
        if (callable && ["Symbol", "Keyword"].includes(callable.name))
          range.call = text.slice(callable.from, callable.to);
        let errors = false;
        node.cursor().iterate((n) => {
          if (n.type.isError) errors = true;
        });
        range.safe = !errors && text[node.to - 1] === "}";
        entry.actions.push(range);
        const id = key(taskName, actionName);
        result.actions[id] = result.actions[id] ? { safe: false } : range;
      }
    }
    if (actions?.name === "Vector") collect(actions);
    result.tasks.push(entry);
  }
  return result;
}

export function actionRange(text, regions, task, action) {
  const tracked = regions?.[key(task, action)];
  if (tracked?.safe && tracked.from >= 0 && tracked.to <= text.length)
    return tracked;
  const parsed = sourceIndex(text).actions[key(task, action)];
  return parsed?.safe ? parsed : null;
}

export function mapRegions(text, regions, changes, nextText) {
  const next = {};
  for (const [id, range] of Object.entries(
    regions || sourceIndex(text).actions,
  )) {
    if (!range.safe) continue;
    let boundary = false;
    changes.iterChangedRanges((from, to) => {
      if (
        (from <= range.from && to > range.from) ||
        (from < range.to && to >= range.to)
      )
        boundary = true;
    });
    if (!boundary)
      next[id] = {
        ...range,
        from: changes.mapPos(range.from, 1),
        to: changes.mapPos(range.to, -1),
      };
  }
  const parsed = sourceIndex(nextText).actions;
  // Reacquire moved forms by name, without guessing from their old position.
  for (const [id, range] of Object.entries(parsed))
    if (!next[id] && range.safe) next[id] = range;
  return next;
}

export function replaceAction(text, regions, range, value) {
  const suffix = text.slice(range.to);
  const needsLineBreak =
    /^[^\S\r\n]*[^\s]/.test(suffix) &&
    value.includes(";") &&
    clojureLanguage.parser.parse(value).topNode.lastChild?.name === "LineComment" &&
    !/[\r\n][^\S\r\n]*$/.test(value);
  // Keep an action's trailing comment from swallowing the next form.
  const insert = needsLineBreak ? value + "\n" : value;
  const changes = ChangeSet.of(
    { from: range.from, to: range.to, insert },
    text.length,
  );
  const edn = text.slice(0, range.from) + insert + suffix;
  const next = mapRegions(text, regions, changes, edn);
  next[key(range.task, range.action)] = {
    ...range,
    to: range.from + value.length,
    safe: true,
  };
  return { edn, regions: next };
}

export function migratePending(pending, session) {
  if (!pending) return null;
  if (pending.kind === "action") {
    const task = sourceIndex(session.document.edn).tasks.find(
      (t) => t.name === pending.task,
    );
    const action = task?.actions[pending.index];
    const range =
      action &&
      actionRange(session.document.edn, null, task.name, action.action);
    if (!range)
      return {
        ...pending,
        kind: "source",
        edn: session.document.edn,
        unplacedAction: pending,
        migrationError: true,
      };
    return {
      ...pending,
      ...replaceAction(session.document.edn, null, range, pending.edn),
      kind: "source",
      configEdn: session.configEdn,
    };
  }
  return {
    ...pending,
    configEdn: pending.configEdn ?? pending.config ?? session.configEdn,
  };
}
