import client from "./client";
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

export function buildExportUrl(filters: ProductFilters): string {
  const params = new URLSearchParams();
  if (filters.search) params.set("search", filters.search);
  if (filters.tenantId) params.set("tenantId", filters.tenantId);
  if (filters.stockLevel !== "ALL") params.set("stockLevel", filters.stockLevel);
  if (filters.status !== "ALL") params.set("status", filters.status);
  if (filters.plan !== "ALL") params.set("plan", filters.plan);
  const qs = params.toString();
  return `/api/backend/admin/catalog/products/export${qs ? `?${qs}` : ""}`;
}
