import { IncidentBanner } from "@/components/health/IncidentBanner";
import { Sidebar } from "./Sidebar";

export function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen bg-gray-100">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0">
        {/* Story 9-5 — sticky incident banner on every admin page. */}
        <IncidentBanner />
        <main className="flex-1 p-6 overflow-auto">{children}</main>
      </div>
    </div>
  );
}
