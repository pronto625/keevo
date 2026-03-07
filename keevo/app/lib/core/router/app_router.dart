import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/presentation/page/login_page.dart';
import '../../features/auth/presentation/page/register_page.dart';
import '../../features/auth/presentation/page/tenant_picker_page.dart';
import '../../features/auth/domain/model/membership_dto.dart';
import '../../features/onboarding/domain/model/sector_type.dart';
import '../../features/onboarding/presentation/page/onboarding_page.dart';
import '../../features/onboarding/presentation/page/sector_selection_page.dart';
import '../../features/onboarding/presentation/page/shop_name_page.dart';
import '../../features/onboarding/presentation/page/terms_page.dart';
import '../../features/pos/presentation/page/pos_placeholder_page.dart';
import '../../features/settings/presentation/page/subscription_page.dart';
import '../di/providers.dart';
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
      final wizardSeen = prefs.getBool(kOnboardingWizardSeenKey) ?? false;
      if (!mounted) return;
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

/// Application router — all feature routes registered here.
/// Each route points to a placeholder until the feature story is implemented.
final GoRouter appRouter = GoRouter(
  initialLocation: '/splash',
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

    // ── POS ─────────────────────────────────────────────────
    // AC5: fade transition — no lateral slide when landing from onboarding
    GoRoute(
      path: '/pos',
      pageBuilder: (_, state) => CustomTransitionPage(
        key: state.pageKey,
        child: const PosPlaceholderPage(),
        transitionsBuilder: (_, animation, __, child) =>
            FadeTransition(opacity: animation, child: child),
      ),
    ),

    // ── Products ────────────────────────────────────────────
    GoRoute(
      path: '/products',
      builder: (_, __) => const _PlaceholderPage(title: 'Products'),
    ),

    // ── Inventory ───────────────────────────────────────────
    GoRoute(
      path: '/inventory',
      builder: (_, __) => const _PlaceholderPage(title: 'Inventory'),
    ),

    // ── Stores ──────────────────────────────────────────────
    GoRoute(
      path: '/stores',
      builder: (_, __) => const _PlaceholderPage(title: 'Stores'),
    ),

    // ── Reports ─────────────────────────────────────────────
    GoRoute(
      path: '/reports',
      builder: (_, __) => const _PlaceholderPage(title: 'Reports'),
    ),

    // ── Settings ────────────────────────────────────────────
    GoRoute(
      path: '/settings',
      builder: (_, __) => const _PlaceholderPage(title: 'Settings'),
    ),
    // ── Settings > Subscription ──────────────────────────────
    GoRoute(
      path: '/settings/subscription',
      builder: (_, __) => const SubscriptionPage(),
    ),  ],
);
