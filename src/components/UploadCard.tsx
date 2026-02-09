"use client";

import { useState } from "react";
import { Upload } from "lucide-react";
import { useI18n } from "@/i18n/context";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";

interface UploadResult {
  importId: string;
  status: string;
  summary?: { totalRows: number; inserted: number; errors: number };
  error?: string;
}

export default function UploadCard({
  onImportComplete,
}: {
  onImportComplete?: () => void;
}) {
  const { t } = useI18n();
  const [file, setFile] = useState<File | null>(null);
  const [importType, setImportType] = useState("orders_csv");
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<UploadResult | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!file) return;

    setUploading(true);
    setResult(null);

    const formData = new FormData();
    formData.append("file", file);
    formData.append("import_type", importType);

    try {
      const res = await fetch("/api/imports", {
        method: "POST",
        body: formData,
      });
      const data = await res.json();
      setResult(data);
      if (data.status === "completed") {
        onImportComplete?.();
      }
    } catch {
      setResult({ importId: "", status: "error", error: t.uploadFailed });
    } finally {
      setUploading(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm">
          <Upload className="size-4" />
          {t.importCsv}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label>{t.importType}</Label>
            <Select value={importType} onValueChange={setImportType}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="orders_csv">
                  {t.importTypeOrders}
                </SelectItem>
                <SelectItem value="order_lines_csv">
                  {t.importTypeOrderLines}
                </SelectItem>
                <SelectItem value="inventory_csv">
                  {t.importTypeInventory}
                </SelectItem>
                <SelectItem value="payments_csv">
                  {t.importTypePayments}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label>{t.csvFile}</Label>
            <Input
              type="file"
              accept=".csv"
              onChange={(e) => setFile(e.target.files?.[0] || null)}
            />
          </div>
          <Button type="submit" disabled={!file || uploading} className="w-full">
            <Upload className="size-4" />
            {uploading ? t.uploading : t.uploadAndImport}
          </Button>
        </form>

        {result && (
          <div className="mt-4">
            {result.error ? (
              <Badge variant="destructive" className="w-full justify-center py-2">
                {result.error}
              </Badge>
            ) : result.summary ? (
              <div className="rounded-md bg-green-50 p-3 text-sm text-green-800 dark:bg-green-950 dark:text-green-200">
                {t.importedOf(result.summary.inserted, result.summary.totalRows)}{" "}
                {result.summary.errors > 0 &&
                  t.errorsCount(result.summary.errors)}
              </div>
            ) : (
              <Badge variant="secondary" className="w-full justify-center py-2">
                {t.importStatus(result.status)}
              </Badge>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
