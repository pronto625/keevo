import type { Metadata } from "next";
import ActivityPageClient from "./ActivityPageClient";

export const metadata: Metadata = {
  title: "Activité",
};

export default function ActivityPage() {
  return <ActivityPageClient />;
}
