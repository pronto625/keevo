import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/domain/model/membership_dto.dart';
import '../../features/auth/presentation/page/auth_page.dart';
import '../../features/auth/presentation/page/password_change_page.dart';
import '../../features/auth/presentation/page/tenant_picker_page.dart';
import '../../features/auth/presentation/page/forgot_password_page.dart';
import '../../features/auth/presentation/page/reset_password_page.dart';
import '../../features/auth/presentation/provider/auth_provider.dart';
import '../../features/auth/presentation/provider/session_claims_applier.dart';
import '../../features/catalog/domain/model/product_model.dart';
import '../../features/catalog/presentation/page/catalog_page.dart';
import '../../features/catalog/presentation/page/categories_page.dart';
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
import '../../features/reports/presentation/page/report_history_page.dart';
import '../../features/reports/presentation/page/report_detail_page.dart';
import '../../features/reports/presentation/page/owner_reports_page.dart';
import '../../features/reports/domain/model/report_history_model.dart';
import '../../features/profitability/presentation/page/product_profitability_detail_page.dart';
import '../../features/auth/presentation/page/user_password_change_page.dart';
import '../../features/account/presentation/page/delete_account_page.dart';
import '../../features/feedback/presentation/page/feedback_form_page.dart';
import '../../features/settings/presentation/page/account_page.dart';
import '../../features/settings/presentation/page/settings_page.dart';
import '../../features/settings/presentation/page/subscription_page.dart';
import '../../features/settings/presentation/page/report_preferences_page.dart';
import '../../features/sync_indicator/presentation/page/blocked_operations_page.dart';
import '../../features/sync_indicator/presentation/page/sync_conflict_log_page.dart';
import '../../features/sync_indicator/presentation/page/sync_settings_page.dart';
import '../sync/sync_trigger_notifier.dart';
import '../../features/stores/presentation/page/stores_list_page.dart';
import '../../features/inventory/presentation/page/global_stock_overview_page.dart';
import '../../features/inventory/presentation/page/inventory_counting_page.dart';
import '../../features/inventory/presentation/page/inventory_gap_report_page.dart';
import '../../features/inventory/presentation/page/inventory_launch_page.dart';
import '../../features/inventory/presentation/page/transfer_history_page.dart';
import '../../features/team/presentation/page/team_page.dart';
import '../../features/audit/presentation/page/audit_page.dart';
import '../../features/notifications/presentation/page/notifications_page.dart';
import '../../features/dashboard/presentation/page/dashboard_page.dart';
import '../../features/dashboard/presentation/page/store_dashboard_page.dart';
import '../../features/team/domain/model/employee_model.dart';
import '../../features/team/presentation/page/create_employee_page.dart';
import '../../features/team/presentation/page/edit_employee_page.dart';
import '../di/providers.dart';
import '../scaffold/main_shell.dart';
import '../storage/app_constants.dart';
import 'startup_route.dart';

/// Global ScaffoldMessenger key — used by AuthInterceptor.onAccountSuspended
/// to show a SnackBar regardless of which page the user is on when their
/// session is revoked or account deactivated (Story 12.7 AC4).
final rootScaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();

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
    String route;
    Object? extra;
    try {
      route = await resolveStartupRoute(
        storage: ref.read(flutterSecureStorageProvider),
        prefs: await SharedPreferences.getInstance(),
        refresher: ref.read(sessionRefresherProvider),
        applyClaims: (token) =>
            ref.read(sessionClaimsApplierProvider).apply(token),
      );
    } catch (_) {
      // Storage or local DB failure: never leave the user on the spinner.
      // The marker stops /auth/login from sending a session back here
      // (v1s-16-13: that looped splash → login → splash).
      route = '/auth/login';
      extra = kSplashFailedExtra;
    }
    if (!mounted) return;

    // Story 16.8 AC1: pull latest server state at cold start once signed in —
    // fire-and-forget, do NOT block navigation.
    try {
      if (await hasSession(ref.read(flutterSecureStorageProvider))) {
        ref.read(syncTriggerNotifierProvider.notifier).onAppStartup();
      }
    } catch (_) {
      // Storage unreadable: the startup pull simply does not run.
    }
    if (!mounted) return;
    context.go(route, extra: extra);
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
/// Note: catalogue routes (/products, /products/:id/edit, /products/new,
/// /products/import) are intentionally absent — the catalogue is identical
/// for OWNER and EMPLOYEE (HF-2 B4 fix). Backend RBAC (403) enforces
/// authorization at the API level.
const _ownerOnlyPrefixes = [
  '/dashboard',
  '/clients',
  '/suppliers',
  '/stores',
  '/settings/subscription',
  '/settings/team',
  '/settings/sync',
  '/settings/categories',
  '/audit',
  '/reports/rentabilite',
  '/reports/boutiques',
  '/settings/reports',
];

