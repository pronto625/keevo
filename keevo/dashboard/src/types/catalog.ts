export interface AdminProductListItem {
  id: string;
  name: string;
  tenantId: string;
  tenantName: string;
  tenantPlan: "FREE" | "PAID";
  categoryName: string;
  price: number;
  stockQuantity: number;
  status: "ACTIVE" | "DRAFT" | "ARCHIVED";
  updatedAt: string; // ISO-8601
}

export interface AdminProductPage {
  items: AdminProductListItem[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export interface AdminCatalogSummary {
  totalProducts: number;
  activeProducts: number;
  draftProducts: number;
  lowStockProducts: number;
  outOfStockProducts: number;
  createdThisWeek: number;
}

export interface ProductFilters {
  search: string;
  tenantId: string; // "" = ALL
  stockLevel: "ALL" | "OK" | "LOW" | "OUT";
  status: "ALL" | "ACTIVE" | "DRAFT" | "ARCHIVED";
  plan: "ALL" | "FREE" | "PAID";
}
