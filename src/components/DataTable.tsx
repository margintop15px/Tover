"use client";

import { useState } from "react";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/context";
import { cn } from "@/lib/utils";
import { SlidersHorizontal } from "lucide-react";

interface Column<T> {
  key: string;
  header: React.ReactNode;
  headerLabel?: string;
  render?: (item: T) => React.ReactNode;
  className?: string;
  required?: boolean;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  onRowClick?: (item: T) => void;
  emptyMessage?: string;
  tableId?: string;
  toolbarActions?: React.ReactNode;
}

const COLUMN_VISIBILITY_VERSION = 2;

function isToggleableColumn<T>(col: Column<T>) {
  return !col.required && col.key !== "actions";
}

function getStorageKey(tableId: string) {
  return `tover-columns-${tableId}`;
}

function getInitialHiddenKeys<T>(columns: Column<T>[], tableId?: string): Set<string> {
  if (tableId) {
    try {
      const stored = localStorage.getItem(getStorageKey(tableId));
      if (stored) {
        const parsed = JSON.parse(stored) as {
          version?: number;
          hidden?: unknown;
        };

        if (
          parsed.version === COLUMN_VISIBILITY_VERSION &&
          Array.isArray(parsed.hidden)
        ) {
          const toggleableKeys = new Set(
            columns.filter(isToggleableColumn).map((col) => col.key)
          );
          return new Set(
            parsed.hidden.filter(
              (key): key is string =>
                typeof key === "string" && toggleableKeys.has(key)
            )
          );
        }
      }
    } catch {
      // ignore
    }
  }
  return new Set<string>();
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export default function DataTable<T extends Record<string, any>>({
  columns,
  data,
  onRowClick,
  emptyMessage = "No data",
  tableId,
  toolbarActions,
}: DataTableProps<T>) {
  const { t } = useI18n();
  const hasVisibilityControl = columns.some(isToggleableColumn);
  const allRequired = !hasVisibilityControl;

  const [hiddenKeys, setHiddenKeys] = useState<Set<string>>(() => {
    if (allRequired) return new Set<string>();
    return getInitialHiddenKeys(columns, tableId);
  });

  const persistHiddenKeys = (keys: Set<string>) => {
    if (!tableId || allRequired) return;
    localStorage.setItem(
      getStorageKey(tableId),
      JSON.stringify({
        version: COLUMN_VISIBILITY_VERSION,
        hidden: [...keys],
      })
    );
  };

  const toggleColumn = (key: string) => {
    setHiddenKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      persistHiddenKeys(next);
      return next;
    });
  };

  const visibleColumns = allRequired
    ? columns
    : columns.filter(
        (col) =>
          col.required || col.key === "actions" || !hiddenKeys.has(col.key)
      );

  const toggleableColumns = columns.filter(isToggleableColumn);

  const isColumnVisible = (key: string) => !hiddenKeys.has(key);

  const toolbar = (toolbarActions || !allRequired) && (
    <div className="mb-2 flex justify-end gap-2">
      {toolbarActions}
      {!allRequired && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm" className="gap-2">
              <SlidersHorizontal className="h-4 w-4" />
              {t.columns}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {toggleableColumns.map((col) => (
              <DropdownMenuCheckboxItem
                key={col.key}
                checked={isColumnVisible(col.key)}
                onSelect={(event) => {
                  event.preventDefault();
                  toggleColumn(col.key);
                }}
              >
                {col.headerLabel ?? col.header}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );

  if (data.length === 0) {
    return (
      <div>
        {toolbar}
        <div className="rounded-lg border bg-card p-8 text-center text-sm text-muted-foreground">
          {emptyMessage}
        </div>
      </div>
    );
  }

  return (
    <div>
      {toolbar}
      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              {visibleColumns.map((col) => (
                <TableHead key={col.key} className={col.className}>
                  {col.header}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((item, i) => (
              <TableRow
                key={i}
                onClick={() => onRowClick?.(item)}
                className={cn(onRowClick && "cursor-pointer")}
              >
                {visibleColumns.map((col) => (
                  <TableCell key={col.key} className={col.className}>
                    {col.render
                      ? col.render(item)
                      : String(item[col.key] ?? "")}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
