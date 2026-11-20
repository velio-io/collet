import { useMemo, useState, useCallback } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  Handle,
  Position,
  MarkerType,
} from "@xyflow/react";
import { Button, IconButton, Tooltip } from "@radix-ui/themes";
import {
  Check,
  Circle,
  ChevronDown,
  ChevronUp,
  Plus,
  AlertCircle,
  LoaderCircle,
  Braces,
} from "lucide-react";
import { title } from "./api";
import "@xyflow/react/dist/style.css";

const statusLabels = {
  completed: "Completed",
  done: "Completed",
  failed: "Failed",
  running: "Running",
  partial: "Partial",
  stale: "Stale",
  paused: "Paused",
  skipped: "Skipped",
  pending: "Pending",
  stopped: "Stopped",
  interrupted: "Interrupted",
  waiting: "Not run",
};
export function Status({ status = "waiting", compact = false }) {
  const Icon = ["completed", "done"].includes(status)
    ? Check
    : status === "failed"
      ? AlertCircle
      : status === "running"
        ? LoaderCircle
        : Circle;
  return (
    <span
      className={`status ${status}`}
      aria-label={statusLabels[status] || status}
      title={statusLabels[status] || status}
    >
      <Icon size={14} className={status === "running" ? "spin" : ""} />
      {!compact && <span>{statusLabels[status] || status}</span>}
    </span>
  );
}
function TaskNode({ data, selected }) {
  const { task, state, expanded, selection, onSelect, onExpand } = data;
  return (
    <div
      className={`task-node ${selected ? "selected" : ""} ${state?.status === "failed" ? "failed" : ""}`}
    >
      <Handle type="target" position={Position.Left} />
      <div className="task-heading">
        <Status compact status={state?.status} />
        <button
          id={`open-task-${task.name}`}
          className="task-name nodrag"
          onClick={(e) => {
            e.stopPropagation();
            onSelect(task.name, null);
          }}
        >
          <span>{title(task.name)}</span>
        </button>
        <Tooltip content={expanded ? "Collapse actions" : "Show actions"}>
          <IconButton
            className="nodrag"
            variant="ghost"
            color="gray"
            size="1"
            aria-label={`${expanded ? "Collapse" : "Expand"} ${task.name}`}
            onClick={(e) => {
              e.stopPropagation();
              onExpand(task.name);
            }}
          >
            {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
          </IconButton>
        </Tooltip>
      </div>
      <div className="task-summary">
        <div
          className="action-progress"
          aria-label={`${task.actions.length} ${task.actions.length === 1 ? "action" : "actions"}`}
        >
          {task.actions.map((action) => (
            <button
              key={action.name}
              className={`nodrag ${state?.status === "stale" ? "stale" : state?.actions?.[action.name]?.status || "waiting"}`}
              aria-label={`Open action ${action.name}`}
              title={`${action.name} · ${state?.actions?.[action.name]?.status || "Not run"}`}
              onClick={(event) => {
                event.stopPropagation();
                onSelect(task.name, action.name);
              }}
            />
          ))}
        </div>
        <span>
          {state?.preview?.count != null
            ? `${state.preview.count} rows`
            : state?.status === "stale"
              ? "stale"
              : state?.status === "running"
                ? "running"
                : `${task.actions.length} ${task.actions.length === 1 ? "action" : "actions"}`}
        </span>
      </div>
      {expanded && (
        <div className="graph-action-list">
          {task.actions.map((action) => (
            <button
              key={`${action.index}-${action.name}`}
              className={`graph-action nodrag ${selection.task === task.name && selection.action === action.name ? "active" : ""}`}
              onClick={(e) => {
                e.stopPropagation();
                onSelect(task.name, action.name);
              }}
            >
              <Status
                compact
                status={
                  state?.status === "stale"
                    ? "stale"
                    : state?.actions?.[action.name]?.status
                }
              />
              <span>{action.name}</span>
            </button>
          ))}
        </div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
const nodeTypes = { task: TaskNode };
export default function Graph({
  document,
  states,
  selection,
  onSelect,
  onOpen,
  expanded,
  onExpand,
  edit,
  build,
  disabled,
  view,
  onViewChange,
}) {
  const [geometry, setGeometry] = useState(view?.geometry || {});
  const [viewport, setViewport] = useState(view?.viewport);
  const levels = useMemo(() => {
    const result = {};
    function level(task, visiting = new Set()) {
      if (result[task.name] !== undefined) return result[task.name];
      if (visiting.has(task.name)) return 0;
      return (result[task.name] = Math.max(
        0,
        ...task.inputs.map((name) => {
          const parent = document.tasks.find((t) => t.name === name);
          return parent
            ? level(parent, new Set([...visiting, task.name])) + 1
            : 0;
        }),
      ));
    }
    document.tasks.forEach((t) => level(t));
    return result;
  }, [document]);
  const nodes = useMemo(
    () =>
      document.tasks.map((task) => {
        const siblings = document.tasks.filter(
          (t) => levels[t.name] === levels[task.name],
        );
        return {
          id: task.name,
          type: "task",
          style: { width: 184 },
          deletable: false,
          selected: selection.task === task.name,
          measured: geometry[task.name]?.measured,
          position: geometry[task.name]?.position || {
            x: 40 + levels[task.name] * 236,
            y: 250 + (siblings.indexOf(task) - (siblings.length - 1) / 2) * 180,
          },
          data: {
            task,
            state: states[task.name],
            expanded: expanded.has(task.name),
            selection,
            onSelect,
            onExpand,
          },
        };
      }),
    [
      document,
      levels,
      geometry,
      states,
      expanded,
      selection,
      onSelect,
      onExpand,
    ],
  );
  const edges = document.tasks.flatMap((task) =>
    task.inputs.map((input) => ({
      id: `${input}->${task.name}`,
      source: input,
      target: task.name,
      type: "smoothstep",
      style: {
        stroke: "var(--gray-8)",
        strokeWidth: 1.5,
        strokeDasharray:
          states[task.name]?.status === "stale" ? "5 4" : undefined,
      },
      animated: states[task.name]?.status === "running",
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: "#80838d",
        width: 18,
        height: 18,
      },
    })),
  );
  const onNodesChange = useCallback((changes) => {
    const updates = changes.filter(
      (c) => c.type === "position" || c.type === "dimensions",
    );
    if (!updates.length) return;
    setGeometry((previous) => {
      const next = { ...previous };
      for (const change of updates)
        next[change.id] = {
          ...next[change.id],
          ...(change.position ? { position: change.position } : {}),
          ...(change.dimensions ? { measured: change.dimensions } : {}),
        };
      return next;
    });
  }, []);
  return (
    <div className="flow-frame">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        colorMode="dark"
        onNodesChange={onNodesChange}
        fitView={!view?.viewport}
        defaultViewport={viewport}
        fitViewOptions={{ padding: 0.08, maxZoom: 1 }}
        minZoom={0.3}
        maxZoom={1.5}
        onMoveEnd={(_, next) => {
          setViewport(next);
          onViewChange({ viewport: next, geometry });
        }}
        onNodeDragStop={(_, node) =>
          onViewChange({
            viewport,
            geometry: {
              ...geometry,
              [node.id]: { ...geometry[node.id], position: node.position },
            },
          })
        }
        onNodeClick={(_, node) => onSelect(node.id, null)}
        onPaneClick={() => onSelect(null, null)}
        onNodeDoubleClick={(_, node) => onOpen(node.id, null)}
        onConnect={(connection) => edit({ op: "connect", ...connection })}
        onEdgesDelete={async (deleted) => {
          for (const edge of deleted) {
            const result = await edit({
              op: "disconnect",
              source: edge.source,
              target: edge.target,
            });
            if (!result) break;
          }
        }}
        nodesConnectable={build && !disabled}
        edgesReconnectable={false}
        elementsSelectable
        nodesDraggable
        deleteKeyCode={build && !disabled ? "Backspace" : null}
        proOptions={{ hideAttribution: false }}
        ariaLabelConfig={{ "controls.fitView.ariaLabel": "Fit pipeline graph" }}
      >
        <Background color="#2a2c32" gap={18} size={1.5} />
        <Controls showInteractive={false} />
        {!document.tasks.length && (
          <div className="graph-empty">
            <Braces size={30} />
            <h2>Your pipeline starts here</h2>
            <p>Add a task, then develop its actions one step at a time.</p>
            {build && (
              <Button
                onClick={() => edit({ op: "add-task" })}
                disabled={disabled}
              >
                <Plus size={16} />
                Add first task
              </Button>
            )}
          </div>
        )}
      </ReactFlow>
    </div>
  );
}
