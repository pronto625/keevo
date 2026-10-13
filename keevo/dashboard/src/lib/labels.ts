// Single source of French labels for backend codes shown in the dashboard (Story 9.8).
// Rule "no technical data in UI": a raw enum code, UUID or server free text is never rendered.
// An unknown code is humanized (`SOME_CODE` → « Some code »); free text falls back to a generic label.

/** A backend enum name (UPPER_SNAKE), as opposed to free text. Mirrors the Flutter app's pattern. */
const CODE_PATTERN = /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$/;
/** 6+ consecutive hex characters mixing digits in: an id fragment, never a readable code. */
const HEX_RUN_WITH_DIGIT = /[0-9A-F]{6,}/g;

/**
 * True for an UPPER_SNAKE enum name. Rejects anything that looks like an id: it must start with
 * a letter and must not contain a run of 6+ hex-looking characters that includes a digit.
 */
export function isCode(value: string): boolean {
  if (!CODE_PATTERN.test(value)) return false;
  const hexRuns = value.match(HEX_RUN_WITH_DIGIT) ?? [];
  return !hexRuns.some((run) => /[0-9]/.test(run));
}

function own(map: Record<string, string>, key: string): string | undefined {
  return Object.hasOwn(map, key) ? map[key] : undefined;
}

/** `SOME_CODE` → « Some code ». Also accepts lower_snake codes (`stock_level` → « Stock level »). */
export function humanizeCode(code: string): string {
  const words = code.trim().replace(/[_\s]+/g, " ").toLowerCase();
  if (!words) return "—";
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function lookup(map: Record<string, string>, code: string | null | undefined, fallback: string): string {
  const key = code?.trim();
  if (!key) return fallback;
  const label = own(map, key) ?? own(map, key.toUpperCase());
  if (label) return label;
  return isCode(key.toUpperCase()) ? humanizeCode(key) : fallback;
}

// Copied from keevo/app/lib/core/sync/sync_rejection_messages.dart (friendlySyncOperationLabel).
const OPERATION_LABELS: Record<string, string> = {
  CREATE_PRODUCT: "Création de produit",
  CREATE_DRAFT_PRODUCT: "Création de produit brouillon",
  UPDATE_PRODUCT: "Modification de produit",
  PRODUCT_CREATE: "Création de produit",
  PRODUCT_UPDATE: "Modification de produit",
  ARCHIVE_PRODUCT: "Archivage de produit",
  UNARCHIVE_PRODUCT: "Restauration de produit",
  PROMOTE_PRODUCT: "Validation de produit brouillon",
  CREATE_CATEGORY: "Création de catégorie",
  RENAME_CATEGORY: "Renommage de catégorie",
  TOGGLE_CATEGORY: "Activation/désactivation de catégorie",
  CREATE_CLIENT: "Création de client",
  UPDATE_CLIENT: "Modification de client",
  ARCHIVE_CLIENT: "Archivage de client",
  CREATE_SUPPLIER: "Création de fournisseur",
  UPDATE_SUPPLIER: "Modification de fournisseur",
  ARCHIVE_SUPPLIER: "Archivage de fournisseur",
  CREATE_STORE: "Création de boutique",
  UPDATE_STORE: "Modification de boutique",
  DEACTIVATE_STORE: "Désactivation de boutique",
  CREATE_SALE: "Enregistrement de vente",
  VALIDATE_SALE: "Validation de vente",
  CANCEL_SALE: "Annulation de vente",
  CREATE_DAY_CLOSURE: "Clôture de journée",
  STOCK_ADJUST: "Ajustement de stock",
  RECORD_STOCK_ENTRY: "Entrée de stock",
  STOCK_TRANSFER: "Transfert de stock",
  CREATE_INVENTORY_SESSION: "Ouverture d'inventaire",
  SAVE_INVENTORY_COUNT: "Comptage d'inventaire",
  VALIDATE_INVENTORY: "Validation d'inventaire",
  REASSIGN_EMPLOYEE: "Réaffectation d'employé",
  DEACTIVATE_EMPLOYEE: "Désactivation d'employé",
  REACTIVATE_EMPLOYEE: "Réactivation d'employé",
  CREATE_EMPLOYEE: "Création d'employé",
  SUBMIT_FEEDBACK: "Envoi d'un avis",
};

export function operationLabel(code: string | null | undefined): string {
  return lookup(OPERATION_LABELS, code, "Opération");
}

// Copied from keevo/app/lib/core/sync/sync_rejection_messages.dart (friendlySyncRejectionReason).
const REJECTION_REASON_LABELS: Record<string, string> = {
  PRODUCT_NAME_ALREADY_EXISTS: "Un produit avec ce nom existe déjà",
  PLAN_LIMIT_EXCEEDED: "Limite du forfait atteinte",
  CATEGORY_NOT_FOUND: "Catégorie introuvable",
  PRODUCT_NOT_FOUND: "Produit introuvable",
  CLIENT_NOT_FOUND: "Client introuvable",
  SUPPLIER_NOT_FOUND: "Fournisseur introuvable",
  STORE_NOT_FOUND: "Boutique introuvable",
  STORE_NOT_ACTIVE: "Boutique désactivée",
  INSUFFICIENT_STOCK: "Stock insuffisant",
  STOCK_NOT_FOUND: "Stock introuvable",
  SALE_NOT_FOUND: "Vente introuvable",
  SALE_NOT_PENDING: "Cette vente n'est plus en attente",
  SALE_ALREADY_CANCELLED: "Vente déjà annulée",
  DAY_ALREADY_CLOSED: "La journée est déjà clôturée",
  DISCOUNT_EXCEEDS_SUBTOTAL: "Remise supérieure au montant de la vente",
  JUSTIFICATION_REQUIRED: "Justification manquante",
  JUSTIFICATION_TOO_SHORT: "Justification trop courte",
  INVENTORY_SESSION_ALREADY_ACTIVE: "Un autre inventaire est déjà en cours dans cette boutique",
  INVENTORY_STORE_NOT_FOUND: "Boutique de l'inventaire introuvable",
  INVENTORY_INVALID_CATEGORIES: "Catégories de l'inventaire invalides",
  INVENTORY_SESSION_NOT_IN_PROGRESS: "Cet inventaire n'est plus en cours",
  INVENTORY_SESSION_NOT_FOUND: "Inventaire introuvable sur le serveur",
  OPTIMISTIC_LOCK: "Modifié en même temps sur un autre appareil",
  INTERNAL_ERROR: "Incident temporaire du serveur",
  TRANSACTION_ROLLED_BACK: "Incident temporaire du serveur",
  DATA_INTEGRITY_VIOLATION: "Données en conflit avec le serveur",
  UNKNOWN_OPERATION_TYPE: "Action non prise en charge par le serveur",
  SUBSCRIPTION_NOT_FOUND: "Abonnement introuvable",
  TENANT_NOT_FOUND: "Compte de l'entreprise introuvable",
  WAREHOUSE_ALREADY_EXISTS: "Une boutique avec ce nom existe déjà",
  USER_ALREADY_EXISTS: "Un utilisateur avec ces identifiants existe déjà",
  EMPLOYEE_NOT_FOUND: "Employé introuvable",
  CANNOT_DEMOTE_LAST_OWNER: "Impossible de retirer le dernier propriétaire du compte",
  VALIDATION_ERROR: "Données invalides",
  VALIDATION_FAILED: "Données invalides",
  FORBIDDEN: "Action non autorisée",
  UNAUTHORIZED: "Session expirée",
};

/**
 * Reason a sync operation was rejected. Known code → French label, unknown code → humanized,
 * free text (an unmapped server exception message, possibly containing an id) → generic label.
 */
export function rejectionReasonLabel(reason: string | null | undefined): string {
  const key = reason?.trim();
  if (!key) return "Raison inconnue";
  const known = own(REJECTION_REASON_LABELS, key.toUpperCase());
  if (known) return known;
  // Case-sensitive on purpose: only a real UPPER_SNAKE code is humanized, any other text is generic.
  return isCode(key) ? humanizeCode(key) : "Données refusées par le serveur";
}

// Entity types written to sync_conflicts_log (LastWriteWinsStrategy / SyncPushService.deriveEntityType).
const ENTITY_TYPE_LABELS: Record<string, string> = {
  PRODUCT: "Produit",
  CATEGORY: "Catégorie",
  CLIENT: "Client",
  SUPPLIER: "Fournisseur",
  EMPLOYEE: "Employé",
  STORE: "Boutique",
  SALE: "Vente",
  STOCK: "Stock",
  STOCK_LEVEL: "Stock",
  INVENTORY: "Inventaire",
  UNKNOWN: "Élément inconnu",
};

export function entityTypeLabel(code: string | null | undefined): string {
  const key = code?.trim().toUpperCase();
  if (key && !own(ENTITY_TYPE_LABELS, key) && own(OPERATION_LABELS, key)) {
    // deriveEntityType falls back to the operation type when the strategy gives no entity.
    return own(OPERATION_LABELS, key)!;
  }
  return lookup(ENTITY_TYPE_LABELS, code, "Élément");
}

const CONFLICT_TYPE_LABELS: Record<string, string> = {
  LAST_WRITE_WINS: "Modifications concurrentes",
  STOCK_NEGATIVE: "Stock devenu négatif",
};

export function conflictTypeLabel(code: string | null | undefined): string {
  return lookup(CONFLICT_TYPE_LABELS, code, "Conflit");
}

const STRATEGY_LABELS: Record<string, string> = {
  LAST_WRITE_WINS: "Dernière modification gagnante",
  LWW: "Dernière modification gagnante",
  DELTA_SUM: "Cumul des mouvements de stock",
  APPEND_ONLY: "Ajout sans conflit",
};

export function strategyLabel(code: string | null | undefined): string {
  return lookup(STRATEGY_LABELS, code, "—");
}

const PLAN_LABELS: Record<string, string> = {
  FREE: "Gratuit",
  PREMIUM: "Premium",
  PREMIUM_TRIAL: "Essai Premium",
};

export function planLabel(code: string | null | undefined): string {
  return lookup(PLAN_LABELS, code, "—");
}

// Story 9-2 — tenant lifecycle status.
const TENANT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "Actif",
  SUSPENDED: "Suspendu",
  DELETION_PENDING: "Suppression planifiée",
  DELETED: "Supprimé",
};

