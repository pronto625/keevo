import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/domain/model/membership_dto.dart';
import '../../features/auth/presentation/page/login_page.dart';
import '../../features/auth/presentation/page/password_change_page.dart';
import '../../features/auth/presentation/page/register_page.dart';
import '../../features/auth/presentation/page/tenant_picker_page.dart';
import '../../features/catalog/domain/model/product_model.dart';
import '../../features/catalog/presentation/page/catalog_page.dart';
import '../../features/catalog/presentation/page/csv_import_page.dart';
import '../../features/catalog/presentation/page/product_form_page.dart';
import '../../features/contact/domain/model/client_model.dart';
import '../../features/contact/domain/model/supplier_model.dart';
import '../../features/contact/presentation/page/client_form_page.dart';
import '../../features/contact/presentation/page/client_list_page.dart';
import '../../features/contact/presentation/page/supplier_form_page.dart';
import '../../features/contact/presentation/page/supplier_list_page.dart';
import '../../features/debug/presentation/page/category_debug_page.dart';
import '../../features/onboarding/domain/model/sector_type.dart';
import '../../features/onboarding/presentation/page/onboarding_page.dart';
import '../../features/onboarding/presentation/page/sector_selection_page.dart';
import '../../features/onboarding/presentation/page/shop_name_page.dart';
import '../../features/onboarding/presentation/page/terms_page.dart';
import '../../features/pos/presentation/page/pos_page.dart';
import '../../features/pos/presentation/page/checkout_page.dart';
import '../../features/pos/presentation/page/pending_sales_page.dart';
import '../../features/pos/presentation/page/pending_sale_detail_page.dart';
import '../../features/pos/presentation/page/sale_success_page.dart';
import '../../features/pos/presentation/page/sales_history_page.dart';
import '../../features/pos/presentation/page/sale_detail_page.dart';
import '../../features/pos/domain/model/sale_model.dart';
import '../../features/settings/presentation/page/settings_page.dart';
import '../../features/settings/presentation/page/subscription_page.dart';
import '../../features/stores/presentation/page/stores_list_page.dart';
import '../../features/inventory/presentation/page/global_stock_overview_page.dart';
import '../../features/inventory/presentation/page/transfer_history_page.dart';
import '../../features/team/presentation/page/team_page.dart';
import '../../features/audit/presentation/page/audit_page.dart';
import '../../features/team/presentation/page/create_employee_page.dart';
import '../di/providers.dart';
import '../scaffold/main_shell.dart';
import '../storage/app_constants.dart';

/// Returns true only if [token] is a structurally valid JWT **and** its `exp`
/// claim is in the future. Expired or malformed tokens return false.
bool _isValidJwt(String token) {
  final parts = token.split('.');
  if (parts.length != 3 || !token.startsWith('eyJ')) return false;
  try {
    final payload = parts[1];
    final decoded = utf8.decode(
      base64Url.decode(base64Url.normalize(payload)),
    );
    final data = jsonDecode(decoded) as Map<String, dynamic>;
    final exp = data['exp'] as int?;
    if (exp == null) return false;
    return DateTime.now().isBefore(
      DateTime.fromMillisecondsSinceEpoch(exp * 1000),
    );
  } catch (_) {
    return false;
  }
}

/// Returns true if the JWT payload contains `tenantStatus == "ACTIVE"`,
/// meaning the onboarding wizard has already been completed on the backend.
bool _isTenantActive(String token) {
  final parts = token.split('.');
  if (parts.length != 3) return false;
  try {
    final payload = parts[1];
    final decoded = utf8.decode(
      base64Url.decode(base64Url.normalize(payload)),
    );
    final data = jsonDecode(decoded) as Map<String, dynamic>;
    return data['tenantStatus'] == 'ACTIVE';
  } catch (_) {
    return false;
  }
}

/// Placeholder page shown until each feature is implemented
class _PlaceholderPage extends StatelessWidget {
  final String title;
  const _PlaceholderPage({required this.title});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Center(
        child: Text(
          title,
          style: Theme.of(context).textTheme.headlineMedium,
        ),
      ),
    );
  }
}

