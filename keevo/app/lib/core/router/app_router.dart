import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/domain/model/membership_dto.dart';
import '../../features/auth/presentation/page/auth_page.dart';
import '../../features/auth/presentation/page/password_change_page.dart';
import '../../features/auth/presentation/page/tenant_picker_page.dart';
import '../../features/auth/presentation/page/forgot_password_page.dart';
import '../../features/auth/presentation/page/reset_password_page.dart';
import '../../features/auth/presentation/provider/auth_provider.dart';
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
import '../../features/settings/presentation/page/account_page.dart';
import '../../features/settings/presentation/page/settings_page.dart';
import '../../features/settings/presentation/page/subscription_page.dart';
import '../../features/settings/presentation/page/report_preferences_page.dart';
import '../../features/sync_indicator/presentation/page/sync_conflict_log_page.dart';
import '../../features/sync_indicator/presentation/page/sync_settings_page.dart';
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
import '../../features/team/presentation/page/create_employee_page.dart';
import '../di/providers.dart';
import '../scaffold/main_shell.dart';
import '../../features/stores/presentation/provider/active_store_provider.dart';
import '../storage/app_constants.dart';

// Mirrors the API_BASE_URL from auth_provider.dart so the splash can perform
// a proactive token refresh without importing auth_provider.
const _kApiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'https://localhost:4500',
);

/// Global ScaffoldMessenger key — used by AuthInterceptor.onAccountSuspended
/// to show a SnackBar regardless of which page the user is on when their
/// session is revoked or account deactivated (Story 12.7 AC4).
final rootScaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();

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

/// Decodes the JWT payload without checking expiry. Returns null for
/// malformed tokens. Used to distinguish "expired but valid structure"
/// from garbage, enabling proactive silent refresh on splash.
Map<String, dynamic>? _decodeJwtPayload(String token) {
  final parts = token.split('.');
  if (parts.length != 3 || !token.startsWith('eyJ')) return null;
  try {
    final payload = parts[1];
    final decoded = utf8.decode(
      base64Url.decode(base64Url.normalize(payload)),
    );
    return jsonDecode(decoded) as Map<String, dynamic>;
  } catch (_) {
    return null;
  }
}

