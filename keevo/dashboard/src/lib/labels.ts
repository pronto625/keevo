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

// Fallback labels shared by every page (one constant each — mirrors the backend AdminLabels).
export const OWNER_LABEL = "Propriétaire";
export const EMPLOYEE_LABEL = "Employé";
export const UNKNOWN_USER_LABEL = "Utilisateur inconnu";
export const UNNAMED_TENANT_LABEL = "Entreprise sans nom";
export const DELETED_TENANT_LABEL = "Entreprise supprimée";

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
  if (userRole === "OWNER") return OWNER_LABEL;
  return UNKNOWN_USER_LABEL;
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

// Story 9-3 — « Revenus » page.
export const REVENUE_LABELS = {
  mrr: "MRR",
  arr: "ARR",
  payingTenants: "Entreprises payantes",
  freeTenants: "Entreprises gratuites",
  trialTenants: "Entreprises en essai",
  premiumTenants: "Entreprises Premium",
  conversionRate: "Conversion essai → Premium",
  churnRate: "Taux de churn",
  churnRateRange: "Taux de churn sur la période",
  newPayingTenants: "Nouveaux payants",
  revenueCollected: "Encaissé sur la période",
  chartMrr: "MRR",
  chartPaying: "Entreprises payantes",
  chartNew: "Nouveaux payants",
  chartChurned: "Entreprises perdues",
  churnTitle: "Entreprises perdues",
  churnEmpty: "Aucune entreprise perdue ce mois-ci",
  exportCsv: "Exporter CSV",
} as const;

const MONTH_NAMES = [
  "janvier", "février", "mars", "avril", "mai", "juin",
  "juillet", "août", "septembre", "octobre", "novembre", "décembre",
];
const MONTH_SHORT_NAMES = [
  "janv.", "févr.", "mars", "avr.", "mai", "juin",
  "juil.", "août", "sept.", "oct.", "nov.", "déc.",
];

/** `2026-10` → « octobre 2026 » (short: « oct. 26 »). */
export function monthLabel(month: string | null | undefined, short = false): string {
  const match = /^(\d{4})-(\d{2})$/.exec(month?.trim() ?? "");
  if (!match) return "—";
  const index = Number(match[2]) - 1;
  const name = MONTH_NAMES[index];
  if (!name) return "—";
  return short ? `${MONTH_SHORT_NAMES[index]} ${match[1].slice(2)}` : `${name} ${match[1]}`;
}

// Story 9-4 — « Analytics » page.
export const ANALYTICS_LABELS = {
  dau: "Utilisateurs actifs aujourd'hui",
  mau: "Utilisateurs actifs (30 derniers jours)",
  dauMau: "Taux d'engagement (actifs du jour / actifs 30 jours)",
  totalTenants: "Entreprises",
  totalStores: "Boutiques",
  activeStores: "Boutiques actives (30 jours)",
  totalProducts: "Produits",
  totalCompletedSales: "Ventes réalisées",
  totalGmv: "Volume de ventes total",
  newTenants: "Nouvelles entreprises",
  cumulativeTenants: "Entreprises (cumul)",
  salesCount: "Ventes",
  gmv: "Volume de ventes",
  monthlyActiveUsers: "Utilisateurs actifs du mois",
  geographyTitle: "Répartition géographique des entreprises",
  geographyHint: "D'après l'indicatif du téléphone du propriétaire",
  retentionTitle: "Rétention des entreprises par semaine d'inscription",
  cohort: "Semaine d'inscription",
  cohortSize: "Entreprises",
  topStoresTitle: "Top 20 des boutiques par volume de ventes",
  store: "Boutique",
  tenant: "Entreprise",
  transactions: "Transactions",
  averageBasket: "Panier moyen",
  growth: "Évolution",
  newStore: "Nouveau",
  adoptionTitle: "Adoption des fonctionnalités (entreprises actives sur 30 jours)",
  noData: "Aucune donnée",
  retentionLegend: "— : semaine pas encore terminée",
} as const;

export const ANALYTICS_TABS = [
  { id: "overview", label: "Vue d'ensemble" },
  { id: "retention", label: "Rétention" },
  { id: "top-stores", label: "Top boutiques" },
  { id: "adoption", label: "Adoption" },
] as const;

const COUNTRY_LABELS: Record<string, string> = {
  CM: "Cameroun",
  CI: "Côte d'Ivoire",
  SN: "Sénégal",
  GA: "Gabon",
  CG: "Congo",
  CD: "RD Congo",
  BJ: "Bénin",
  TG: "Togo",
  BF: "Burkina Faso",
  ML: "Mali",
  NE: "Niger",
  TD: "Tchad",
  CF: "Centrafrique",
  GQ: "Guinée équatoriale",
  FR: "France",
  OTHER: "Autre",
  UNKNOWN: "Inconnu",
};

