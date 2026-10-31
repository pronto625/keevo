import type { Metadata } from "next";
import AnalyticsPageClient from "./AnalyticsPageClient";

export const metadata: Metadata = {
  title: "Statistiques",
};

export default function AnalyticsPage() {
  return <AnalyticsPageClient />;
}
