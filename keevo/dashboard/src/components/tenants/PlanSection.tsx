"use client";

import { useEffect, useState } from "react";
import { useTenantPayments, useTenantPlanActions } from "@/hooks/useTenants";
import { apiErrorMessage, apiFieldErrors } from "@/lib/api/admin-tenants";
import { planLabel, tenantStatusLabel } from "@/lib/labels";
import type { ActivatePremiumInput, TenantStatus, TenantSubscription } from "@/types/tenant";
import { ActivatePremiumDialog } from "./ActivatePremiumDialog";
import { PaymentHistory } from "./PaymentHistory";
import { SuspendDialog } from "./SuspendDialog";
import { formatInstantWat } from "./planDates";

interface PlanSectionProps {
  tenantId: string;
  status: TenantStatus;
  subscription: TenantSubscription | null;
}

type Dialog = "premium" | "free" | "suspend" | "reactivate" | null;
type Toast = { kind: "success" | "error"; message: string } | null;

const actionButton =
  "px-3 py-1.5 text-sm rounded-lg font-medium disabled:opacity-50 disabled:cursor-not-allowed";

/**
 * « Plan & paiements » section of the tenant drawer (Story 9-2): current plan and expiry,
 * Premium activation with payment, downgrade to Free, suspension / reactivation, payment history.
 */
export function PlanSection({ tenantId, status, subscription }: PlanSectionProps) {
  const [dialog, setDialog] = useState<Dialog>(null);
  const [toast, setToast] = useState<Toast>(null);
  const [premiumErrors, setPremiumErrors] = useState<Record<string, string>>({});
  const payments = useTenantPayments(tenantId);
  const { activate, downgrade, suspend, reactivate } = useTenantPlanActions(tenantId);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  const done = (message: string) => {
    setDialog(null);
    setToast({ kind: "success", message });
  };
  const failed = (err: unknown) => setToast({ kind: "error", message: apiErrorMessage(err) });

  const onActivate = (input: ActivatePremiumInput) => {
    setPremiumErrors({});
    activate.mutate(input, {
      onSuccess: () => done("Plan Premium activé"),
      onError: (err) => {
        const fields = apiFieldErrors(err);
        if (Object.keys(fields).length > 0) setPremiumErrors(fields);
        failed(err);
      },
    });
  };

  const plan = subscription?.planType ?? null;
  const deleted = status === "DELETED";
  // Plan changes need a readable subscription and a tenant that is not being deleted.
  const planLocked = deleted || status === "DELETION_PENDING" || subscription === null;
  const pending =
    activate.isPending || downgrade.isPending || suspend.isPending || reactivate.isPending;

  return (
    <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-4">
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
        Plan &amp; paiements
      </h3>

      {toast && (
        <div
          role={toast.kind === "error" ? "alert" : "status"}
          className={`text-sm rounded-lg px-3 py-2 ${
            toast.kind === "error"
              ? "bg-red-50 text-red-700 border border-red-200"
              : "bg-green-50 text-green-700 border border-green-200"
          }`}
        >
          {toast.message}
        </div>
      )}

      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
        <dt className="text-gray-500 font-medium">Plan actuel</dt>
        <dd className="text-gray-900">{plan ? planLabel(plan) : "Inconnu"}</dd>
        <dt className="text-gray-500 font-medium">Échéance</dt>
        <dd className="text-gray-900">
          {subscription?.expiresAt ? formatInstantWat(subscription.expiresAt) : "Aucune"}
        </dd>
        <dt className="text-gray-500 font-medium">Statut du compte</dt>
        <dd className="text-gray-900">{tenantStatusLabel(status)}</dd>
      </dl>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={planLocked || pending}
          onClick={() => {
            setPremiumErrors({});
            setDialog("premium");
          }}
          className={`${actionButton} bg-purple-600 text-white hover:bg-purple-700`}
        >
          Passer en Premium
        </button>
        {plan !== null && plan !== "FREE" && (
          <button
            type="button"
            disabled={planLocked || pending}
            onClick={() => setDialog("free")}
            className={`${actionButton} border border-gray-300 text-gray-700 hover:bg-gray-50`}
          >
            Repasser en Gratuit
          </button>
        )}
        {status === "ACTIVE" && (
          <button
            type="button"
            disabled={pending}
            onClick={() => setDialog("suspend")}
            className={`${actionButton} bg-red-600 text-white hover:bg-red-700`}
          >
            Suspendre
          </button>
        )}
        {status === "SUSPENDED" && (
          <button
            type="button"
            disabled={pending}
            onClick={() => setDialog("reactivate")}
            className={`${actionButton} bg-green-600 text-white hover:bg-green-700`}
          >
            Réactiver
          </button>
        )}
      </div>

      <div>
        <h4 className="text-xs font-medium text-gray-500 mb-2">Historique des paiements</h4>
        <PaymentHistory
          payments={payments.data}
          isLoading={payments.isLoading}
          isError={payments.isError}
        />
      </div>

      {dialog === "premium" && (
        <ActivatePremiumDialog
          isPending={activate.isPending}
          serverErrors={premiumErrors}
          currentSubscription={subscription}
          onSubmit={onActivate}
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog === "free" && (
        <SuspendDialog
          title="Repasser en Gratuit ?"
          description="Le compte repassera au plan Gratuit avec ses limites. Les données sont conservées et le propriétaire est prévenu sur WhatsApp."
          confirmLabel="Repasser en Gratuit"
          reasonRequired={false}
          isPending={downgrade.isPending}
          onConfirm={(reason) =>
            downgrade.mutate(reason, { onSuccess: () => done("Compte repassé au plan Gratuit"), onError: failed })
          }
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog === "suspend" && (
        <SuspendDialog
          title="Suspendre ce compte ?"
          description="Le compte passera en lecture seule : plus aucune modification possible jusqu'à sa réactivation. Les sessions en cours sont fermées."
          confirmLabel="Suspendre"
          reasonRequired
          destructive
          isPending={suspend.isPending}
          onConfirm={(reason) =>
            suspend.mutate(reason, { onSuccess: () => done("Compte suspendu"), onError: failed })
          }
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog === "reactivate" && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
          <div role="dialog" aria-modal="true" className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4">
            <h3 className="font-semibold text-gray-900 mb-2">Réactiver ce compte ?</h3>
            <p className="text-sm text-gray-500 mb-4">
              Le compte retrouvera un accès complet après la prochaine reconnexion de ses utilisateurs.
            </p>
            <div className="flex gap-3 justify-end">
              <button
                type="button"
                onClick={() => setDialog(null)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                type="button"
                disabled={reactivate.isPending}
                onClick={() =>
                  reactivate.mutate(undefined, { onSuccess: () => done("Compte réactivé"), onError: failed })
                }
                className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-green-600 hover:bg-green-700 disabled:opacity-50"
              >
                Réactiver
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
