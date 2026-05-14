"use client";

interface TenantSearchBarProps {
  value: string;
  onChange: (value: string) => void;
}

export function TenantSearchBar({ value, onChange }: TenantSearchBarProps) {
  return (
    <div className="relative">
      <span className="absolute inset-y-0 left-3 flex items-center text-gray-400 pointer-events-none">
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
        </svg>
      </span>
      <input
        type="search"
        placeholder="Rechercher par nom, téléphone ou code…"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="pl-9 pr-3 py-2 border border-gray-300 rounded-lg bg-white text-gray-900 placeholder-gray-400 text-sm w-80 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
      />
    </div>
  );
}
