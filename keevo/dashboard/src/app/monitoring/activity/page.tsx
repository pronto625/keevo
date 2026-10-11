"use client";

import { AdminLayout } from "@/components/layout/AdminLayout";

// Story 9-7 (Activité Opérationnelle) is not implemented yet: no backend
// `/api/v1/admin/operations/*` exists. This placeholder keeps the route
// resolvable (no 404) without calling any API. The full page contract is
// preserved in the 9-7 story file and in git history (commit e1f91c6).
export default function ActivityPage() {
  return (
    <AdminLayout>
      <div className="p-6 space-y-6">
        <h1 className="text-xl font-bold text-gray-800">
          Activité Opérationnelle
        </h1>
        <div className="bg-white rounded-lg shadow p-8 text-center">
          <span className="inline-block text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded mb-3">
            bientôt disponible
          </span>
          <p className="text-gray-700 font-medium">
            Le suivi de l&apos;activité opérationnelle arrive bientôt.
          </p>
          <p className="text-sm text-gray-500 mt-2">
            Volume d&apos;affaires (GMV), activité par tenant et tenants
            inactifs seront disponibles dans une prochaine version.
          </p>
        </div>
      </div>
    </AdminLayout>
  );
}
