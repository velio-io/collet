# REPL Workflow

Collet uses a REPL-first Clojure workflow. Agents must attach to or start an
nREPL from the active worktree before editing Clojure source, tests, fixtures,
or build tooling. Each worktree needs its own JVM so reloads and evaluations use
the files being edited.

## Prerequisites

- JDK 25 or newer.
- Clojure CLI.
- Babashka.
- `clj-nrepl-eval` on `PATH` for the preferred eval client.
- `clj-paren-repair` on `PATH` for delimiter recovery.

The repository includes `scripts/agent/nrepl-eval.bb` as an eval fallback.
Parenthesis repair remains an explicit local development prerequisite.

## Start nREPL

Run from the active worktree root:

```shell
clojure -M:dev:nrepl
```

The server binds only to `127.0.0.1`, chooses a free port, and writes that port
to `.nrepl-port`. The file is ignored by Git and removed when the JVM shuts down
normally. Keep this command running in a long-lived terminal.

## Discover a Running Server

Prefer the installed client:

```shell
clj-nrepl-eval --discover-ports
```

Use the repository fallback when the global client is unavailable:

```shell
bb scripts/agent/nrepl-eval.bb --discover
```

The fallback checks `.nrepl-port` in the current directory and Git worktree
root, then reports listening Java endpoints as diagnostic candidates. Always
choose the port belonging to the active worktree.

## Evaluate Code

With the preferred client:

```shell
clj-nrepl-eval -p <port> "(+ 1 2)"
clj-nrepl-eval -p <port> --timeout 5000 "(+ 1 2)"
```

With the repository fallback:

```shell
bb scripts/agent/nrepl-eval.bb \
  --port <port> \
  --code "(+ 1 2)"

bb scripts/agent/nrepl-eval.bb \
  --port <port> \
  --timeout 5 \
  --code "(+ 1 2)"
```

The global client timeout is expressed in milliseconds; the fallback timeout is
expressed in seconds. Don't overuse timeouts, try first without timeout.
Both clients reuse the running JVM, so loaded namespaces
and state persist between evaluations.

The fallback exits with `0` on success, `1` for an nREPL evaluation error, `3`
for a connection failure, `4` for a timeout, and `64` for invalid usage.

## Reload Changed Code

The primary cycle is:

```text
attach or start -> edit -> reload -> validate
```

After changing production or shared-fixture code:

```shell
clj-nrepl-eval -p <port> "(user/reload)"
```

Fallback:

```shell
bb scripts/agent/nrepl-eval.bb \
  --port <port> \
  --code "(user/reload)"
```

`dev/user.clj` configures clj-reload for every existing `collet-*/src`
directory plus `test-fixtures/src`. `user` is excluded so the REPL entrypoint
and reload function remain stable. The reload result reports the namespaces
that were unloaded and loaded.

Never use `:reload-all` or `tools.namespace/refresh-all`. Those operations can
walk into dependencies, recreate protocol identities, and leave existing
records or deftypes implementing an obsolete protocol.

Package tests and `build/src` deliberately remain outside the root reload
scope. A worktree REPL is still required before editing them, but their
executable validation is the isolated package or build test command:

```shell
bb test:module <module>
clojure -T:build-test
```

## Stateful Values

Reload replaces namespace vars and may recreate records, deftypes, protocols,
and multimethod definitions. Do not rely on live pipelines, task results,
publishers, or protocol implementations created before a reload.

After `(user/reload)`:

1. Re-evaluate the form that constructs the pipeline or runtime value.
2. Repeat the focused behavior check.
3. Restart the nREPL JVM if dispatch reports an old class or protocol identity.

Collet does not automatically stop or restart pipelines from `user/reload`.
Automatic lifecycle management would be unsafe because the REPL cannot infer
which user-created runtime values are disposable.

## Dependency and Library Discovery

Inspect dependency APIs through the running REPL instead of scraping Maven
caches, Git dependency checkouts, JARs, or generated POMs:

```clojure
(require '[some.library :as lib] :reload)
(dir some.library)
(doc lib/function)
(source lib/function)
(ns-publics 'some.library)
```

Repository source can be read directly. Use `:reload` during manual namespace
investigation so a cached require does not hide recent edits.

## Parenthesis Repair

When reload reports an unmatched, unexpected, or EOF delimiter error, do not
balance the form manually. From the repository root run:

```shell
clj-paren-repair path/to/file.clj
```

Multiple files are supported:

```shell
clj-paren-repair path/to/first.clj path/to/second.clj
```

In file mode the tool repairs delimiters and invokes cljfmt in the current
project environment, so Collet's `.cljfmt.edn` is applied. Use it only for
delimiter recovery, not routine formatting.

After repair:

1. Inspect `git diff -- path/to/file.clj`.
2. Confirm the repaired form still expresses the intended behavior.
3. Evaluate `(user/reload)` again.
4. Run the affected package or build tests.

If `clj-paren-repair` is missing or returns a failure, stop and report the
blocker rather than guessing at the delimiter structure.

## Sandbox and Connection Recovery

Some agent sandboxes deny loopback sockets or process inspection. A failure
such as `java.net.SocketException: Operation not permitted` is an environment
restriction, not evidence that nREPL failed to start.

Use this recovery order:

1. Confirm the command is running from the active worktree.
2. Rediscover ports with the preferred client.
3. Try the repository fallback discovery and eval commands.
4. Rerun the exact socket command through the environment's approval mechanism.
5. If connection is still unavailable, report a blocker instead of continuing
   with a CLI-only Clojure workflow.

## Troubleshooting

| Problem | Resolution |
| --- | --- |
| No `.nrepl-port` | Start `clojure -M:dev:nrepl` from the worktree root. |
| Stale or refused port | Stop the stale JVM if owned by this worktree, start a new nREPL, and rediscover. |
| `Operation not permitted` | Request the environment's loopback/process approval and rerun the command. |
| Eval client missing | Use `bb scripts/agent/nrepl-eval.bb`. |
| Eval times out | Reduce the form, set a bounded timeout, and check for blocking work in the JVM. |
| Reload reports a delimiter error | Run `clj-paren-repair` on the reported file, inspect the diff, and reload again. |
| Reload reports a stale protocol/class | Recreate affected values; restart the JVM if stale identity remains. |
| Test namespace changed | Run its package/build CLI test; tests are not added to the root REPL classpath. |

## Safety

- Keep evals small and deterministic.
- Do not run file deletion, Git mutation, publication, or external network
  actions through nREPL without explicit authorization.
- Do not treat a successful reload as test coverage. Run the owning tests.
- Do not run `bb release` as part of development or REPL validation.

See [development](./development.md) for the complete command contract and
[releasing](./releasing.md) for approval-gated publication.
