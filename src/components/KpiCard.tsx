"use client";

interface KpiCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  onClick?: () => void;
}

export default function KpiCard({
  title,
  value,
  subtitle,
  onClick,
}: KpiCardProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!onClick}
      className={`rounded-lg border border-zinc-200 bg-white p-6 text-left shadow-sm transition-shadow dark:border-zinc-800 dark:bg-zinc-900 ${
        onClick
          ? "cursor-pointer hover:shadow-md"
          : "cursor-default"
      }`}
    >
      <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">
        {title}
      </p>
      <p className="mt-2 text-3xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
        {value}
      </p>
      {subtitle && (
        <p className="mt-1 text-sm text-zinc-400 dark:text-zinc-500">
          {subtitle}
        </p>
      )}
    </button>
  );
}
