"use client";

import * as React from "react";
import { Check } from "lucide-react";

import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface ComboboxContextValue<T> {
  items: T[];
  value: T | null;
  query: string;
  setQuery: (query: string) => void;
  itemToStringValue: (item: T) => string;
  filteredItems: T[];
  isSelected: (item: T) => boolean;
  selectItem: (item: T) => void;
  listboxId: string;
}

const ComboboxContext = React.createContext<ComboboxContextValue<unknown> | null>(
  null
);

function useComboboxContext<T>() {
  const context = React.useContext(ComboboxContext);
  if (!context) {
    throw new Error("Combobox components must be used inside Combobox");
  }
  return context as ComboboxContextValue<T>;
}

interface ComboboxProps<T> {
  items: T[];
  value?: T | null;
  onValueChange?: (value: T) => void;
  itemToStringValue?: (item: T) => string;
  children: React.ReactNode;
}

function Combobox<T>({
  items,
  value = null,
  onValueChange,
  itemToStringValue,
  children,
}: ComboboxProps<T>) {
  const [query, setQuery] = React.useState("");
  const listboxId = React.useId();
  const stringify = React.useCallback(
    (item: T) => itemToStringValue?.(item) ?? String(item),
    [itemToStringValue]
  );
  const normalizedQuery = query.trim().toLowerCase();
  const filteredItems = React.useMemo(
    () =>
      normalizedQuery
        ? items.filter((item) =>
            stringify(item).toLowerCase().includes(normalizedQuery)
          )
        : items,
    [items, normalizedQuery, stringify]
  );

  const context = React.useMemo<ComboboxContextValue<T>>(
    () => ({
      items,
      value,
      query,
      setQuery,
      itemToStringValue: stringify,
      filteredItems,
      isSelected: (item) =>
        value != null && stringify(item) === stringify(value),
      selectItem: (item) => onValueChange?.(item),
      listboxId,
    }),
    [items, value, query, stringify, filteredItems, onValueChange, listboxId]
  );

  return (
    <ComboboxContext.Provider value={context as ComboboxContextValue<unknown>}>
      <div className="space-y-2">{children}</div>
    </ComboboxContext.Provider>
  );
}

function ComboboxInput({
  className,
  ...props
}: React.ComponentProps<typeof Input>) {
  const { query, setQuery, listboxId } = useComboboxContext<unknown>();

  return (
    <Input
      autoFocus
      role="combobox"
      aria-expanded="true"
      aria-controls={listboxId}
      value={query}
      onChange={(event) => setQuery(event.target.value)}
      className={cn("h-8 text-xs", className)}
      {...props}
    />
  );
}

function ComboboxContent({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return <div className={cn("space-y-1", className)} {...props} />;
}

function ComboboxEmpty({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const { filteredItems } = useComboboxContext<unknown>();

  if (filteredItems.length > 0) return null;

  return (
    <div
      className={cn("px-2 py-3 text-sm text-muted-foreground", className)}
      data-slot="combobox-empty"
      {...props}
    />
  );
}

function ComboboxList<T>({
  children,
  className,
}: {
  children: (item: T) => React.ReactNode;
  className?: string;
}) {
  const { filteredItems, listboxId } = useComboboxContext<T>();

  return (
    <div
      id={listboxId}
      role="listbox"
      className={cn("max-h-56 overflow-y-auto", className)}
    >
      {filteredItems.map((item) => children(item))}
    </div>
  );
}

function ComboboxItem<T>({
  value,
  children,
  className,
  ...props
}: Omit<React.ComponentProps<"button">, "value"> & {
  value: T;
}) {
  const { isSelected, selectItem } = useComboboxContext<T>();
  const selected = isSelected(value);

  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      className={cn(
        "flex w-full items-center justify-between gap-2 rounded-sm px-2 py-1.5 text-left text-sm outline-hidden hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground",
        selected && "font-medium",
        className
      )}
      onClick={() => selectItem(value)}
      {...props}
    >
      <span className="truncate">{children}</span>
      {selected && <Check className="h-4 w-4 shrink-0" />}
    </button>
  );
}

export {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
};
