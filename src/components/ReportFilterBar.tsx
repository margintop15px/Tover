"use client";

interface ReportFilterBarProps {
  children: React.ReactNode;
}

export default function ReportFilterBar({ children }: ReportFilterBarProps) {
  return (
    <div className="flex flex-wrap items-end gap-3 mb-4">{children}</div>
  );
}