/** Country of the analytics geography (ISO code / OTHER / UNKNOWN); an unmapped code → « Autre ». */
export function countryLabel(code: string | null | undefined): string {
  const key = code?.trim().toUpperCase();
  if (!key) return "Inconnu";
  return own(COUNTRY_LABELS, key) ?? "Autre";
}

const FEATURE_LABELS: Record<string, string> = {
  SALES: "Ventes",
  INVENTORY: "Inventaires",
  TRANSFERS: "Transferts",
  WHATSAPP_REPORTS: "Rapports WhatsApp",
  STOCK_ALERTS: "Alertes stock",
  OFFLINE_SYNC: "Synchronisation hors ligne",
};

export function featureLabel(code: string | null | undefined): string {
  return lookup(FEATURE_LABELS, code, "—");
}

/** `2026-09-28` (Monday) → « Sem. du 28/09/2026 ». */
export function cohortWeekLabel(weekStart: string | null | undefined): string {
  const [y, m, d] = (weekStart ?? "").split("-");
  return y && m && d ? `Sem. du ${d}/${m}/${y}` : "—";
}

// ── Spec 9-1 completion — Entreprises page and « Retours utilisateurs » inbox ─────────────────

/** Plan filter of the Entreprises page (select options, chips). `PAID` = legacy « Premium et essai ». */
export const TENANT_PLAN_FILTERS = ["ALL", "FREE", "PREMIUM_TRIAL", "PREMIUM"] as const;

export function planFilterLabel(code: string | null | undefined): string {
  if (code === "ALL") return "Tous";
  if (code === "PAID") return "Premium et essai";
  return planLabel(code);
}

// Copied from keevo/app/lib/features/audit/domain/audit_labels.dart (auditActionLabels, v1s-18-7),
// plus the server-only codes the admin log can contain (PRICE_CHANGED).
const AUDIT_ACTION_LABELS: Record<string, string> = {
  ACCOUNT_DELETION_CANCELLED: "Suppression du compte annulée",
  ACCOUNT_DELETION_REQUESTED: "Suppression du compte demandée",
  CATEGORY_ACTIVATED: "Catégorie activée",
  CATEGORY_CREATED: "Catégorie créée",
  CATEGORY_DEACTIVATED: "Catégorie désactivée",
  CATEGORY_RENAMED: "Catégorie renommée",
  CLIENT_ARCHIVED: "Client archivé",
  CLIENT_CREATED: "Client créé",
  DATA_EXPORTED: "Données exportées",
  DAY_CLOSED: "Journée clôturée",
  EMPLOYEE_CREATED: "Employé ajouté",
  EMPLOYEE_PASSWORD_SET_BY_OWNER: "Mot de passe défini",
  EMPLOYEE_ROLE_CHANGED: "Rôle modifié",
  EMPLOYEE_UPDATED: "Employé modifié",
  INVENTORY_COUNT_SAVED: "Comptage enregistré",
  INVENTORY_REPORT_GENERATED: "Rapport d'inventaire",
  INVENTORY_SESSION_CANCELLED: "Inventaire annulé",
  INVENTORY_SESSION_CREATED: "Inventaire lancé",
  INVENTORY_VALIDATED: "Inventaire validé",
  ONBOARDING_COMPLETED: "Configuration terminée",
  PASSWORD_RESET: "Mot de passe réinitialisé",
  PASSWORD_RESET_REQUESTED: "Réinitialisation demandée",
  PRICE_CHANGED: "Prix modifié",
  PRICE_OVERRIDDEN: "Prix modifié en caisse",
  PRODUCT_ACTIVATED: "Produit activé",
  PRODUCT_ARCHIVED: "Produit archivé",
  PRODUCT_CREATED: "Produit créé",
  PRODUCT_UPDATED: "Produit modifié",
  SALE_AUTO_VALIDATED: "Vente validée automatiquement",
  SALE_CANCELLED: "Vente annulée",
  SALE_COMPLETED: "Vente enregistrée",
  SALE_CORRECTED: "Vente corrigée",
  SALE_MANUALLY_VALIDATED: "Vente validée",
  SALE_PENDING_VALIDATION: "Vente en attente",
  STOCK_ADJUSTED: "Mouvement de stock",
  STOCK_FORCED_ZERO: "Stock forcé à zéro",
  STOCK_THRESHOLD_BREACHED: "Alerte de stock bas",
  STORE_CREATED: "Boutique créée",
  STORE_DEACTIVATED: "Boutique désactivée",
  STORE_UPDATED: "Boutique modifiée",
  SUPPLIER_ARCHIVED: "Fournisseur archivé",
  SUPPLIER_CREATED: "Fournisseur créé",
  USER_AUTHENTICATED: "Connexion",
  USER_REGISTERED: "Inscription",
};

