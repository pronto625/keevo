import type { Metadata } from "next";
import ModulesPageClient from "./ModulesPageClient";

export const metadata: Metadata = {
  title: "Modules",
};

export default function ModulesPage() {
  return <ModulesPageClient />;
}
