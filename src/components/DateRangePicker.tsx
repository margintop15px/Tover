"use client";

import { useI18n } from "@/i18n/context";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

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
  const { t } = useI18n();

  return (
    <div className="flex items-center gap-3">
      <Label className="text-muted-foreground">{t.from}</Label>
      <Input
        type="date"
        value={from}
        onChange={(e) => onChange(e.target.value, to)}
        className="w-auto"
      />
      <Label className="text-muted-foreground">{t.to}</Label>
      <Input
        type="date"
        value={to}
        onChange={(e) => onChange(from, e.target.value)}
        className="w-auto"
      />
    </div>
  );
}
