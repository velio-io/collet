import { useEffect, useRef, useState } from "react";
import { Button, Select } from "@radix-ui/themes";
import { api } from "./api";

export default function DiagnosticsPanel({ path, task, action, view, revision }) {
  const [level, setLevel] = useState("info");
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [refresh, setRefresh] = useState(0);
  const [pagedRevision, setPagedRevision] = useState(null);
  const pending = useRef(null);
  const query = new URLSearchParams({ view, level });
  if (task) query.set("task-name", task);
  if (action) query.set("action-name", action);
  const endpoint = path && `${path}?${query}`;
  const liveRevision = pagedRevision ?? revision;
  useEffect(() => {
    setData(null);
    setError(null);
  }, [endpoint]);
  useEffect(() => {
    if (!endpoint) return;
    const request = new AbortController();
    pending.current = request;
    api(endpoint, undefined, { signal: request.signal })
      .then((v) => {
        if (request.signal.aborted) return;
        setData(v);
        setError(null);
      })
      .catch((e) => {
        if (!request.signal.aborted) setError(e);
      });
    return () => pending.current?.abort();
  }, [endpoint, refresh, liveRevision]);
  const more = async () => {
    // Keep the current pages visible until the reader explicitly refreshes.
    setPagedRevision(liveRevision);
    pending.current?.abort();
    const request = new AbortController();
    pending.current = request;
    try {
      const next = await api(
        `${endpoint}&cursor=${encodeURIComponent(data.nextCursor)}`,
        undefined,
        { signal: request.signal },
      );
      if (!request.signal.aborted)
        setData((old) => ({ ...next, events: [...old.events, ...next.events] }));
    } catch (e) {
      if (!request.signal.aborted) setError(e);
    }
  };
  const download = async () => {
    try {
      let page = await api(`${endpoint}&limit=100`);
      const events = [...page.events];
      while (page.hasMore) {
        page = await api(
          `${endpoint}&limit=100&cursor=${encodeURIComponent(page.nextCursor)}`,
        );
        events.push(...page.events);
      }
      const content =
        [{ retention: page.retention, dropped: page.dropped }, ...events]
          .map((x) => JSON.stringify(x))
          .join("\n") + "\n";
      const url = URL.createObjectURL(
        new Blob([content], { type: "application/x-ndjson" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = "collet-events.ndjson";
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e);
    }
  };
  if (!path)
    return (
      <div className="empty-state">
        Run this task to inspect its logs and metrics.
      </div>
    );
  return (
    <div className="diagnostics-panel">
      <div className="data-toolbar">
        {view === "events" && (
          <Select.Root
            value={level}
            onValueChange={(value) => {
              setPagedRevision(null);
              setLevel(value);
            }}
          >
            <Select.Trigger aria-label="Minimum log level" />
            <Select.Content>
              {["debug", "info", "warn", "error"].map((x) => (
                <Select.Item key={x} value={x}>
                  {x}
                </Select.Item>
              ))}
            </Select.Content>
          </Select.Root>
        )}
        <Button
          size="1"
          variant="soft"
          onClick={() => {
            setPagedRevision(null);
            setRefresh((x) => x + 1);
          }}
        >
          Refresh
        </Button>
        {view === "events" && (
          <Button size="1" variant="soft" onClick={download}>
            Export retained logs
          </Button>
        )}
      </div>
      {error && <p role="alert">{error.message}</p>}
      {!data && !error && <p>Loading…</p>}
      {view === "metrics" && data && (
        <>
          <p>
            Times in milliseconds. Run and task totals persist. Action metrics
            can arrive late or miss events under load and end on restart.
          </p>
          <pre>{JSON.stringify(data.metrics, null, 2)}</pre>
        </>
      )}
      {view === "events" && data && (
        <>
          <p>
            {data.retention}
            {data.dropped > 0 && ` ${data.dropped} earlier events dropped.`}
          </p>
          {data.gap && (
            <p role="status">
              Earlier events are no longer retained. Durable run summaries
              remain available.
            </p>
          )}
          {data.events.map((event) => (
            <div className="event" key={event.sequence}>
              <time>{new Date(event.timestamp).toLocaleTimeString()}</time>
              <div>
                <strong>{event.event}</strong>
                <p>
                  {event.action || event.task}{" "}
                  {event.durationMs != null &&
                    `· ${event.durationMs.toFixed(1)} ms`}
                </p>
                <details>
                  <summary>Details</summary>
                  <pre>{JSON.stringify(event, null, 2)}</pre>
                </details>
              </div>
            </div>
          ))}
          {!data.events.length && (
            <p>{data.reason || "No events match this selection."}</p>
          )}
          {data.hasMore && <Button onClick={more}>More events</Button>}
        </>
      )}
    </div>
  );
}
