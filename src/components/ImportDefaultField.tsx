"use client";

import { useId } from "react";
import { useI18n } from "@/i18n/context";

interface ImportDefaultFieldProps {
  checked: boolean;
  entityLabel: string;
  onCheckedChange: (checked: boolean) => void;
}

export default function ImportDefaultField({
  checked,
  entityLabel,
  onCheckedChange,
}: ImportDefaultFieldProps) {
  const { t } = useI18n();
  const id = useId();

  return (
    <div className="rounded-md border bg-muted/30 p-3">
      <div className="flex items-start gap-3">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          onChange={(event) => onCheckedChange(event.target.checked)}
          className="mt-0.5 h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
        <div className="space-y-1">
          <label htmlFor={id} className="text-sm font-medium leading-none">
            {t.importDefaultLabel}
          </label>
          <p className="text-sm text-muted-foreground">
            {t.importDefaultHelp(entityLabel)}
          </p>
        </div>
      </div>
    </div>
  );
}
