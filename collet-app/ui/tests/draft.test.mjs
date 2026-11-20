import { test } from "node:test";
import assert from "node:assert/strict";
import { ChangeSet } from "@codemirror/state";
import {
  actionRange,
  sourceIndex,
  replaceAction,
  mapRegions,
  migratePending,
} from "../src/draft.js";

const edn =
  '{:name :demo :custom #rgx "ok" :tasks [{:name :one :actions [;; preserved\n{:name :first :type :clj/identity :params [1]} {:name :second :type :clj/identity :params [2]}]} {:name :two :actions [{:name :first :type :clj/inc :params [1]}]}]}';
test("action lenses preserve surrounding EDN and incomplete edits", () => {
  const region = actionRange(edn, null, "one", "second");
  const first = replaceAction(edn, null, region, "{:name :renamed :params [");
  assert.equal(
    first.edn,
    edn.slice(0, region.from) +
      "{:name :renamed :params [" +
      edn.slice(region.to),
  );
  const reopened = actionRange(first.edn, first.regions, "one", "second");
  const repaired = replaceAction(
    first.edn,
    first.regions,
    reopened,
    "{:name :renamed :type :clj/identity :params [3]}",
  );
  assert.ok(repaired.edn.includes(";; preserved"));
  assert.ok(repaired.edn.includes(':custom #rgx "ok"'));
  assert.equal(sourceIndex(repaired.edn).tasks[0].actions[1].action, "renamed");
  assert.equal(sourceIndex(repaired.edn).tasks[1].actions[0].action, "first");
});
test("source changes rebase ranges; a full replacement resolves reordered forms by name", () => {
  const prefix = ";; note\n";
  const change = ChangeSet.of({ from: 0, insert: prefix }, edn.length);
  const regions = mapRegions(edn, null, change, prefix + edn);
  assert.equal(
    actionRange(prefix + edn, regions, "one", "first").from,
    actionRange(edn, null, "one", "first").from + prefix.length,
  );
  const reordered = edn.replace(
    ":name :first :type :clj/identity :params [1]",
    ":name :third :type :clj/identity :params [1]",
  );
  const all = ChangeSet.of(
    { from: 0, to: edn.length, insert: reordered },
    edn.length,
  );
  const remapped = mapRegions(edn, null, all, reordered);
  assert.equal(actionRange(reordered, remapped, "one", "first"), null);
  assert.ok(actionRange(reordered, remapped, "one", "third"));
});
test("ambiguous or missing ranges never overwrite unrelated text", () => {
  assert.equal(actionRange("{:tasks [", null, "one", "first"), null);
  const duplicate = edn.replace(":name :second", ":name :first");
  assert.equal(actionRange(duplicate, null, "one", "first"), null);
});

test("leading comments and reader-discarded forms do not hide action ranges", () => {
  const prefixed = ";; pipeline note\n#_{:ignored true}\n" + edn;
  const range = actionRange(prefixed, null, "one", "first");
  assert.ok(range);
  assert.equal(
    prefixed.slice(range.from, range.to),
    "{:name :first :type :clj/identity :params [1]}",
  );
});
test("legacy action buffers migrate into the shared pipeline text", () => {
  const session = {
    document: {
      edn,
      tasks: [
        { name: "one", actions: [{ name: "first" }, { name: "second" }] },
      ],
    },
    configEdn: "{}",
  };
  const migrated = migratePending(
    { kind: "action", task: "one", index: 1, edn: "{:name :changed}" },
    session,
  );
  assert.equal(migrated.kind, "source");
  assert.ok(migrated.edn.includes("{:name :changed}"));
  assert.ok(migrated.edn.includes(':custom #rgx "ok"'));
});

test("expired-session recovery merges an incomplete legacy action without normalizing its pipeline", () => {
  const fragment = ";; unfinished replacement\n{:name :changed :params [";
  const pending = { kind: "action", task: "one", index: 1, edn: fragment };
  const migrated = migratePending(pending, {
    document: { edn },
    configEdn: "{}",
  });
  const range = actionRange(edn, null, "one", "second");
  assert.equal(
    migrated.edn,
    edn.slice(0, range.from) + fragment + edn.slice(range.to),
  );
  assert.equal(
    migratePending(migrated, { document: { edn: migrated.edn } }).edn,
    migrated.edn,
  );
});

test("ambiguous legacy recovery retains the full source and the unplaced action separately", () => {
  const source = edn.replace(":name :second", ":name :first");
  const pending = {
    kind: "action",
    task: "one",
    index: 1,
    edn: "{:name :unfinished",
  };
  const migrated = migratePending(pending, { document: { edn: source } });
  assert.equal(migrated.edn, source);
  assert.equal(migrated.migrationError, true);
  assert.deepEqual(migrated.unplacedAction, pending);
});
