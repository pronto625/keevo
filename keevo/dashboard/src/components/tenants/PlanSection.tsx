"use client";

import { useEffect, useState } from "react";
import { useTenantPayments, useTenantPlanActions } from "@/hooks/useTenants";
import { apiErrorMessage, apiFieldErrors } from "@/lib/api/admin-tenants";
import { formatXAFFull } from "@/lib/format";
import { planLabel, tenantStatusLabel } from "@/lib/labels";
import type {
  ActivatePremiumInput,
  TenantPayment,
  TenantStatus,
  TenantSubscription,
  TenantSuspension,
} from "@/types/tenant";
import { ActivatePremiumDialog } from "./ActivatePremiumDialog";
import { PaymentHistory } from "./PaymentHistory";
import { SuspendDialog } from "./SuspendDialog";
import { formatInstantWat, formatIsoDate } from "./planDates";

interface PlanSectionProps {
  tenantId: string;
  status: TenantStatus;
  subscription: TenantSubscription | null;
  /** Current suspension trace (date + reason), null when not suspended. */
  suspension?: TenantSuspension | null;
  /** Re-reads the tenant detail (plan unreadable → « Réessayer »). */
  onRetryPlan?: () => void;
}

type Dialog = "premium" | "free" | "suspend" | "reactivate" | "void" | null;
type Toast = { kind: "success"; message: string } | null;
/** Error of the open dialog: general message (top) and field errors (under their input). */
type DialogError = { message: string; fields: Record<string, string> } | null;

const NO_FIELDS: Record<string, string> = {};

const actionButton =
  "px-3 py-1.5 text-sm rounded-lg font-medium disabled:opacity-50 disabled:cursor-not-allowed";

/** Downgrade dialog text: a running paid Premium loses its remaining paid days. */
export function downgradeDescription(subscription: TenantSubscription | null): string {
  const base = "Le compte repassera au plan Gratuit avec ses limites.";
  const tail = "Les données sont conservées et le propriétaire est prévenu sur WhatsApp.";
  if (subscription?.planType === "PREMIUM" && subscription.expiresAt) {
    return `${base} Premium payé jusqu'au ${formatInstantWat(subscription.expiresAt)} : les jours payés restants seront perdus. ${tail}`;
  }
  return `${base} ${tail}`;
}

/**
 * « Plan & paiements » section of the tenant drawer (Story 9-2): current plan and expiry,
 * Premium activation with payment, downgrade to Free, suspension / reactivation, payment history
 * with « Annuler ce paiement ». Errors are shown inside the open dialog; successes as a toast.
 */
