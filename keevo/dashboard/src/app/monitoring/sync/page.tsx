import type { Metadata } from "next";
import SyncMonitoringPageClient from "./SyncMonitoringPageClient";

export const metadata: Metadata = {
  title: "Synchronisation",
};

export default function SyncMonitoringPage() {
  return <SyncMonitoringPageClient />;
}
