import client from "./client";
import type { ChurnReport, RevenueRange, RevenueSummary, RevenueTrendPoint } from "@/types/revenue";

export async function getRevenueSummary(range: RevenueRange): Promise<RevenueSummary> {
  const res = await client.get<{ data: RevenueSummary }>("/admin/revenue/summary", {
    params: { from: range.from, to: range.to },
  });
  return res.data.data;
}

export async function getRevenueTrend(): Promise<RevenueTrendPoint[]> {
  const res = await client.get<{ data: RevenueTrendPoint[] }>("/admin/revenue/trend");
  return res.data.data;
}

export async function getChurnReport(month: string): Promise<ChurnReport> {
  const res = await client.get<{ data: ChurnReport }>("/admin/revenue/churn", { params: { month } });
  return res.data.data;
}

/** `keevo-churn-YYYY-MM.csv` — same name as the backend's Content-Disposition. */
export function churnCsvFilename(month: string): string {
  return `keevo-churn-${month}.csv`;
}

/**
 * Downloads the churn CSV of a month. Goes through the authenticated axios client (the JWT lives in
 * memory, a plain link would not carry it), then saves the blob under {@link churnCsvFilename}.
 */
export async function downloadChurnCsv(month: string): Promise<void> {
  const res = await client.get<Blob>("/admin/revenue/churn/export", {
    params: { month },
    responseType: "blob",
  });
  const url = URL.createObjectURL(res.data);
  const link = document.createElement("a");
  link.href = url;
  link.download = churnCsvFilename(month);
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoking synchronously can cancel the download in some browsers: let it start first.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
