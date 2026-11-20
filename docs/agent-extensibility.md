# Extending actions

Prefer a catalogue built-in action, then a `:clj/*` core function. Reserve inline
forms for domain logic those actions do not cover. `collet_describe_action` accepts an exact action type
and returns its authoring fields, effects and dependency recipe.

## Core functions

`:clj/NAME` calls a public `clojure.core` function with positional `:params`.
For example, `{:name :read :type :clj/slurp :params ["/tmp/input.txt"]}` reads a
file. `:clj/spit` writes a file and normally replaces its contents. These actions
execute real I/O. An unknown function or macro is not a callable core action.
Describe the exact function to inspect its argument lists.

## Custom functions and SCI

`:custom` evaluates its `:fn` form in SCI during preparation. A function body runs
when the action runs. Do not put effects in the outer preparation expression.

```clojure
{:name :convert :type :custom
 :fn (fn [row] (assoc row :fahrenheit (+ 32 (* 1.8 (:celsius row)))))
 :selectors {row [:$mapper/item]}
 :params [row]}
```

The default SCI environment supports ordinary pure core functions, including
`map`, `mapv`, `filter`, `filterv`, `reduce`, `get`, `assoc`, arithmetic and
conditionals. It also exposes `println`. It does not expose `slurp` or `spit` by
default. Use explicit core I/O actions for those operations. A successful
`:clj/slurp` does not imply that `(slurp ...)` resolves inside SCI.

EDN function forms such as `(fn [x] (inc x))` and qualified symbols such as
`clojure.core/inc` serialize as durable data. Live function objects and closures
are rejected when saving. In a Clojure program, quote the form to construct that
data; in a pipeline EDN file, write the form directly without an extra quote.

Maven `:deps/:coordinates` supplies external libraries. `:deps/:requires` loads
namespaces and `:deps/:imports` exposes Java classes; neither fetches missing
libraries. Declare pinned coordinates even when discovery says
`classpath-present`: that describes the current process only. Successful
Workbench or saved-run execution does not prove clean-process portability. Select configuration and previous results into named
parameters; custom code does not inherit the application's global bindings.
Preparation may load declared dependencies. Passive validation never evaluates
custom forms and cannot prove their runtime behavior.

## File-backed actions

A qualified action type ending in `.edn` loads that action definition during
preparation. Discovery and Check never read the referenced file. Its effects and
runtime compatibility remain unknown until explicitly prepared and executed.

## Task boundary representations

Within a task, selectors see the previous action's actual result. Across tasks,
retained tabular output normally becomes dataset batches. Authoring and saved
runs use the same handoff. Inspection labels the representation separately from
its row sample. A displayed row path is not necessarily a selector on the batch
container. Use `:slicer` to transform tables or `:mapper` to bind each row to
`:$mapper/item`; see `collet://docs/recipes`.
