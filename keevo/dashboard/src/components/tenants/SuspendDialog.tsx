"use client";

import { useState } from "react";

interface SuspendDialogProps {
  title: string;
  description: string;
  confirmLabel: string;
  /** When true, a 3–500 character reason is required (suspension). */
  reasonRequired: boolean;
  destructive?: boolean;
  isPending: boolean;
  /** General error of the last server answer, shown at the top of the dialog. */
  serverError?: string | null;
  /** Server error of the reason field (422 `details.reason`), shown under the input. */
  serverReasonError?: string | null;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}

/**
 * Confirmation dialog with a free-text reason (Story 9-2): « Suspendre » and « Annuler ce paiement »
 * (reason required), « Repasser en Gratuit » (reason optional). Server errors stay in the dialog.
 */
export function SuspendDialog({
  title,
  description,
  confirmLabel,
  reasonRequired,
  destructive = false,
  isPending,
  serverError = null,
  serverReasonError = null,
  onConfirm,
  onCancel,
}: SuspendDialogProps) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  // the server's reason error is hidden once the reason is edited (shown again on a new answer)
  const [reasonEdited, setReasonEdited] = useState(false);
  const [prevServerReasonError, setPrevServerReasonError] = useState(serverReasonError);
  if (prevServerReasonError !== serverReasonError) {
    setPrevServerReasonError(serverReasonError);
    setReasonEdited(false);
  }
  const reasonError = error ?? (reasonEdited ? null : serverReasonError);

  const confirm = () => {
    const r = reason.trim();
    if (reasonRequired && (r.length < 3 || r.length > 500)) {
      setError("Motif requis (3 à 500 caractères)");
      return;
    }
    if (r.length > 500) {
      setError("500 caractères maximum");
      return;
    }
    setError(null);
    onConfirm(r);
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="reason-dialog-title"
        className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4"
      >
        <h3 id="reason-dialog-title" className="font-semibold text-gray-900 mb-2">
          {title}
        </h3>
        {serverError && (
          <p role="alert" className="text-sm rounded-lg px-3 py-2 mb-3 bg-red-50 text-red-700 border border-red-200">
            {serverError}
          </p>
        )}
        <p className="text-sm text-gray-500 mb-4">{description}</p>
        <label htmlFor="reason-dialog-input" className="block text-xs font-medium text-gray-600 mb-1">
          {reasonRequired ? "Motif" : "Motif (facultatif)"}
        </label>
        <textarea
          id="reason-dialog-input"
          rows={3}
          maxLength={500}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value);
            setReasonEdited(true);
          }}
          className="w-full border border-gray-300 rounded-lg bg-white text-gray-900 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        {reasonError && <p className="text-xs text-red-600 mt-1">{reasonError}</p>}
        <div className="flex gap-3 justify-end mt-4">
          <button
            type="button"
            onClick={onCancel}
            className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
          >
            Annuler
          </button>
          <button
            type="button"
            onClick={confirm}
            disabled={isPending}
            className={`px-4 py-2 text-sm text-white rounded-lg font-medium disabled:opacity-50 ${
              destructive ? "bg-red-600 hover:bg-red-700" : "bg-blue-600 hover:bg-blue-700"
            }`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
