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
