"use client";

import { useState } from "react";
import { useI18n } from "@/i18n/context";

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
    <div className="rounded-lg border border-zinc-200 bg-white p-6 shadow-sm dark:border-zinc-800 dark:bg-zinc-900">
      <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-50">
        {t.importCsv}
      </h3>
      <form onSubmit={handleSubmit} className="mt-4 space-y-4">
        <div>
          <label className="block text-sm font-medium text-zinc-600 dark:text-zinc-400">
            {t.importType}
          </label>
          <select
            value={importType}
            onChange={(e) => setImportType(e.target.value)}
            className="mt-1 w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
          >
            <option value="orders_csv">{t.importTypeOrders}</option>
            <option value="order_lines_csv">{t.importTypeOrderLines}</option>
            <option value="inventory_csv">{t.importTypeInventory}</option>
            <option value="payments_csv">{t.importTypePayments}</option>
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-zinc-600 dark:text-zinc-400">
            {t.csvFile}
          </label>
          <input
            type="file"
            accept=".csv"
            onChange={(e) => setFile(e.target.files?.[0] || null)}
            className="mt-1 block w-full text-sm text-zinc-500 file:mr-4 file:rounded-md file:border-0 file:bg-zinc-100 file:px-4 file:py-2 file:text-sm file:font-medium file:text-zinc-700 hover:file:bg-zinc-200 dark:text-zinc-400 dark:file:bg-zinc-800 dark:file:text-zinc-300"
          />
        </div>
        <button
          type="submit"
          disabled={!file || uploading}
          className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {uploading ? t.uploading : t.uploadAndImport}
        </button>
      </form>

      {result && (
        <div
          className={`mt-4 rounded-md p-3 text-sm ${
            result.error || result.status === "failed"
              ? "bg-red-50 text-red-800 dark:bg-red-950 dark:text-red-200"
              : "bg-green-50 text-green-800 dark:bg-green-950 dark:text-green-200"
          }`}
        >
          {result.error ? (
            <p>{result.error}</p>
          ) : result.summary ? (
            <p>
              {t.importedOf(result.summary.inserted, result.summary.totalRows)}{" "}
              {result.summary.errors > 0 &&
                t.errorsCount(result.summary.errors)}
            </p>
          ) : (
            <p>{t.importStatus(result.status)}</p>
          )}
        </div>
      )}
    </div>
  );
}
