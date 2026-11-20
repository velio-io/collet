import { useState } from "react";
import {
  Callout,
  DropdownMenu,
  IconButton,
  Select,
  Table,
  Tabs,
  Tooltip,
} from "@radix-ui/themes";
import {
  Copy,
  ExternalLink,
  Check,
  AlertCircle,
  MoreHorizontal,
  X,
} from "lucide-react";
import { cell, label } from "./api";
import DiagnosticsPanel from "./DiagnosticsPanel";

export function ErrorNotice({ error, onDismiss }) {
  if (!error) return null;
  return (
    <Callout.Root color="red" className="error-notice" role="alert">
      <Callout.Icon>
        <AlertCircle size={17} />
      </Callout.Icon>
      <div>
        <Callout.Text>
          <strong>{error.message || "Execution failed"}</strong>
        </Callout.Text>
        {error.cause?.message && <p>{error.cause.message}</p>}
        {(error.causes || []).slice(1).map((cause, i) => (
          <p key={i}>{typeof cause === "string" ? cause : cause.message}</p>
        ))}
        {error.details && (
          <details>
            <summary>Error details</summary>
            <pre>{JSON.stringify(error.details, null, 2)}</pre>
          </details>
        )}
      </div>
      {onDismiss && (
        <IconButton
          variant="ghost"
          color="red"
          aria-label="Dismiss error"
          onClick={onDismiss}
        >
          <X size={16} />
        </IconButton>
      )}
    </Callout.Root>
  );
}
function Tree({ value, path = [], paths, copy, depth = 0 }) {
  if (value === null || typeof value !== "object")
    return <pre>{cell(value)}</pre>;
  return (
    <div className="tree">
      {Object.entries(value).map(([key, child]) => {
        const next = [...path, Array.isArray(value) ? Number(key) : key];
        const selector = paths[JSON.stringify(next)];
        return (
          <details key={key} open={depth < 1}>
            <summary>
              <span className="tree-key">
                {Array.isArray(value) ? key : `:${key}`}
              </span>
              <span className="tree-value">
                {child !== null && typeof child === "object"
                  ? `${Array.isArray(child) ? "vector" : "map"} · ${Object.keys(child).length}`
                  : cell(child)}
              </span>
              {selector && (
                <Tooltip content="Copy selector">
                  <IconButton
                    size="1"
                    variant="ghost"
                    color="gray"
                    aria-label={`Copy selector ${selector}`}
                    onClick={(e) => {
                      e.preventDefault();
                      copy(selector);
                    }}
                  >
                    <Copy size={13} />
                  </IconButton>
                </Tooltip>
              )}
            </summary>
            {child !== null && typeof child === "object" && (
              <Tree
                value={child}
                path={next}
                paths={paths}
                copy={copy}
                depth={depth + 1}
              />
            )}
          </details>
        );
      })}
    </div>
  );
}
export function DataPanel({
  data,
  base = "",
  status,
  inspect,
  view = "Result",
  empty,
  emptyTitle,
  unavailable,
  disabled,
}) {
  const [format, setFormat] = useState("Table");
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(null);
  const shownFormat = format === "Table" && !data?.rows ? "Tree" : format;
  const paths = Object.fromEntries(
    (data?.selectors || []).map((x) => [JSON.stringify(x.path), x.selector]),
  );
  const copy = async (relative) => {
    try {
      await navigator.clipboard.writeText(
        relative.startsWith("[:$mapper/item")
          ? relative
          : `[${[base, relative.slice(1, -1)].filter(Boolean).join(" ")}]`,
      );
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      setCopyError({
        message:
          "Could not copy the selector. Check your browser clipboard permission.",
      });
    }
  };
  return (
    <div className="data-panel">
      {data?.selectorContext === "mapper-row" && (
        <p className="previous-sample">
          Dataset rows · copied fields use :$mapper/item inside a mapper action.
        </p>
      )}
      <div className="data-toolbar">
        <span className="sample-count">
          {data
            ? `${data.count != null ? `${data.count} rows` : data.type} · ${data.truncated ? "truncated sample" : "sample"}`
            : "No sample yet"}
        </span>
        <div className="data-options">
          <Select.Root value={shownFormat} onValueChange={setFormat}>
            <Select.Trigger variant="ghost" aria-label={`${view} format`} />
            <Select.Content>
              <Select.Item value="Table" disabled={!data?.rows}>
                Table
              </Select.Item>
              <Select.Item value="Tree">Tree</Select.Item>
              <Select.Item value="EDN">EDN</Select.Item>
            </Select.Content>
          </Select.Root>
          <DropdownMenu.Root>
            <DropdownMenu.Trigger>
              <IconButton
                variant="ghost"
                color="gray"
                aria-label={`${view} tools`}
              >
                <MoreHorizontal size={18} />
              </IconButton>
            </DropdownMenu.Trigger>
            <DropdownMenu.Content>
              <DropdownMenu.Item
                disabled={
                  !data || data.deferred || status === "stale" || disabled
                }
                onSelect={() => inspect(view)}
              >
                <ExternalLink size={15} />
                Inspect in Portal
              </DropdownMenu.Item>
            </DropdownMenu.Content>
          </DropdownMenu.Root>
        </div>
      </div>
      <div className="data-content">
        <ErrorNotice error={copyError} onDismiss={() => setCopyError(null)} />
        {copied && (
          <div className="copy-confirmation" role="status">
            <Check size={14} />
            Selector copied
          </div>
        )}
        {status === "stale" && data && (
          <Callout.Root color="amber" size="1">
            <Callout.Text>
              Previous sample · execute this step again to refresh it.
            </Callout.Text>
          </Callout.Root>
        )}
        {data?.truncated && (
          <details className="sample-limits">
            <summary>Showing a bounded sample</summary>
            <p>
              Up to 100 rows, 30 columns, 6 levels and 50 KB of EDN. Use Portal
              for the full value.
            </p>
          </details>
        )}
        {data?.deferred && (
          <Callout.Root size="1">
            <Callout.Text>
              This live sequence is not sampled. Inspect the retained task
              output after execution.
            </Callout.Text>
          </Callout.Root>
        )}
        {!data ? (
          <div className="empty-state">
            <h3>
              {emptyTitle ||
                (unavailable
                  ? "Output unavailable"
                  : view === "Inputs"
                    ? "Inputs appear after execution"
                    : "Ready to explore")}
            </h3>
            <p>
              {unavailable ||
                empty ||
                "Run this step, then inspect its result here."}
            </p>
          </div>
        ) : shownFormat === "EDN" ? (
          <pre className="edn-result">{data.edn}</pre>
        ) : shownFormat === "Table" && data.rows ? (
          <Table.Root size="1" className="result-table">
            <Table.Header>
              <Table.Row>
                <Table.ColumnHeaderCell className="row-number">
                  #
                </Table.ColumnHeaderCell>
                {data.columns.map((column, i) => (
                  <Table.ColumnHeaderCell key={column}>
                    <button
                      className="column-selector"
                      title={
                        data.selectorContext === "mapper-row"
                          ? "Copy field selector for a mapper row"
                          : "Copy selector for first row"
                      }
                      disabled={!data.columnSelectors?.[i]}
                      aria-label={`Copy selector for ${column}`}
                      onClick={() => copy(data.columnSelectors[i])}
                    >
                      {column}
                      <Copy size={12} />
                    </button>
                  </Table.ColumnHeaderCell>
                ))}
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {data.rows.map((row, index) => (
                <Table.Row key={index}>
                  <Table.RowHeaderCell className="row-number">
                    {index + 1}
                  </Table.RowHeaderCell>
                  {row.map((value, i) => (
                    <Table.Cell
                      key={i}
                      className={
                        typeof value === "number" ? "numeric-cell" : undefined
                      }
                    >
                      {cell(value)}
                    </Table.Cell>
                  ))}
                </Table.Row>
              ))}
            </Table.Body>
          </Table.Root>
        ) : (
          <Tree value={data.value} paths={paths} copy={copy} />
        )}
      </div>
    </div>
  );
}
export default function Inspector({
  task,
  action,
  preview,
  inputs,
  error,
  status,
  events = [],
  inspect,
  unavailable,
  disabled,
  withInputs = true,
  withTabs = true,
  tab: controlledTab,
  onTabChange,
  inputsStatus,
  previous,
  notices,
  inputsError,
  diagnosticsPath,
  diagnosticsRevision,
}) {
  const [localTab, setLocalTab] = useState("Result");
  const tab = controlledTab || localTab;
  const setTab = onTabChange || setLocalTab;
  const relevant = events
    .filter((e) => !task || label(e.task) === task.name)
    .filter((e) => !action || label(e.action) === action.name || !e.action);
  return (
    <section className="inspector" aria-label="Data inspector">
      <Tabs.Root value={tab} onValueChange={setTab}>
        {withTabs && (
          <Tabs.List>
            <Tabs.Trigger value="Result">Result</Tabs.Trigger>
            {withInputs && <Tabs.Trigger value="Inputs">Inputs</Tabs.Trigger>}
            <Tabs.Trigger value="Logs">Logs</Tabs.Trigger>
            <Tabs.Trigger value="Metrics">Metrics</Tabs.Trigger>
          </Tabs.List>
        )}
        <Tabs.Content
          value={tab}
          className="inspector-content"
          aria-labelledby={undefined}
          aria-label={tab}
        >
          <ErrorNotice error={error} />
          {tab === "Inputs" && (
            <>
              <ErrorNotice error={inputsError} />
              {notices?.length > 0 && (
                <Callout.Root color="amber" size="1">
                  <Callout.Text>{notices.join(" ")}</Callout.Text>
                </Callout.Root>
              )}
            </>
          )}
          {tab === "Result" && previous && (
            <div className="previous-sample" role="status">
              Previous sample · produced by an earlier revision
            </div>
          )}
          {tab === "Logs" || tab === "Metrics" ? (
            <DiagnosticsPanel
              key={JSON.stringify([diagnosticsPath, task?.name, action?.name, tab])}
              path={diagnosticsPath}
              revision={diagnosticsRevision}
              task={task?.name}
              action={action?.name}
              view={tab === "Logs" ? "events" : "metrics"}
            />
          ) : tab === "Events" ? (
            <div className="events-list">
              {relevant.length ? (
                [...relevant].reverse().map((event, i) => (
                  <div className="event" key={event.sequence || i}>
                    <time>
                      {new Date(event.timestamp).toLocaleTimeString()}
                    </time>
                    <div>
                      <strong>
                        {label(
                          event["mulog/event-name"] ||
                            event["collet/event"] ||
                            event.kind,
                        )}
                      </strong>
                      <p>
                        {label(event.action || event.task)}
                        {event["mulog/duration"] != null &&
                          ` · ${(event["mulog/duration"] / 1e6).toFixed(1)} ms`}
                      </p>
                      <details>
                        <summary>
                          Details
                          {event["invocation-id"]
                            ? ` · ${event["invocation-id"].slice(0, 8)}`
                            : ""}
                        </summary>
                        <pre>{JSON.stringify(event, null, 2)}</pre>
                      </details>
                    </div>
                  </div>
                ))
              ) : (
                <div className="empty-state">
                  <h3>No events yet</h3>
                  <p>
                    Execute this step to see its transitions and diagnostics.
                  </p>
                </div>
              )}
            </div>
          ) : (
            <DataPanel
              data={tab === "Inputs" ? inputs : preview}
              base={
                tab === "Inputs"
                  ? ""
                  : action
                    ? `:state :${action.name}`
                    : `:inputs :${task?.name}`
              }
              status={tab === "Inputs" ? inputsStatus : status}
              view={tab}
              inspect={inspect}
              unavailable={tab === "Result" ? unavailable : null}
              disabled={disabled}
            />
          )}
        </Tabs.Content>
      </Tabs.Root>
    </section>
  );
}
