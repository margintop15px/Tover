"use client";

import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/context";

interface PaginationProps {
  offset: number;
  limit: number;
  total: number | null;
  onPageChange: (newOffset: number) => void;
}

export default function Pagination({
  offset,
  limit,
  total,
  onPageChange,
}: PaginationProps) {
  const { t } = useI18n();
  const currentPage = Math.floor(offset / limit) + 1;
  const totalPages = total != null ? Math.max(1, Math.ceil(total / limit)) : null;
  const hasPrev = offset > 0;
  const hasNext = total != null ? offset + limit < total : false;

  return (
    <div className="flex items-center justify-between pt-4">
      <Button
        variant="outline"
        size="sm"
        disabled={!hasPrev}
        onClick={() => onPageChange(Math.max(0, offset - limit))}
      >
        {t.previousPage}
      </Button>
      <span className="text-sm text-muted-foreground">
        {totalPages != null ? t.pageInfo(currentPage, totalPages) : ""}
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={!hasNext}
        onClick={() => onPageChange(offset + limit)}
      >
        {t.nextPage}
      </Button>
    </div>
  );
}