/** Audit action → French title; an unknown code is humanized, never shown raw. */
export function auditActionLabel(code: string | null | undefined): string {
  return lookup(AUDIT_ACTION_LABELS, code, "Action");
}

// Copied from keevo/app/lib/features/audit/domain/audit_labels.dart (auditEntityTypeLabels).
const AUDIT_ENTITY_TYPE_LABELS: Record<string, string> = {
  Category: "Catégorie",
  Client: "Client",
  DayClosure: "Clôture de journée",
  Employee: "Employé",
  Export: "Export de données",
  InventoryCount: "Comptage d'inventaire",
  InventorySession: "Inventaire",
  PriceOverride: "Prix de vente",
  Product: "Produit",
  Sale: "Vente",
  StockAlert: "Alerte de stock",
  StockLevel: "Niveau de stock",
  StockMovement: "Mouvement de stock",
  Store: "Boutique",
  Supplier: "Fournisseur",
  Tenant: "Compte",
  User: "Compte utilisateur",
};

/** Audited object type (`Product`, `StockLevel`…) → French noun; unknown → « Élément ». */
export function auditEntityTypeLabel(code: string | null | undefined): string {
  const key = code?.trim();
  if (!key) return "Élément";
  const known = own(AUDIT_ENTITY_TYPE_LABELS, key);
  if (known) return known;
  // PascalCase → « Stock level », UPPER_SNAKE → humanized; anything else stays generic.
  if (/^[A-Z][a-zA-Z]*$/.test(key)) return humanizeCode(key.replace(/([a-z])([A-Z])/g, "$1_$2"));
  return isCode(key) ? humanizeCode(key) : "Élément";
}

const STORE_TYPE_LABELS: Record<string, string> = {
  STORE: "Boutique",
  WAREHOUSE: "Entrepôt",
};

export function storeTypeLabel(code: string | null | undefined): string {
  return lookup(STORE_TYPE_LABELS, code, "—");
}

// Mirrors keevo/app/lib/features/feedback/domain/model/feedback_type.dart.
const FEEDBACK_TYPE_LABELS: Record<string, string> = {
  SUGGESTION: "Suggestion",
  BUG: "Problème signalé",
  PRAISE: "Compliment",
};

/** Feedback type, case-insensitive (`bug` / `BUG`); any unknown type (code or free text) → « Autre ». */
export function feedbackTypeLabel(code: string | null | undefined): string {
  const key = code?.trim().toUpperCase();
  return (key && own(FEEDBACK_TYPE_LABELS, key)) || "Autre";
}

export const FEEDBACK_TYPES = ["SUGGESTION", "BUG", "PRAISE"] as const;

const PRIORITY_LABELS: Record<string, string> = {
  HIGH: "Haute",
  NORMAL: "Normale",
};

export function priorityLabel(code: string | null | undefined): string {
  return lookup(PRIORITY_LABELS, code, "—");
}

// Story 9-5 — severity of the platform alerts feed.
const ALERT_SEVERITY_LABELS: Record<string, string> = {
  HIGH: "Haute",
  MEDIUM: "Moyenne",
  LOW: "Basse",
};

export function alertSeverityLabel(code: string | null | undefined): string {
  return lookup(ALERT_SEVERITY_LABELS, code, "—");
}

const PLATFORM_LABELS: Record<string, string> = {
  ANDROID: "Android",
  IOS: "iOS",
  WEB: "Web",
};

export function platformLabel(code: string | null | undefined): string {
  return lookup(PLATFORM_LABELS, code, "—");
}

