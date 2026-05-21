import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";

export const dynamic = "force-dynamic";

function flattenRows(report: { rows?: Record<string, unknown>[] }) {
  return report.rows || [];
}

function csvEscape(value: unknown) {
  if (value == null) return "";
  const text = typeof value === "object" ? JSON.stringify(value) : String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function toCsv(rows: Record<string, unknown>[]) {
  const headers = [...new Set(rows.flatMap((row) => Object.keys(row)))];
  return [
    headers.map(csvEscape).join(","),
    ...rows.map((row) => headers.map((header) => csvEscape(row[header])).join(",")),
  ].join("\n");
}

function toExcelHtml(title: string, rows: Record<string, unknown>[]) {
  const headers = [...new Set(rows.flatMap((row) => Object.keys(row)))];
  const cells = rows
    .map(
      (row) =>
        `<tr>${headers.map((header) => `<td>${csvEscape(row[header])}</td>`).join("")}</tr>`
    )
    .join("");
  return `<!doctype html><html><head><meta charset="utf-8"></head><body><h1>${title}</h1><table border="1"><thead><tr>${headers.map((h) => `<th>${h}</th>`).join("")}</tr></thead><tbody>${cells}</tbody></table></body></html>`;
}

function minimalPdf(title: string, rows: Record<string, unknown>[]) {
  const text = [title, `Rows: ${rows.length}`, new Date().toISOString()]
    .join("\\n")
    .replace(/[()\\]/g, "\\$&");
  const stream = `BT /F1 12 Tf 72 740 Td (${text}) Tj ET`;
  const objects = [
    "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
    "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj",
    "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj",
    "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj",
    `5 0 obj << /Length ${stream.length} >> stream\n${stream}\nendstream endobj`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  for (const object of objects) {
    offsets.push(pdf.length);
    pdf += `${object}\n`;
  }
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets.slice(1)) {
    pdf += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer << /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return pdf;
}

export async function POST(request: NextRequest) {
  try {
    await getRouteContext(request);
    const body = await request.json();
    const format = body.format || "csv";
    const title = body.title || "Report";
    const rows = flattenRows(body.report || {});

    if (rows.length > 10000) {
      return NextResponse.json(
        { error: "Export is limited to 10000 rows" },
        { status: 413 }
      );
    }

    if (format === "xlsx") {
      return new NextResponse(toExcelHtml(title, rows), {
        headers: {
          "Content-Type": "application/vnd.ms-excel; charset=utf-8",
          "Content-Disposition": `attachment; filename="report.xls"`,
        },
      });
    }

    if (format === "pdf") {
      return new NextResponse(minimalPdf(title, rows), {
        headers: {
          "Content-Type": "application/pdf",
          "Content-Disposition": `attachment; filename="report.pdf"`,
        },
      });
    }

    return new NextResponse(toCsv(rows), {
      headers: {
        "Content-Type": "text/csv; charset=utf-8",
        "Content-Disposition": `attachment; filename="report.csv"`,
      },
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}