/// S12 (Story 12.7 AC5): Global auth guard — any deep link to a non-public route
/// without a session is sent to /splash, which decides where to go. EMPLOYEE
/// users are kept out of OWNER-only routes (AC5).
///
/// Public routes are reachable signed out. /terms must be: the splash sends a
/// signed-out user there before registration, so guarding it would loop
/// /splash → /terms → /splash. /tenant-picker too: a multi-membership user
/// reaches it before any JWT exists (the login token only lives in the route
/// extra), and without that extra the route falls back to the login page.
/// /auth/change-password requires a session and is not public.
@visibleForTesting
Future<String?> globalAuthRedirect(
  String path, {
  required Future<bool> Function() hasSession,
  required Future<String?> Function() readRole,
}) async {
  const publicPaths = {
    '/splash',
    '/terms',
    '/terms/view',
    '/auth/login',
    '/auth/register',
    '/auth/forgot-password',
    '/auth/reset-password',
    '/tenant-picker',
  };
  const publicPathPrefixes = ['/onboarding'];
  final isPublicPath = publicPaths.contains(path) ||
      publicPathPrefixes.any((p) => path.startsWith(p));
  try {
    if (!isPublicPath && !await hasSession()) return '/splash';

    final isOwnerOnlyPath =
        _ownerOnlyPrefixes.any((prefix) => path.startsWith(prefix));
    if (!isOwnerOnlyPath) return null;
    final role = await readRole();
    if (role == 'OWNER') return null;
    // v1s-16-13: a missing role is not a pass. The splash re-applies the JWT
    // claims (rewrites the role) or falls back to login when signed out.
    if (role == null) return '/splash';
    return '/pos';
  } catch (_) {
    // Storage unreadable: let the splash decide (it falls back to login).
    return path == '/splash' ? null : '/splash';
  }
}

/// Route extra the splash passes to `/auth/login` when it failed.
const kSplashFailedExtra = {'splashFailed': true};

/// v1s-16-13: `/auth/login` with a session goes through the splash, which
/// decides between the setup wizard, the forced password change and the
/// role's home — never straight to the dashboard.
///
/// [extra] carrying [kSplashFailedExtra] means the splash itself failed:
/// the login form is shown, otherwise splash and login would loop.
@visibleForTesting
Future<String?> loginRouteRedirect({
  required Future<bool> Function() hasSession,
  Object? extra,
}) async {
  if (extra is Map && extra['splashFailed'] == true) return null;
  try {
    return await hasSession() ? '/splash' : null;
  } catch (_) {
    return null; // storage unreadable: show the login form
  }
}

/// v1s-16-13: the reset page needs the phone the code was sent to. A deep
/// link without it (no extra, or no non-empty `phoneNumber`) restarts the
/// flow from the forgot-password page.
@visibleForTesting
String? resetPasswordRedirect(Object? extra) {
  final phone = extra is Map ? extra['phoneNumber'] : null;
  if (phone is String && phone.trim().isNotEmpty) return null;
  return '/auth/forgot-password';
}

/// v1s-16-13: builder of `/terms/view` — read-only CGU, opened at the
/// privacy policy with `?section=privacy`.
@visibleForTesting
Widget termsViewPage(Uri uri) => TermsPage(
      readOnly: true,
      scrollToPrivacy: uri.queryParameters['section'] == 'privacy',
    );

