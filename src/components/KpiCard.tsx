"use client";

import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

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
    <Card
      className={cn(
        "gap-2 py-5 transition-shadow",
        onClick && "cursor-pointer hover:shadow-md"
      )}
      onClick={onClick}
    >
      <CardContent className="space-y-1">
        <p className="text-sm font-medium text-muted-foreground">{title}</p>
        <p className="text-3xl font-bold tracking-tight">{value}</p>
        {subtitle && (
          <p className="text-sm text-muted-foreground/70">{subtitle}</p>
        )}
      </CardContent>
    </Card>
  );
}
