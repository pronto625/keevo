import client from "./client";
import type {
  AdoptionReport,
  AnalyticsOverview,
  AnalyticsRange,
  CountryCount,
  GrowthPoint,
  RetentionReport,
  TopStoresReport,
} from "@/types/analytics";

export async function getAnalyticsOverview(): Promise<AnalyticsOverview> {
  const res = await client.get<{ data: AnalyticsOverview }>("/admin/analytics/overview");
  return res.data.data;
}

export async function getAnalyticsGrowth(months = 12): Promise<GrowthPoint[]> {
  const res = await client.get<{ data: GrowthPoint[] }>("/admin/analytics/growth", { params: { months } });
  return res.data.data;
}

export async function getAnalyticsGeography(): Promise<CountryCount[]> {
  const res = await client.get<{ data: CountryCount[] }>("/admin/analytics/geography");
  return res.data.data;
}

export async function getAnalyticsRetention(weeks = 8): Promise<RetentionReport> {
  const res = await client.get<{ data: RetentionReport }>("/admin/analytics/retention", { params: { weeks } });
  return res.data.data;
}

export async function getTopStores(range: AnalyticsRange): Promise<TopStoresReport> {
  const res = await client.get<{ data: TopStoresReport }>("/admin/analytics/top-stores", {
    params: { from: range.from, to: range.to },
  });
  return res.data.data;
}

export async function getFeatureAdoption(): Promise<AdoptionReport> {
  const res = await client.get<{ data: AdoptionReport }>("/admin/analytics/adoption");
  return res.data.data;
}
