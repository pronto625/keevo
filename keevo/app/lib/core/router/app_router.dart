import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

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

/// Application router — all feature routes registered here.
/// Each route points to a placeholder until the feature story is implemented.
final GoRouter appRouter = GoRouter(
  initialLocation: '/auth/login',
  routes: [
    // ── Auth ────────────────────────────────────────────────
    GoRoute(
      path: '/auth/login',
      builder: (_, __) => const _PlaceholderPage(title: 'Login'),
    ),

    // ── Onboarding ──────────────────────────────────────────
    GoRoute(
      path: '/onboarding',
      builder: (_, __) => const _PlaceholderPage(title: 'Onboarding'),
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
