"use client";

import { Suspense } from "react";
import { useParams } from "next/navigation";
import ReportTemplateForm from "@/components/reports/ReportTemplateForm";

export default function EditReportTemplatePage() {
  return (
    <Suspense fallback={<div className="p-6 text-muted-foreground">Loading...</div>}>
      <EditReportTemplatePageContent />
    </Suspense>
  );
}

function EditReportTemplatePageContent() {
  const params = useParams<{ id: string }>();
  return <ReportTemplateForm templateId={params.id} />;
}
