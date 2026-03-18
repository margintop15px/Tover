"use client";

import { useEffect, useState } from "react";
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
  header: string;
  render?: (item: T) => React.ReactNode;
  className?: string;
  required?: boolean;
  defaultVisible?: boolean;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  onRowClick?: (item: T) => void;
  emptyMessage?: string;
  tableId?: string;
}

function getInitialVisibility<T>(columns: Column<T>[], tableId?: string): Set<string> {
  if (tableId) {
    try {
      const stored = localStorage.getItem(`tover-columns-${tableId}`);
      if (stored) {
        const parsed = JSON.parse(stored) as string[];
        // Always include required columns
        const set = new Set(parsed);
        for (const col of columns) {
          if (col.required) set.add(col.key);
        }
        return set;
      }
    } catch {
      // ignore
    }
  }
  const set = new Set<string>();
  for (const col of columns) {
    if (col.required || col.defaultVisible) {
      set.add(col.key);
    }
  }
  return set;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export default function DataTable<T extends Record<string, any>>({
  columns,
  data,
  onRowClick,
  emptyMessage = "No data",
  tableId,
}: DataTableProps<T>) {
  const { t } = useI18n();
  const hasVisibilityControl = columns.some((col) => !col.required && col.key !== "actions");
  const allRequired = !hasVisibilityControl;

  const [visibleKeys, setVisibleKeys] = useState<Set<string>>(() => {
    if (allRequired) return new Set(columns.map((c) => c.key));
    return getInitialVisibility(columns, tableId);
  });

  useEffect(() => {
    if (tableId && !allRequired) {
      localStorage.setItem(
        `tover-columns-${tableId}`,
        JSON.stringify([...visibleKeys])
      );
    }
  }, [visibleKeys, tableId, allRequired]);

  const toggleColumn = (key: string) => {
    setVisibleKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const visibleColumns = allRequired
    ? columns
    : columns.filter((col) => col.required || col.key === "actions" || visibleKeys.has(col.key));

  const toggleableColumns = columns.filter((col) => !col.required && col.key !== "actions");

  if (data.length === 0) {
    return (
      <div>
        {!allRequired && (
          <div className="mb-2 flex justify-end">
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
                    checked={visibleKeys.has(col.key)}
                    onCheckedChange={() => toggleColumn(col.key)}
                  >
                    {col.header}
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        )}
        <div className="rounded-lg border bg-card p-8 text-center text-sm text-muted-foreground">
          {emptyMessage}
        </div>
      </div>
    );
  }

  return (
    <div>
      {!allRequired && (
        <div className="mb-2 flex justify-end">
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
                  checked={visibleKeys.has(col.key)}
                  onCheckedChange={() => toggleColumn(col.key)}
                >
                  {col.header}
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      )}
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
