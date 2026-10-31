/// Story 15.8 — every Flutter feature (`lib/features/<folder>`) and module
/// package (`packages/keevo_<x>`) attached to a backend module (manifest id,
/// `keevo/backend/src/main/resources/keevo/modules/<id>/module.json`).
///
/// [FeatureModules.module] is the feature's main module; a free module is
/// always on, so the feature itself is never gated. [FeatureModules.premium]
/// lists the surfaces of the feature that belong to another (premium) module:
/// those are gated — their route through the router's module guard
/// (`core/router/module_routes.dart`), their in-page entry through
/// `isRouteAvailable` / `isModuleAvailable`.
///
/// Documented in `docs/modules/flutter-feature-modules.md`.
/// `test/core/modules/feature_modules_test.dart` fails when a feature folder
/// or module package is missing here, when a module id is not a manifest id,
/// or when a surface route does not belong to its module.
library;

/// A surface of a feature that belongs to [module] (not the feature's main
/// module). [route]: the route that opens it (its module must own it in the
/// route registry); null for an in-page entry that opens no route of its
/// own (gated with `isModuleAvailable` / `isModuleActive`), or for a
/// [serverSide] surface (produced and gated by the server only, e.g. push
/// notifications the app merely displays).
class FeatureSurface {
  final String module;
  final String label;
  final String? route;
  final bool serverSide;

  const FeatureSurface(this.module, this.label,
      {this.route, this.serverSide = false});
}

class FeatureModules {
  /// Main module (manifest id).
  final String module;

  /// Surfaces of another module, gated.
  final List<FeatureSurface> premium;

  /// Mounted in debug builds only (`buildAppRoutes(includeDebug:)`).
  final bool debugOnly;

  const FeatureModules(this.module,
      {this.premium = const [], this.debugOnly = false});
}

/// Key: `lib/features` folder name, or module package name (`keevo_<x>`).
const kFeatureModules = <String, FeatureModules>{
  'account': FeatureModules('core'),
  'audit': FeatureModules('core'),
  'auth': FeatureModules('core'),
  'catalog': FeatureModules('catalog', premium: [
    FeatureSurface('crm', 'Fiche fournisseur du produit',
        route: '/suppliers/:id'),
    FeatureSurface('multi-store', 'Vue stock multi-boutiques',
        route: '/stock/overview'),
  ]),
  'contact': FeatureModules('crm'),
  'dashboard': FeatureModules('reporting', premium: [
    FeatureSurface('multi-store', 'Stock bas multi-boutiques',
        route: '/stock/overview'),
  ]),
  'debug': FeatureModules('core', debugOnly: true),
  'export': FeatureModules('reporting', premium: [
    FeatureSurface('exports', 'Mes exports', route: '/exports'),
  ]),
  'feedback': FeatureModules('core'),
  'help': FeatureModules('core'),
  'identity': FeatureModules('core'),
  'inventory': FeatureModules('inventory', premium: [
    FeatureSurface('counting', 'Inventaire', route: '/inventory'),
    FeatureSurface('multi-store', 'Vue stock multi-boutiques',
        route: '/stock/overview'),
    FeatureSurface('multi-store', 'Transferts', route: '/stock/transfers'),
  ]),
  'notifications': FeatureModules('messaging', premium: [
    FeatureSurface('alerts', 'Alertes du propriétaire (stock, produits)',
        serverSide: true),
  ]),
  'onboarding': FeatureModules('core'),
  'pos': FeatureModules('pos', premium: [
    FeatureSurface('crm', 'Client de la vente (encaissement)'),
    FeatureSurface('invoicing', 'Facturer (après la vente)'),
  ]),
  'profitability': FeatureModules('profitability', premium: [
    FeatureSurface('multi-store', 'Boutiques', route: '/reports/boutiques'),
    FeatureSurface('advanced-reports', 'Équipe (classement des vendeurs)',
        route: '/reports/equipe'),
  ]),
  'reports': FeatureModules('reporting', premium: [
    FeatureSurface('profitability', 'Marges', route: '/reports/rentabilite'),
    FeatureSurface('multi-store', 'Boutiques', route: '/reports/boutiques'),
    FeatureSurface('advanced-reports', 'Équipe', route: '/reports/equipe'),
  ]),
  'settings': FeatureModules('core', premium: [
    FeatureSurface('report-config', 'Rapports & WhatsApp',
        route: '/settings/reports'),
    FeatureSurface('advanced-reports',
        'Rapport hebdomadaire, son aperçu et les alertes de tendance'),
    FeatureSurface('alerts', 'Canal des alertes stock bas'),
    FeatureSurface('crm', 'Clients', route: '/clients'),
    FeatureSurface('crm', 'Fournisseurs', route: '/suppliers'),
    FeatureSurface('exports', 'Mes exports', route: '/exports'),
    FeatureSurface('counting', 'Inventaire', route: '/inventory'),
    FeatureSurface('multi-store', 'Vue stock multi-boutiques',
        route: '/stock/overview'),
    FeatureSurface('multi-store', 'Transferts', route: '/stock/transfers'),
    FeatureSurface('invoicing', 'Factures', route: '/invoicing'),
  ]),
  'stores': FeatureModules('store'),
  'sync_indicator': FeatureModules('sync'),
  'team': FeatureModules('core'),
  'keevo_invoicing': FeatureModules('invoicing'),
};