export function PlanSection({ tenantId, status, subscription, suspension = null, onRetryPlan }: PlanSectionProps) {
  const [dialog, setDialog] = useState<Dialog>(null);
  const [toast, setToast] = useState<Toast>(null);
  const [dialogError, setDialogError] = useState<DialogError>(null);
  const [voidTarget, setVoidTarget] = useState<TenantPayment | null>(null);
  const payments = useTenantPayments(tenantId);
  const { activate, downgrade, suspend, reactivate, voidPayment } = useTenantPlanActions(tenantId);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  const open = (d: Dialog) => {
    setDialogError(null);
    setDialog(d);
  };
  const close = () => {
    setDialogError(null);
    setDialog(null);
    setVoidTarget(null);
  };
  const done = (message: string) => {
    close();
    setToast({ kind: "success", message });
  };
  const failed = (err: unknown) =>
    setDialogError({ message: apiErrorMessage(err), fields: apiFieldErrors(err) });
  /** Clears the previous answer before a new call, so an identical error is shown again. */
  const run = (call: () => void) => {
    setDialogError(null);
    call();
  };

  const onActivate = (input: ActivatePremiumInput) =>
    run(() => activate.mutate(input, { onSuccess: () => done("Plan Premium activé"), onError: failed }));

  const plan = subscription?.planType ?? null;
  const deleted = status === "DELETED";
  // Plan changes need a readable subscription and a tenant that is not being deleted.
  const planLocked = deleted || status === "DELETION_PENDING" || subscription === null;
  const pending =
    activate.isPending || downgrade.isPending || suspend.isPending || reactivate.isPending || voidPayment.isPending;
  const fields = dialogError?.fields ?? NO_FIELDS;

  return (
    <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-4">
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
        Plan &amp; paiements
      </h3>

      {toast && (
        <div
          role="status"
          className="text-sm rounded-lg px-3 py-2 bg-green-50 text-green-700 border border-green-200"
        >
          {toast.message}
        </div>
      )}

      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
        <dt className="text-gray-500 font-medium">Plan actuel</dt>
        <dd className="text-gray-900">
          {plan ? (
            planLabel(plan)
          ) : (
            <span className="flex flex-wrap items-center gap-2">
              <span className="text-gray-600">Plan illisible pour le moment</span>
              {onRetryPlan && (
                <button
                  type="button"
                  onClick={onRetryPlan}
                  className="text-xs px-2 py-0.5 border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
                >
                  Réessayer
                </button>
              )}
            </span>
          )}
        </dd>
        <dt className="text-gray-500 font-medium">Échéance</dt>
        <dd className="text-gray-900">
          {subscription?.expiresAt ? formatInstantWat(subscription.expiresAt) : plan ? "Aucune" : "—"}
        </dd>
        <dt className="text-gray-500 font-medium">Statut du compte</dt>
        <dd className="text-gray-900">
          {tenantStatusLabel(status)}
          {status === "SUSPENDED" && suspension && (
            <span className="block text-xs text-gray-500">
              Suspendu le {formatInstantWat(suspension.suspendedAt)}
              {suspension.reason ? ` — motif : ${suspension.reason}` : ""}
            </span>
          )}
        </dd>
      </dl>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={planLocked || pending}
          onClick={() => open("premium")}
          className={`${actionButton} bg-purple-600 text-white hover:bg-purple-700`}
        >
          Passer en Premium
        </button>
        {plan !== null && plan !== "FREE" && (
          <button
            type="button"
            disabled={planLocked || pending}
            onClick={() => open("free")}
            className={`${actionButton} border border-gray-300 text-gray-700 hover:bg-gray-50`}
          >
            Repasser en Gratuit
          </button>
        )}
        {status === "ACTIVE" && (
          <button
            type="button"
            disabled={pending}
            onClick={() => open("suspend")}
            className={`${actionButton} bg-red-600 text-white hover:bg-red-700`}
          >
            Suspendre
          </button>
        )}
        {status === "SUSPENDED" && (
          <button
            type="button"
            disabled={pending}
            onClick={() => open("reactivate")}
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
          voidDisabled={pending}
          onVoid={(p) => {
            setVoidTarget(p);
            open("void");
          }}
        />
      </div>

      {dialog === "premium" && (
        <ActivatePremiumDialog
          isPending={activate.isPending}
          serverErrors={fields}
          serverError={dialogError?.message ?? null}
          currentSubscription={subscription}
          payments={payments.data}
          onSubmit={onActivate}
          onCancel={close}
        />
      )}
      {dialog === "free" && (
        <SuspendDialog
          title="Repasser en Gratuit ?"
          description={downgradeDescription(subscription)}
          confirmLabel="Repasser en Gratuit"
          reasonRequired={false}
          isPending={downgrade.isPending}
          serverError={dialogError?.message ?? null}
          serverReasonError={fields.reason ?? null}
          onConfirm={(reason) =>
            run(() =>
              downgrade.mutate(reason, { onSuccess: () => done("Compte repassé au plan Gratuit"), onError: failed }),
            )
          }
          onCancel={close}
        />
      )}
      {dialog === "suspend" && (
        <SuspendDialog
          title="Suspendre ce compte ?"
          description="Le compte passera en lecture seule : plus aucune modification possible jusqu'à sa réactivation. La suspension prend effet dans les minutes qui suivent (déconnexion forcée)."
          confirmLabel="Suspendre"
          reasonRequired
          destructive
          isPending={suspend.isPending}
          serverError={dialogError?.message ?? null}
          serverReasonError={fields.reason ?? null}
          onConfirm={(reason) =>
            run(() => suspend.mutate(reason, { onSuccess: () => done("Compte suspendu"), onError: failed }))
          }
          onCancel={close}
        />
      )}
      {dialog === "void" && voidTarget && (
        <SuspendDialog
          title="Annuler ce paiement ?"
          description={`Paiement de ${formatXAFFull(voidTarget.amountXaf)} du ${formatInstantWat(voidTarget.paidAt)} (période ${formatIsoDate(voidTarget.periodStart)} → ${formatIsoDate(voidTarget.periodEnd)}). Il restera visible, barré, et ne comptera plus dans les revenus. L'échéance du plan n'est pas modifiée.`}
          confirmLabel="Annuler ce paiement"
          reasonRequired
          destructive
          isPending={voidPayment.isPending}
          serverError={dialogError?.message ?? null}
          serverReasonError={fields.reason ?? null}
          onConfirm={(reason) =>
            run(() =>
              voidPayment.mutate(
                { paymentId: voidTarget.id, reason },
                { onSuccess: () => done("Paiement annulé"), onError: failed },
              ),
            )
          }
          onCancel={close}
        />
      )}
      {dialog === "reactivate" && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
          <div role="dialog" aria-modal="true" className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4">
            <h3 className="font-semibold text-gray-900 mb-2">Réactiver ce compte ?</h3>
            {dialogError && (
              <p role="alert" className="text-sm rounded-lg px-3 py-2 mb-3 bg-red-50 text-red-700 border border-red-200">
                {dialogError.message}
              </p>
            )}
            <p className="text-sm text-gray-500 mb-4">
              Le compte retrouvera un accès complet : l&apos;accès revient dans les minutes qui suivent.
            </p>
            <div className="flex gap-3 justify-end">
              <button
                type="button"
                onClick={close}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                type="button"
                disabled={reactivate.isPending}
                onClick={() =>
                  run(() =>
                    reactivate.mutate(undefined, { onSuccess: () => done("Compte réactivé"), onError: failed }),
                  )
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