const UUID_SEGMENT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Segments of the app routes (keevo/app/lib/core/router/app_router.dart).
const SCREEN_SEGMENT_LABELS: Record<string, string> = {
  audit: "Historique des actions",
  entry: "Détail",
  "stock-movements": "Mouvements de stock",
  auth: "Connexion",
  "change-password": "Mot de passe",
  "forgot-password": "Mot de passe oublié",
  login: "Connexion",
  register: "Inscription",
  "reset-password": "Réinitialisation du mot de passe",
  clients: "Clients",
  new: "Nouveau",
  edit: "Modification",
  dashboard: "Tableau de bord",
  store: "Boutique",
  stores: "Boutiques",
  exports: "Exports",
  local: "Sur l'appareil",
  inventory: "Inventaire",
  counting: "Comptage",
  "gap-report": "Rapport d'écarts",
  notifications: "Notifications",
  onboarding: "Configuration",
  sector: "Secteur",
  "shop-name": "Nom de la boutique",
  pos: "Caisse",
  checkout: "Paiement",
  pending: "Ventes en attente",
  "sales-history": "Historique des ventes",
  success: "Vente terminée",
  products: "Produits",
  import: "Import",
  reports: "Rapports",
  boutiques: "Boutiques",
  history: "Historique",
  rentabilite: "Rentabilité",
  settings: "Paramètres",
  account: "Compte",
  delete: "Suppression",
  categories: "Catégories",
  feedback: "Avis",
  help: "Aide",
  subscription: "Abonnement",
  sync: "Synchronisation",
  conflicts: "Conflits",
  team: "Équipe",
  splash: "Démarrage",
  stock: "Stock",
  overview: "Vue d'ensemble",
  transfers: "Transferts",
  suppliers: "Fournisseurs",
  blocked: "Bloquée",
  "tenant-picker": "Choix de l'entreprise",
  terms: "Conditions d'utilisation",
  view: "Lecture",
  home: "Accueil",
};

/**
 * Screen of a feedback, from the app route (`/products/<uuid>/edit`): ids, numbers and query
 * strings are dropped, known segments are translated (« Produits › Modification »), unknown ones
 * humanized.
 */
export function screenLabel(route: string | null | undefined): string {
  const path = route?.split(/[?#]/)[0]?.trim();
  if (!path) return "—";
  const parts = path
    .split("/")
    .filter((s) => s && !UUID_SEGMENT.test(s) && !/\d/.test(s) && !s.startsWith(":"))
    .map((s) => own(SCREEN_SEGMENT_LABELS, s.toLowerCase()) ?? humanizeCode(s.replace(/-/g, "_")));
  return parts.length ? parts.join(" › ") : "Accueil";
}

/** Last active day (`yyyy-MM-dd`): « Aujourd'hui », « Hier », else dd/MM/yyyy; null → « Jamais ». */
export function lastActivityDayLabel(day: string | null | undefined, today: Date = new Date()): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(day ?? "");
  if (!match) return "Jamais";
  const [, y, m, d] = match;
  const key = (dt: Date) =>
    `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, "0")}-${String(dt.getDate()).padStart(2, "0")}`;
  const todayKey = key(today);
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  const value = `${y}-${m}-${d}`;
  if (value === todayKey) return "Aujourd'hui";
  if (value === key(yesterday)) return "Hier";
  return `${d}/${m}/${y}`;
}

// Story 9-5 (2/2) — global push notifications (« Notifications » page).
export const GLOBAL_TARGETS = ["ALL", "PREMIUM", "TRIAL", "FREE", "TENANT"] as const;

const GLOBAL_TARGET_LABELS: Record<string, string> = {
  ALL: "Tous",
  PREMIUM: "Premium",
  TRIAL: "Essai",
  FREE: "Gratuit",
  TENANT: "Un commerce",
};

/** Target of a global push; for one company, its business name when known. */
export function globalTargetLabel(code: string | null | undefined, tenantName?: string | null): string {
  if (code?.trim().toUpperCase() === "TENANT") {
    return tenantName?.trim() ? tenantName.trim() : DELETED_TENANT_LABEL;
  }
  return lookup(GLOBAL_TARGET_LABELS, code, "—");
}

/** Label of a target in the compose form select (« Un commerce » for TENANT). */
export function globalTargetOptionLabel(code: string): string {
  return lookup(GLOBAL_TARGET_LABELS, code, "—");
}

const GLOBAL_NOTIFICATION_STATUS_LABELS: Record<string, string> = {
  SCHEDULED: "Programmé",
  SENDING: "Envoi en cours",
  SENT: "Envoyé",
  FAILED: "Échec de l'envoi",
  CANCELLED: "Annulé",
};

export function globalNotificationStatusLabel(code: string | null | undefined): string {
  return lookup(GLOBAL_NOTIFICATION_STATUS_LABELS, code, "—");
}

const GLOBAL_FAILURE_REASON_LABELS: Record<string, string> = {
  PUSH_UNAVAILABLE: "Notifications push non configurées",
  ALL_CHUNKS_FAILED: "Échec d'envoi vers Firebase",
  INTERRUPTED: "Envoi interrompu",
  NO_RECIPIENT: "Aucun appareil ciblé",
};

/** Why a global push failed; null / unknown without a code → null (nothing shown). */
export function globalFailureReasonLabel(code: string | null | undefined): string | null {
  if (!code?.trim()) return null;
  return lookup(GLOBAL_FAILURE_REASON_LABELS, code, "—");
}

/** « 1 appareil » / « 12 appareils ». */
export function deviceCountLabel(n: number): string {
  return `${n} ${n > 1 ? "appareils" : "appareil"}`;
}
