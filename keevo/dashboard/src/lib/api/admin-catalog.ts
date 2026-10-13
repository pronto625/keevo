import client from "./client";
import { todayInDouala } from "@/lib/period";
import type {
  AdminCatalogSummary,
  AdminProductPage,
  ProductFilters,
} from "@/types/catalog";

export async function getCatalogSummary(): Promise<AdminCatalogSummary> {
  const res = await client.get<{ data: AdminCatalogSummary }>(
    "/admin/catalog/summary"
  );
  return res.data.data;
}

export async function getProducts(
  filters: ProductFilters,
  page: number
): Promise<AdminProductPage> {
  const params: Record<string, string | number> = { page, pageSize: 25 };
  if (filters.search) params.search = filters.search;
  if (filters.tenantId) params.tenantId = filters.tenantId;
  if (filters.stockLevel !== "ALL") params.stockLevel = filters.stockLevel;
  if (filters.status !== "ALL") params.status = filters.status;
  if (filters.plan !== "ALL") params.plan = filters.plan;
  const res = await client.get<{ data: AdminProductPage }>(
    "/admin/catalog/products",
    { params }
  );
  return res.data.data;
}

/** Query params of the products export (same filters as the table). */
export function exportParams(filters: ProductFilters): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.search) params.search = filters.search;
  if (filters.tenantId) params.tenantId = filters.tenantId;
  if (filters.stockLevel !== "ALL") params.stockLevel = filters.stockLevel;
  if (filters.status !== "ALL") params.status = filters.status;
  if (filters.plan !== "ALL") params.plan = filters.plan;
  return params;
}

/** `keevo-products-YYYY-MM-DD.csv`, the day in Africa/Douala — same name as the backend's Content-Disposition. */
export function productsCsvFilename(now: Date = new Date()): string {
  return `keevo-products-${todayInDouala(now)}.csv`;
}

/**
 * Downloads the products CSV through the authenticated axios client (the admin JWT lives in
 * memory / sessionStorage: a plain link would get a 401), then saves the blob — like the 9-3
 * churn export.
 */
export async function downloadProductsCsv(filters: ProductFilters): Promise<void> {
  const res = await client.get<Blob>("/admin/catalog/products/export", {
    params: exportParams(filters),
    responseType: "blob",
  });
  const url = URL.createObjectURL(res.data);
  const link = document.createElement("a");
  link.href = url;
  link.download = productsCsvFilename();
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoking synchronously can cancel the download in some browsers: let it start first.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