/// Écran de démarrage invisible — redirige vers l'onboarding au premier
/// lancement ou directement vers l'inscription si déjà vu.
class _SplashRedirectPage extends ConsumerStatefulWidget {
  const _SplashRedirectPage();

  @override
  ConsumerState<_SplashRedirectPage> createState() =>
      _SplashRedirectPageState();
}

class _SplashRedirectPageState extends ConsumerState<_SplashRedirectPage> {
  @override
  void initState() {
    super.initState();
    // Prewarm the encrypted DB at splash — forces SQLCipher init logs to appear
    // on every cold start rather than waiting for the first actual DB operation.
    ref.read(appDatabaseProvider);
    _redirect();
  }

  Future<void> _redirect() async {
    const storage = FlutterSecureStorage();
    final token = await storage.read(key: 'jwt_token');

    if (token != null && _isValidJwt(token)) {
      if (!mounted) return;
      final prefs = await SharedPreferences.getInstance();
      var wizardSeen = prefs.getBool(kOnboardingWizardSeenKey) ?? false;

      // If the flag is missing locally, fall back to the JWT payload:
      // tenantStatus == "ACTIVE" means onboarding was already completed on
      // a previous install / after a data-clear. Mark it locally and skip
      // the wizard so the user is never stuck in the onboarding loop.
      if (!wizardSeen) {
        wizardSeen = _isTenantActive(token);
        if (wizardSeen) {
          await prefs.setBool(kOnboardingWizardSeenKey, true);
        }
      }

      if (!mounted) return;

      // Story 3.5 AC4: password change guard — redirect employee to
      // change-password page before any other screen is accessible.
      if (wizardSeen) {
        final pwdChangeRequired =
            prefs.getBool(kPasswordChangeRequiredKey) ?? false;
        if (pwdChangeRequired) {
          context.go('/auth/change-password');
          return;
        }
      }

      context.go(wizardSeen ? '/pos' : '/onboarding/sector');
      return;
    }

    if (token != null) await storage.delete(key: 'jwt_token');

    final prefs = await SharedPreferences.getInstance();
    final onboardingSeen = prefs.getBool(kOnboardingSeenKey) ?? false;
    if (!mounted) return;

    if (!onboardingSeen) {
      context.go('/onboarding');
    } else {
      context.go('/auth/login');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: const Center(child: CircularProgressIndicator()),
    );
  }
}

/// OWNER-only route prefixes — EMPLOYEE users are redirected to /pos (AC5).
const _ownerOnlyPrefixes = [
  '/products',
  '/clients',
  '/suppliers',
  '/stores',
  '/settings/subscription',
  '/settings/team',
  '/reports',
  '/audit',
];