/// Application router — all feature routes registered here.
/// Each route points to a placeholder until the feature story is implemented.
///
/// AC5 — EMPLOYEE role-based restriction (layered enforcement):
/// 1. Route guard: top-level redirect below blocks EMPLOYEE from OWNER-only routes
/// 2. Navigation bar filtering in MainShell (EMPLOYEE sees only Caisse + Plus)
/// 3. Settings tiles hiding in SettingsPage (Gestion section hidden for EMPLOYEE)
/// 4. Backend enforcement via JwtAuthFilter (403 for EMPLOYEE on OWNER-only endpoints)
///
/// S12 (Story 12.7 AC5): Global auth guard — see [globalAuthRedirect].
final GoRouter appRouter = GoRouter(
  initialLocation: '/splash',
  errorBuilder: (context, state) {
    // Unknown deepLink (e.g. stale notification) — fall back to splash
    // which re-evaluates auth state and routes accordingly.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      GoRouter.of(context).go('/splash');
    });
    return const Scaffold(
      body: Center(child: CircularProgressIndicator()),
    );
  },
  redirect: (context, state) async {
    final storage = ProviderScope.containerOf(context, listen: false)
        .read(flutterSecureStorageProvider);
    return globalAuthRedirect(
      state.uri.path,
      hasSession: () => hasSession(storage),
      readRole: () async =>
          (await SharedPreferences.getInstance()).getString(kUserRoleKey),
    );
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
      redirect: (context, state) {
        // AC3: skip login while the device holds a session (renewable through
        // the refresh token even when the access token has expired). The
        // splash picks the destination (v1s-16-13): wizard, forced password
        // change, then OWNER → /dashboard, EMPLOYEE → /pos.
        final storage = ProviderScope.containerOf(context, listen: false)
            .read(flutterSecureStorageProvider);
        return loginRouteRedirect(
          hasSession: () => hasSession(storage),
          extra: state.extra,
        );
      },
      builder: (_, __) => const AuthPage(initialMode: AuthMode.login),
    ),
    GoRoute(
      path: '/auth/register',
      // Account creation requires the CGU (UI-1 flow); enforced here so a
      // deep link cannot skip them.
      redirect: (_, __) async {
        final prefs = await SharedPreferences.getInstance();
        return (prefs.getBool(kTermsAcceptedKey) ?? false) ? null : '/terms';
      },
      builder: (_, __) => const AuthPage(initialMode: AuthMode.register),
    ),

    // ── Forgot/Reset password (Story 14.12 — public, no auth) ──────────
    GoRoute(
      path: '/auth/forgot-password',
      builder: (_, __) => const ForgotPasswordPage(),
    ),
    GoRoute(
      path: '/auth/reset-password',
      redirect: (_, state) => resetPasswordRedirect(state.extra),
      builder: (_, state) => ResetPasswordPage(
        phoneNumber:
            (state.extra as Map?)?['phoneNumber'] as String?,
      ),
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
        if (extra == null) return const AuthPage(initialMode: AuthMode.login);
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
    // v1s-16-13: read-only CGU / privacy view opened from the register form.
    GoRoute(
      path: '/terms/view',
      builder: (_, state) => termsViewPage(state.uri),
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
    // Wraps Caisse / Catalogue / Rapports / Plus with a shared nav bar.
    // Clients & Fournisseurs accessible from Plus page.
    ShellRoute(
      builder: (context, state, child) => MainShell(child: child),
      routes: [
        GoRoute(
          path: '/dashboard',
          builder: (_, __) => const DashboardPage(),
        ),
        GoRoute(
          path: '/pos',
          builder: (_, __) => const PosPage(),
        ),
        GoRoute(
          path: '/products',
          builder: (_, __) => const CatalogPage(),
        ),
        GoRoute(
          path: '/reports',
          builder: (context, __) {
            final role = ProviderScope.containerOf(context)
                .read(currentUserRoleProvider);
            return role == 'OWNER'
                ? const OwnerReportsPage()
                : const EmployeeReportsPage(); // B3.3: Jour + Brouillons for EMPLOYEE
          },
        ),
        GoRoute(
          path: '/settings',
          builder: (_, __) => const SettingsPage(),
        ),
      ],
    ),

    // ── Dashboard > Store sub-dashboard (full-screen, no nav bar) ────────
    GoRoute(
      path: '/dashboard/store/:storeId',
      builder: (_, state) {
        final storeId = state.pathParameters['storeId'] ?? '';
        return StoreDashboardPage(storeId: storeId);
      },
    ),

    // ── Clients (full-screen, accessed from Plus) ─────────────────────────
    GoRoute(
      path: '/clients',
      builder: (_, __) => const ClientListPage(),
    ),

    // ── Fournisseurs (full-screen, accessed from Plus) ────────────────────
    GoRoute(
      path: '/suppliers',
      builder: (_, __) => const SupplierListPage(),
    ),

    // ── Catégories (full-screen, accessed from Plus) ──────────────────────
    GoRoute(
      path: '/settings/categories',
      builder: (_, __) => const CategoriesPage(),
    ),


    // ── Report history (full-screen, no nav bar) — Story 7.2 ─────────────
    GoRoute(
      path: '/reports/history',
      builder: (_, state) {
        final storeId = state.uri.queryParameters['storeId'];
        final adminMode = state.uri.queryParameters['adminMode'] == 'true';
        return ReportHistoryPage(storeId: storeId, adminMode: adminMode);
      },
    ),
    GoRoute(
      path: '/reports/history/:reportId',
      builder: (_, state) {
        final reportId = state.pathParameters['reportId']!;
        final report = state.extra as ReportHistoryModel?;
        return ReportDetailPage(reportId: reportId, report: report);
      },
    ),

    // ── Profitability deep links (AC7, Story 7.4) — open the Rapports hub on
    // the right tab. OWNER-only via _ownerOnlyPrefixes.
    GoRoute(
      path: '/reports/rentabilite',
      builder: (_, __) => const OwnerReportsPage(
          initialTab: OwnerReportsPage.tabRentabilite),
    ),
    GoRoute(
      path: '/reports/boutiques',
      builder: (_, __) => const OwnerReportsPage(
          initialTab: OwnerReportsPage.tabBoutiques),
    ),

    // ── Profitability detail (full-screen, no nav bar) — Story 7.4 ────────
    GoRoute(
      path: '/reports/rentabilite/:productId',
      builder: (_, state) {
        final productId = state.pathParameters['productId']!;
        return ProductProfitabilityDetailPage(productId: productId);
      },
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
      builder: (_, state) {
        final storeId = state.uri.queryParameters['storeId'];
        final adminMode = state.uri.queryParameters['adminMode'] == 'true';
        return SalesHistoryPage(storeId: storeId, adminMode: adminMode);
      },
    ),
    GoRoute(
      path: '/pos/sales-history/:id',
      builder: (_, state) {
        final saleId = state.pathParameters['id'] ?? '';
        final sale = state.extra as Sale?;
        return SaleDetailPage(saleId: saleId, sale: sale);
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
      builder: (_, __) => const InventoryLaunchPage(),
    ),
    GoRoute(
      path: '/inventory/counting/:sessionId',
      builder: (_, state) {
        final sessionId = state.pathParameters['sessionId']!;
        return InventoryCountingPage(sessionId: sessionId);
      },
    ),
    GoRoute(
      path: '/inventory/gap-report/:sessionId',
      builder: (_, state) {
        final sessionId = state.pathParameters['sessionId']!;
        return InventoryGapReportPage(sessionId: sessionId);
      },
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
    // ── Settings > Team > Edit (Story 14.11, FR66 — OWNER only) ──────────
    GoRoute(
      path: '/settings/team/:employeeId/edit',
      builder: (_, state) {
        final employee = state.extra as EmployeeModel?;
        // Deep link without the employee in `extra`: back to the list.
        if (employee == null) return const TeamPage();
        return EditEmployeePage(employee: employee);
      },
    ),
    // ── Settings > Sync Conflicts (Story 5.3 — OWNER only) ────────────────
    GoRoute(
      path: '/settings/sync/conflicts',
      builder: (_, __) => const SyncConflictLogPage(),
    ),
    // ── Settings > Sync Monitoring (Story 5.5 — OWNER only) ──────────────
    GoRoute(
      path: '/settings/sync',
      builder: (_, __) => const SyncSettingsPage(),
    ),
    // ── Blocked (quarantined) sync operations (v1s-16-15 — all roles) ──────
    GoRoute(
      path: '/sync/blocked',
      builder: (_, __) => const BlockedOperationsPage(),
    ),
    // ── Settings > Report Preferences (Story 7.5 — OWNER only) ───────────
    GoRoute(
      path: '/settings/reports',
      builder: (_, __) => const ReportPreferencesPage(),
    ),
    // ── Settings > Mon Compte (Story 8.6 — OWNER + EMPLOYEE) ─────────────
    GoRoute(
      path: '/settings/account',
      builder: (_, __) => const AccountPage(),
    ),
    // ── Settings > Change Password (Story 8.6 — OWNER + EMPLOYEE) ────────
    GoRoute(
      path: '/settings/change-password',
      builder: (_, __) => const UserPasswordChangePage(),
    ),
    // ── Settings > Mon Compte > Suppression (Story 14.5 FR91 — OWNER) ────
    GoRoute(
      path: '/settings/account/delete',
      builder: (_, __) => const DeleteAccountPage(),
    ),
    // ── Settings > Feedback (Story 14.5 FR92 — OWNER + EMPLOYEE) ─────────
    // `from` = path of the screen the user was on when tapping the tile.
    GoRoute(
      path: '/settings/feedback',
      builder: (_, state) => FeedbackFormPage(
        screenContext: state.uri.queryParameters['from'],
      ),
    ),
    // ── Audit trail (OWNER only — full-screen, no nav bar) ────────────────
    GoRoute(
      path: '/audit',
      builder: (_, __) => const AuditPage(),
    ),
    // ── Notifications (Story 8.0 — full-screen, no nav bar) ──────────────
    GoRoute(
      path: '/notifications',
      builder: (_, __) => const NotificationsPage(),
    ),
    // ── Stock overview (full-screen, no nav bar) ─────────────────────────
    GoRoute(
      path: '/stock/overview',
      builder: (context, state) {
        final showLowOnly =
            state.uri.queryParameters['showLowOnly'] == 'true';
        return GlobalStockOverviewPage(showLowOnly: showLowOnly);
      },
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
