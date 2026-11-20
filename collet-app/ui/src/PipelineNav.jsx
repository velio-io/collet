import { useEffect, useMemo, useState } from "react";
import { Dialog, TextField } from "@radix-ui/themes";
import { Search, ChevronRight } from "lucide-react";

export default function PipelineNav({ open, onOpenChange, title, entries }) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  useEffect(() => {
    if (open) {
      setQuery("");
      setActive(0);
    }
  }, [open, title]);
  const filtered = useMemo(
    () =>
      entries.filter((entry) =>
        query
          .toLowerCase()
          .split(/\s+/)
          .every((word) =>
            `${entry.label} ${entry.detail || ""} ${entry.group || ""}`
              .toLowerCase()
              .includes(word),
          ),
      ),
    [entries, query],
  );
  const choose = (entry) => {
    if (!entry || entry.disabled) return;
    onOpenChange(false);
    entry.run();
  };
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Content className="command-menu" maxWidth="560px">
        <Dialog.Title className="sr-only">{title}</Dialog.Title>
        <Dialog.Description className="sr-only">
          Find a pipeline, working draft, or Workbench command.
        </Dialog.Description>
        <TextField.Root
          autoFocus
          value={query}
          placeholder={title}
          aria-label={title}
          role="combobox"
          aria-expanded="true"
          aria-controls="command-options"
          aria-activedescendant={
            filtered[active] ? `command-${active}` : undefined
          }
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
          }}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              const next =
                (active +
                  (event.key === "ArrowDown" ? 1 : -1) +
                  filtered.length) %
                Math.max(1, filtered.length);
              setActive(next);
              document
                .getElementById(`command-${next}`)
                ?.scrollIntoView({ block: "nearest" });
            }
            if (event.key === "Enter") {
              event.preventDefault();
              choose(filtered[active]);
            }
          }}
        >
          <TextField.Slot>
            <Search size={16} />
          </TextField.Slot>
          <TextField.Slot>
            <kbd>Esc</kbd>
          </TextField.Slot>
        </TextField.Root>
        <div
          className="command-results"
          id="command-options"
          role="listbox"
          aria-label="Commands"
        >
          {filtered.map((entry, index) => (
            <div key={`${entry.group}-${entry.label}`}>
              {entry.group &&
                (index === 0 || filtered[index - 1].group !== entry.group) && (
                  <h2>{entry.group}</h2>
                )}
              <button
                role="option"
                id={`command-${index}`}
                aria-selected={active === index}
                aria-disabled={entry.disabled || undefined}
                disabled={entry.disabled}
                tabIndex={-1}
                className={active === index ? "active" : ""}
                onMouseMove={() => setActive(index)}
                onClick={() => choose(entry)}
              >
                <span>
                  <strong>{entry.label}</strong>
                  {entry.detail && <small>{entry.detail}</small>}
                </span>
                <ChevronRight size={14} />
              </button>
            </div>
          ))}
          {!filtered.length && (
            <p className="empty-state">No matching commands</p>
          )}
        </div>
        <footer>
          <span>↑ ↓ navigate · Enter select</span>
          <span>⌘K commands</span>
        </footer>
      </Dialog.Content>
    </Dialog.Root>
  );
}