export function tenantStatusLabel(code: string | null | undefined): string {
  return lookup(TENANT_STATUS_LABELS, code, "—");
}

// Story 9-2 — off-platform payment methods.
const PAYMENT_METHOD_LABELS: Record<string, string> = {
  MOBILE_MONEY: "Mobile Money",
  CASH: "Espèces",
  BANK_TRANSFER: "Virement bancaire",
  OTHER: "Autre",
};

export function paymentMethodLabel(code: string | null | undefined): string {
  return lookup(PAYMENT_METHOD_LABELS, code, "—");
}

/** Payment methods in display order (select options). */
export const PAYMENT_METHODS = ["MOBILE_MONEY", "CASH", "BANK_TRANSFER", "OTHER"] as const;

const USER_ROLE_LABELS: Record<string, string> = {
  OWNER: "Propriétaire",
  EMPLOYEE: "Employé",
  SUPER_ADMIN: "Administrateur plateforme",
};

export function userRoleLabel(code: string | null | undefined): string {
  return lookup(USER_ROLE_LABELS, code, "—");
}

const SYNC_STATUS_LABELS: Record<string, string> = {
  OK: "Sain",
  WARN: "À surveiller",
  ERROR: "En erreur",
};

export function syncStatusLabel(code: string | null | undefined): string {
  return lookup(SYNC_STATUS_LABELS, code, "—");
}

