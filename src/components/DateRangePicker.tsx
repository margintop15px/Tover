"use client";

interface DateRangePickerProps {
  from: string;
  to: string;
  onChange: (from: string, to: string) => void;
}

export default function DateRangePicker({
  from,
  to,
  onChange,
}: DateRangePickerProps) {
  return (
    <div className="flex items-center gap-3">
      <label className="text-sm font-medium text-zinc-600 dark:text-zinc-400">
        From
      </label>
      <input
        type="date"
        value={from}
        onChange={(e) => onChange(e.target.value, to)}
        className="rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 shadow-sm dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
      />
      <label className="text-sm font-medium text-zinc-600 dark:text-zinc-400">
        To
      </label>
      <input
        type="date"
        value={to}
        onChange={(e) => onChange(from, e.target.value)}
        className="rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 shadow-sm dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
      />
    </div>
  );
}
