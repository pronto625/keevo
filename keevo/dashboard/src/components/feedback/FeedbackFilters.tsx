"use client";

import { FEEDBACK_TYPES, feedbackTypeLabel, priorityLabel } from "@/lib/labels";
import type { FeedbackFilters } from "@/types/feedback";

interface FeedbackFiltersProps {
  filters: FeedbackFilters;
  onUpdate: <K extends keyof FeedbackFilters>(key: K, value: FeedbackFilters[K]) => void;
}

const selectClass =
  "border border-gray-300 rounded-lg bg-white text-gray-900 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500";
const labelClass = "text-xs font-medium text-gray-600 mb-1 block";

const HANDLED_OPTIONS: { value: FeedbackFilters["handled"]; label: string }[] = [
  { value: "false", label: "Non traités" },
  { value: "true", label: "Traités" },
  { value: "all", label: "Tous" },
];

export function FeedbackFiltersPanel({ filters, onUpdate }: FeedbackFiltersProps) {
  return (
    <div className="flex flex-wrap gap-3 items-end">
      <div>
        <label className={labelClass} htmlFor="feedback-filter-type">Type</label>
        <select
          id="feedback-filter-type"
          value={filters.type}
          onChange={(e) => onUpdate("type", e.target.value)}
          className={selectClass}
        >
          <option value="">Tous</option>
          {FEEDBACK_TYPES.map((t) => (
            <option key={t} value={t}>{feedbackTypeLabel(t)}</option>
          ))}
        </select>
      </div>

      <div>
        <label className={labelClass} htmlFor="feedback-filter-priority">Priorité</label>
        <select
          id="feedback-filter-priority"
          value={filters.priority}
          onChange={(e) => onUpdate("priority", e.target.value as FeedbackFilters["priority"])}
          className={selectClass}
        >
          <option value="">Toutes</option>
          <option value="HIGH">{priorityLabel("HIGH")}</option>
          <option value="NORMAL">{priorityLabel("NORMAL")}</option>
        </select>
      </div>

      <div>
        <label className={labelClass} htmlFor="feedback-filter-handled">Traité</label>
        <select
          id="feedback-filter-handled"
          value={filters.handled}
          onChange={(e) => onUpdate("handled", e.target.value as FeedbackFilters["handled"])}
          className={selectClass}
        >
          {HANDLED_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>
    </div>
  );
}