/**
 * Display name of a device's user: employee full name, else « Propriétaire » for the tenant
 * OWNER, else « Utilisateur inconnu ». Never the user id.
 */
export function deviceUserLabel(userName: string | null | undefined, userRole: string | null | undefined): string {
  const name = userName?.trim();
  if (name) return name;
  if (userRole === "OWNER") return "Propriétaire";
  return "Utilisateur inconnu";
}

/**
 * « Appareil 1 », « Appareil 2 »… numbered per user — the device id is never shown. The number
 * follows a stable key (device ids sorted per user), not the display order: the backend sorts by
 * last push, so numbering by position would swap labels between refreshes.
 * Returns one label per input row, same order as the input.
 */
export function deviceNumberLabels(
  devices: ReadonlyArray<{ deviceId: string; userId: string | null }>,
): string[] {
  const idsByUser = new Map<string, string[]>();
  for (const d of devices) {
    const key = d.userId ?? "";
    const ids = idsByUser.get(key) ?? [];
    ids.push(d.deviceId);
    idsByUser.set(key, ids);
  }
  for (const ids of idsByUser.values()) ids.sort();
  return devices.map((d) => `Appareil ${idsByUser.get(d.userId ?? "")!.indexOf(d.deviceId) + 1}`);
}

// Story 9.7 — period selector of the « Activité » page.
const PERIOD_LABELS: Record<string, string> = {
  TODAY: "Aujourd'hui",
  "7D": "7 jours",
  "30D": "30 jours",
  "90D": "90 jours",
  CUSTOM: "Personnalisée",
};

export function periodLabel(code: string | null | undefined): string {
  return lookup(PERIOD_LABELS, code, "—");
}