/// Application router — all feature routes registered here.
/// Each route points to a placeholder until the feature story is implemented.
///
/// AC5 — EMPLOYEE role-based restriction (layered enforcement):
/// 1. Route guard: top-level redirect below blocks EMPLOYEE from OWNER-only routes
/// 2. Navigation bar filtering in MainShell (EMPLOYEE sees only Caisse + Plus)
/// 3. Settings tiles hiding in SettingsPage (Gestion section hidden for EMPLOYEE)
/// 4. Backend enforcement via JwtAuthFilter (403 for EMPLOYEE on OWNER-only endpoints)
final GoRouter appRouter = GoRouter(
  initialLocation: '/splash',
  redirect: (context, state) async {
    final path = state.uri.path;
    if (!_ownerOnlyPrefixes.any((prefix) => path.startsWith(prefix))) {
      return null;
    }
    final prefs = await SharedPreferences.getInstance();
    final role = prefs.getString(kUserRoleKey);
    if (role == 'EMPLOYEE') return '/pos';
    return null;
  },
  routes: [
    // ── Splash / redirect logique premier lancement ──────────
    GoRoute(
      path: '/splash',
      builder: (_, __) => const _SplashRedirectPage(),
    ),

    // ── Auth ────────────────────────────────────────────────
    GoRoute(
      path: '/auth/login',
      redirect: (context, state) async {
        // AC3: skip login only when a valid, non-expired JWT is present
        const storage = FlutterSecureStorage();
        final token = await storage.read(key: 'jwt_token');
        if (token != null && _isValidJwt(token)) return '/pos';
        return null; // proceed to LoginPage
      },
      builder: (_, __) => const LoginPage(),
    ),
    GoRoute(
      path: '/auth/register',
      builder: (_, __) => const RegisterPage(),
    ),

    // ── Password change (Story 3.5 — full-screen, no bottom nav) ─────────
    GoRoute(
      path: '/auth/change-password',
      builder: (_, __) => const PasswordChangePage(),
    ),

    // ── Tenant picker (Story 1.7 — AC9: multi-membership users) ─────────
    GoRoute(
      path: '/tenant-picker',
      builder: (_, state) {
        final extra = state.extra as Map<String, dynamic>?;
        if (extra == null) return const LoginPage();
        return TenantPickerPage(
          loginToken: extra['loginToken'] as String,
          memberships: (extra['memberships'] as List).cast<MembershipDto>(),
        );
      },
    ),

    // ── Onboarding ──────────────────────────────────────────
    GoRoute(
      path: '/onboarding',
      builder: (_, __) => const OnboardingPage(),
    ),

    // ── Terms & Privacy ─────────────────────────────────────
    GoRoute(
      path: '/terms',
      builder: (_, __) => const TermsPage(),
    ),

    // ── Onboarding wizard ──────────────────────────────────────────
    GoRoute(
      path: '/onboarding/sector',
      builder: (_, __) => const SectorSelectionPage(),
    ),
    GoRoute(
      path: '/onboarding/shop-name',
      builder: (context, state) {
        final sector = state.extra;
        if (sector == null || sector is! SectorType) {
          // Guard: deep-link or process-kill — restart the wizard from sector selection
          return const SectorSelectionPage();
        }
        return ShopNamePage(sectorType: sector);
      },
    ),

    // ── Main shell — persistent BottomNavigationBar ─────────────────────────
    // Wraps Caisse / Catalogue / Clients / Fournisseurs with a shared nav bar.
    // Sub-routes (form pages) are declared outside this shell so they push
    // as full-screen pages without the nav bar.
    ShellRoute(
      builder: (context, state, child) => MainShell(child: child),
      routes: [
        // ── Caisse (POS) ───────────────────────────────────────────────────
        GoRoute(
          path: '/pos',
          builder: (_, __) => const PosPage(),
        ),

        // ── Catalogue ──────────────────────────────────────────────────────
        GoRoute(
          path: '/products',
          builder: (_, __) => const CatalogPage(),
        ),

        // ── Clients ────────────────────────────────────────────────────────
        GoRoute(
          path: '/clients',
          builder: (_, __) => const ClientListPage(),
        ),

        // ── Fournisseurs ───────────────────────────────────────────────────
        GoRoute(
          path: '/suppliers',
          builder: (_, __) => const SupplierListPage(),
        ),

        // ── Plus > Paramètres (UX spec: 4ème onglet ‘Plus’) ──────────────────────
        GoRoute(
          path: '/settings',
          builder: (_, __) => const SettingsPage(),
        ),
      ],
    ),

    // ── POS sub-routes (full-screen, no nav bar) ──────────────────────────
    GoRoute(
      path: '/pos/checkout',
      builder: (_, __) => const CheckoutPage(),
    ),
    GoRoute(
      path: '/pos/success',
      builder: (_, state) {
        final totalStr = state.uri.queryParameters['total'];
        final total = int.tryParse(totalStr ?? '') ?? 0;
        final status = state.uri.queryParameters['status'] ?? 'COMPLETED';
        return SaleSuccessPage(totalAmount: total, saleStatus: status);
      },
    ),

    // ── Pending sales (OWNER only) — Story 4.3 ──────────────────────────
    GoRoute(
      path: '/pos/pending',
      builder: (_, __) => const PendingSalesPage(),
    ),
    GoRoute(
      path: '/pos/pending/:id',
      builder: (_, state) {
        final saleId = state.pathParameters['id'] ?? '';
        final sale = state.extra;
        return PendingSaleDetailPage(saleId: saleId, sale: sale as Sale?);
      },
    ),

    // ── Sales History (Story 4.4 AC7) ────────────────────────────────────
    GoRoute(
      path: '/pos/sales-history',
      builder: (_, __) => const SalesHistoryPage(),
    ),
    GoRoute(
      path: '/pos/sales-history/:id',
      builder: (_, state) {
        final saleId = state.pathParameters['id'] ?? '';
        return SaleDetailPage(saleId: saleId);
      },
    ),

    // ── Products sub-routes (full-screen, no nav bar) ─────────────────────
    GoRoute(
      path: '/products/import',
      builder: (_, __) => const CsvImportPage(),
    ),
    GoRoute(
      path: '/products/new',
      builder: (_, __) => const ProductFormPage(),
    ),
    GoRoute(
      path: '/products/:id/edit',
      builder: (_, state) {
        final product = state.extra;
        if (product is! ProductModel) return const CatalogPage();
        return ProductFormPage(product: product);
      },
    ),

    // ── Clients sub-routes (full-screen, no nav bar) ─────────────────────
    GoRoute(
      path: '/clients/new',
      builder: (_, __) => const ClientFormPage(),
    ),
    GoRoute(
      path: '/clients/:id',
      builder: (_, state) {
        final client = state.extra;
        if (client is! ClientModel) return const ClientListPage();
        return ClientFormPage(existing: client);
      },
    ),

    // ── Suppliers sub-routes (full-screen, no nav bar) ────────────────────
    GoRoute(
      path: '/suppliers/new',
      builder: (_, __) => const SupplierFormPage(),
    ),
    GoRoute(
      path: '/suppliers/:id',
      builder: (_, state) {
        final supplier = state.extra;
        if (supplier is! SupplierModel) return const SupplierListPage();
        return SupplierFormPage(supplier: supplier);
      },
    ),

    // ── Inventory ───────────────────────────────────────────
    GoRoute(
      path: '/inventory',
      builder: (_, __) => const _PlaceholderPage(title: 'Inventory'),
    ),

    GoRoute(
      path: '/reports',
      builder: (_, __) => const _PlaceholderPage(title: 'Reports'),
    ),

    // ── Settings > Boutiques (full-screen, nav bar hidden) ────────────────
    GoRoute(
      path: '/stores',
      builder: (_, __) => const StoresListPage(),
    ),
    // ── Settings > Subscription (full-screen, nav bar hidden) ────────────
    GoRoute(
      path: '/settings/subscription',
      builder: (_, __) => const SubscriptionPage(),
    ),
    // ── Settings > Team (Story 3.5 — full-screen, nav bar hidden) ────────
    GoRoute(
      path: '/settings/team',
      builder: (_, __) => const TeamPage(),
    ),
    GoRoute(
      path: '/settings/team/new',
      builder: (_, __) => const CreateEmployeePage(),
    ),
    // ── Audit trail (OWNER only — full-screen, no nav bar) ────────────────
    GoRoute(
      path: '/audit',
      builder: (_, __) => const AuditPage(),
    ),
    // ── Stock overview (full-screen, no nav bar) ─────────────────────────
    GoRoute(
      path: '/stock/overview',
      builder: (_, __) => const GlobalStockOverviewPage(),
    ),    // ── Transfer history (Story 3.3) ─────────────────────────────────
    GoRoute(
      path: '/stock/transfers',
      builder: (_, __) => const TransferHistoryPage(),
    ),    // ── Debug > Categories ──────────────────────────────────────
    GoRoute(
      path: '/debug/categories',
      builder: (_, __) => const CategoryDebugPage(),
    ),
  ],
);
