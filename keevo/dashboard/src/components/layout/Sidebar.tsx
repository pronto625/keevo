"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/hooks/useAuth";
import { useRouter } from "next/navigation";

export function Sidebar() {
  const pathname = usePathname();
  const { logout } = useAuth();
  const router = useRouter();

  const handleLogout = () => {
    logout();
    router.push("/login");
  };

  const navItems = [
    { href: "/tenants", label: "Tenants" },
  ];

  const monitoringItems = [
    { href: "/monitoring/products", label: "Catalogue Produits", comingSoon: false },
    { href: "/monitoring/activity", label: "Activité", comingSoon: true },
    { href: "/monitoring/sync", label: "Synchronisation", comingSoon: true },
  ];

  const isMonitoringActive = monitoringItems.some((item) =>
    pathname.startsWith(item.href)
  );

  return (
    <aside className="w-56 bg-gray-900 text-white flex flex-col min-h-screen">
      <div className="px-4 py-5 border-b border-gray-700">
        <span className="text-lg font-bold">Keevo Admin</span>
      </div>
      <nav className="flex-1 py-4">
        {navItems.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`block px-4 py-2 text-sm hover:bg-gray-700 ${
              pathname.startsWith(item.href) ? "bg-gray-700 font-medium" : ""
            }`}
          >
            {item.label}
          </Link>
        ))}

        {/* Monitoring group */}
        <div className="mt-4">
          <p
            className={`px-4 py-1 text-xs font-semibold uppercase tracking-wider ${
              isMonitoringActive ? "text-white" : "text-gray-400"
            }`}
          >
            Monitoring
          </p>
          {monitoringItems.map((item) => (
            <Link
              key={item.href}
              href={item.comingSoon ? "#" : item.href}
              onClick={(e) => item.comingSoon && e.preventDefault()}
              className={`flex items-center justify-between px-6 py-1.5 text-sm hover:bg-gray-700 ${
                !item.comingSoon && pathname.startsWith(item.href)
                  ? "bg-gray-700 font-medium"
                  : item.comingSoon
                  ? "text-gray-500 cursor-default"
                  : "text-gray-300"
              }`}
            >
              <span>{item.label}</span>
              {item.comingSoon && (
                <span className="text-xs bg-gray-700 text-gray-400 px-1 rounded">
                  bientôt
                </span>
              )}
            </Link>
          ))}
        </div>
      </nav>
      <div className="px-4 py-3 border-t border-gray-700">
        <button
          onClick={handleLogout}
          className="text-sm text-gray-400 hover:text-white"
        >
          Déconnexion
        </button>
      </div>
    </aside>
  );
}
