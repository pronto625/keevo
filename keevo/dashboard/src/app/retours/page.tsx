import type { Metadata } from "next";
import RetoursPageClient from "./RetoursPageClient";

export const metadata: Metadata = {
  title: "Retours utilisateurs",
};

export default function RetoursPage() {
  return <RetoursPageClient />;
}
