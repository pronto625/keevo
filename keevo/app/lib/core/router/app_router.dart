import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/presentation/page/register_page.dart';
import '../../features/onboarding/presentation/page/onboarding_page.dart';
import '../../features/onboarding/presentation/page/terms_page.dart';

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
class _SplashRedirectPage extends StatefulWidget {
  const _SplashRedirectPage();

  @override
  State<_SplashRedirectPage> createState() => _SplashRedirectPageState();
}

class _SplashRedirectPageState extends State<_SplashRedirectPage> {
  @override
  void initState() {
    super.initState();
    _redirect();
  }

  Future<void> _redirect() async {
    final prefs = await SharedPreferences.getInstance();
    final onboardingSeen = prefs.getBool(kOnboardingSeenKey) ?? false;
    final termsAccepted = prefs.getBool(kTermsAcceptedKey) ?? false;
    if (!mounted) return;
    if (!onboardingSeen) {
      context.go('/onboarding');
    } else if (!termsAccepted) {
      context.go('/terms');
    } else {
      context.go('/auth/register');
    }
  }

  @override
  Widget build(BuildContext context) {
    // Fond uni pendant le chargement des prefs (< 50 ms en pratique)
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
      builder: (_, __) => const _PlaceholderPage(title: 'Login'),
    ),
    GoRoute(
      path: '/auth/register',
      builder: (_, __) => const RegisterPage(),
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

    // ── POS ─────────────────────────────────────────────────
    GoRoute(
      path: '/pos',
      builder: (_, __) => const _PlaceholderPage(title: 'POS'),
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
  ],
);