/// Attempts a silent token refresh using the stored refresh token.
/// Returns the new access token on success, null on any failure.
/// Persists new tokens to secure storage on success.
Future<String?> _tryProactiveRefresh(FlutterSecureStorage storage) async {
  final refreshToken = await storage.read(key: 'refresh_token');
  if (refreshToken == null) return null;
  try {
    final dio = Dio(BaseOptions(
      baseUrl: _kApiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
    ));
    // Match the badCertificateCallback from dioProvider/_refreshDioProvider
    // so silent refresh works on debug localhost with self-signed certs (AC3).
    dio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final client = HttpClient();
        client.badCertificateCallback =
            (cert, host, port) => kDebugMode && host == 'localhost';
        return client;
      },
    );
    final response = await dio.post(
      '/api/v1/auth/refresh',
      data: {'refreshToken': refreshToken},
    );
    final body = response.data as Map<String, dynamic>;
    final newAccess = body['accessToken'] as String?;
    final newRefresh = body['refreshToken'] as String?;
    if (newAccess == null) return null;
    await storage.write(key: 'jwt_token', value: newAccess);
    if (newRefresh != null) {
      await storage.write(key: 'refresh_token', value: newRefresh);
    }
    return newAccess;
  } catch (_) {
    return null;
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
    final storage = ref.read(flutterSecureStorageProvider);
    final token = await storage.read(key: 'jwt_token');

    // Resolve the effective access token — use the stored one if still valid,
    // otherwise attempt a silent refresh before falling back to login.
    String? effectiveToken;
    if (token != null) {
      if (_isValidJwt(token)) {
        effectiveToken = token;
      } else if (_decodeJwtPayload(token) != null) {
        // Expired but structurally valid — try silent refresh (Story 8.6 AC8:
        // refresh token TTL is 180 days, so this covers the common 2-day gap).
        effectiveToken = await _tryProactiveRefresh(storage);
        if (effectiveToken == null) {
          await storage.delete(key: 'jwt_token');
          await storage.delete(key: 'refresh_token');
        }
      } else {
        await storage.delete(key: 'jwt_token');
      }
    }

    if (effectiveToken != null) {
      if (!mounted) return;

      // Ensure EMPLOYEE activeStoreId is set from JWT on cold start.
      // OWNER gets null (all stores).
      String? role;
      try {
        final claims = _decodeJwtPayload(effectiveToken)!;
        role = claims['role'] as String?;
        final jwtStoreId = claims['storeId'] as String?;
        ref.read(activeStoreIdProvider.notifier).setActiveStore(
          role == 'EMPLOYEE' ? jwtStoreId : null,
        );

        // Story 7.1 AC2: persist firstName from JWT for dashboard greeting.
        // Always overwrite (even with null) so a previous user's name is never shown.
        final firstName = claims['firstName'] as String?;
        final prefs0 = await SharedPreferences.getInstance();
        if (firstName != null && firstName.isNotEmpty) {
          await prefs0.setString('user_first_name', firstName);
        } else {
          await prefs0.remove('user_first_name');
        }
      } catch (_) {}

      final prefs = await SharedPreferences.getInstance();
      var wizardSeen = prefs.getBool(kOnboardingWizardSeenKey) ?? false;

      // If the flag is missing locally, fall back to the JWT payload:
      // tenantStatus == "ACTIVE" means onboarding was already completed on
      // a previous install / after a data-clear. Mark it locally and skip
      // the wizard so the user is never stuck in the onboarding loop.
      if (!wizardSeen) {
        wizardSeen = _isTenantActive(effectiveToken);
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

      // Story 7.1: OWNER lands on /dashboard, EMPLOYEE on /pos.
      final landingRoute = (role == 'OWNER') ? '/dashboard' : '/pos';
      context.go(wizardSeen ? landingRoute : '/onboarding/sector');
      return;
    }

    // No valid session — go to login or first-run onboarding.
    final prefs = await SharedPreferences.getInstance();
    final onboardingSeen = prefs.getBool(kOnboardingSeenKey) ?? false;
    if (!mounted) return;
    context.go(onboardingSeen ? '/auth/login' : '/onboarding');
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

/// Application router — all feature routes registered here.
/// Each route points to a placeholder until the feature story is implemented.
///
/// AC5 — EMPLOYEE role-based restriction (layered enforcement):
/// 1. Route guard: top-level redirect below blocks EMPLOYEE from OWNER-only routes
/// 2. Navigation bar filtering in MainShell (EMPLOYEE sees only Caisse + Plus)
/// 3. Settings tiles hiding in SettingsPage (Gestion section hidden for EMPLOYEE)
/// 4. Backend enforcement via JwtAuthFilter (403 for EMPLOYEE on OWNER-only endpoints)
///
/// S12 (Story 12.7 AC5): Global auth guard — any deep link to a non-public route
/// without a valid JWT is redirected to /splash for session validation.
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
    final path = state.uri.path;

    // ── S12 (Story 12.7 AC5): Global auth guard ─────────────────────────
    // Any deep link to a non-public route without a valid JWT → /splash
    // Note: /auth/change-password and /tenant-picker require authentication
    // and must NOT be in this list.
    const _publicPaths = {
      '/splash',
      '/auth/login',
      '/auth/register',
      '/auth/forgot-password',
      '/auth/reset-password',
    };
    const _publicPathPrefixes = ['/onboarding'];
    final isPublicPath = _publicPaths.contains(path) ||
        _publicPathPrefixes.any((p) => path.startsWith(p));
    if (!isPublicPath) {
      final storage = ProviderScope.containerOf(context, listen: false)
          .read(flutterSecureStorageProvider);
      final token = await storage.read(key: 'jwt_token');
      if (token == null || !_isValidJwt(token)) return '/splash';
    }

    final isOwnerOnlyPath =
        _ownerOnlyPrefixes.any((prefix) => path.startsWith(prefix));
    if (!isOwnerOnlyPath) {
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
        final storage = ProviderScope.containerOf(context, listen: false)
            .read(flutterSecureStorageProvider);
        final token = await storage.read(key: 'jwt_token');
        if (token != null && _isValidJwt(token)) {
          // Story 7.1: OWNER → /dashboard, EMPLOYEE → /pos
          final prefs = await SharedPreferences.getInstance();
          final role = prefs.getString(kUserRoleKey);
          return (role == 'OWNER') ? '/dashboard' : '/pos';
        }
        return null; // proceed to AuthPage in login mode
      },
      builder: (_, __) => const AuthPage(initialMode: AuthMode.login),
    ),
    GoRoute(
      path: '/auth/register',
      builder: (_, __) => const AuthPage(initialMode: AuthMode.register),
    ),

    // ── Forgot/Reset password (Story 14.12 — public, no auth) ──────────
    GoRoute(
      path: '/auth/forgot-password',
      builder: (_, __) => const ForgotPasswordPage(),
    ),
    GoRoute(
      path: '/auth/reset-password',
      builder: (_, __) => const ResetPasswordPage(),
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
