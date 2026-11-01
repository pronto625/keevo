// Story 16.4 — super-admin plan profiles and limits (Story 16.8 endpoints).
import client from "./client";
import type { PlanProfile, PlanProfileInput, PlanProfileUpdate, PlanTypeCode } from "@/types/modules";

export async function getPlanProfiles(): Promise<PlanProfile[]> {
  const res = await client.get<{ data: PlanProfile[] }>("/admin/plan-profiles");
  return res.data.data;
}

/** Saves a profile: the three limit keys are always sent (null = unlimited). */
export async function updatePlanProfile(planType: PlanTypeCode, input: PlanProfileInput): Promise<PlanProfileUpdate> {
  const res = await client.put<{ data: PlanProfileUpdate }>(`/admin/plan-profiles/${planType}`, {
    maxStores: input.maxStores,
    maxProducts: input.maxProducts,
    maxEmployees: input.maxEmployees,
    modules: input.modules,
  });
  return res.data.data;
}
