import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../di/providers.dart';
import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/pos/presentation/provider/pos_providers.dart';
import '../../features/stores/presentation/provider/active_store_provider.dart';

/// MainShell — persistent bottom navigation scaffold wrapping the main sections.
///
/// UX spec: OWNER sees 4 onglets: Caisse / Catalogue / Rapports / Plus
/// EMPLOYEE sees 2 onglets: Caisse / Plus (AC5 — restricted navigation)
///
/// Clients & Fournisseurs are accessible from the Plus (Settings) page.
class MainShell extends ConsumerWidget {
  final Widget child;
  const MainShell({required this.child, super.key});

  /// OWNER tab routes — full navigation
  static const _ownerRoutes = ['/pos', '/products', '/reports', '/settings'];

  /// EMPLOYEE tab routes — restricted to POS + settings only (AC5)
  static const _employeeRoutes = ['/pos', '/settings'];

  static int _tabIndex(String location, List<String> routes) {
    for (int i = routes.length - 1; i >= 0; i--) {
      if (location.startsWith(routes[i])) return i;
    }
    return 0;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final location = GoRouterState.of(context).uri.toString();
    final role = ref.watch(currentUserRoleProvider);
    final isEmployee = role == 'EMPLOYEE';
    final routes = isEmployee ? _employeeRoutes : _ownerRoutes;
    final index = _tabIndex(location, routes);
    final draftCountAsync = ref.watch(pendingDraftsCountProvider);
    final draftCount = draftCountAsync.valueOrNull ?? 0;

    final storeId = ref.watch(activeStoreIdProvider);
    final pendingSalesCount = isEmployee
        ? 0
        : (ref.watch(pendingSalesCountProvider(storeId)).valueOrNull ?? 0);

    final destinations = <NavigationDestination>[
      NavigationDestination(
        icon: Badge(
          label: Text(pendingSalesCount > 9 ? '9+' : '$pendingSalesCount'),
          isLabelVisible: pendingSalesCount > 0,
          backgroundColor: Colors.amber,
          textColor: Colors.black87,
          child: const Icon(Icons.point_of_sale_outlined),
        ),
        selectedIcon: Badge(
          label: Text(pendingSalesCount > 9 ? '9+' : '$pendingSalesCount'),
          isLabelVisible: pendingSalesCount > 0,
          backgroundColor: Colors.amber,
          textColor: Colors.black87,
          child: const Icon(Icons.point_of_sale_rounded),
        ),
        label: 'Caisse',
      ),
      if (!isEmployee) ...[
        NavigationDestination(
          icon: Badge(
            label: Text(draftCount > 9 ? '9+' : '$draftCount'),
            isLabelVisible: draftCount > 0,
            backgroundColor: const Color(0xFFFCC419),
            textColor: Colors.black87,
            child: const Icon(Icons.inventory_2_outlined),
          ),
          selectedIcon: Badge(
            label: Text(draftCount > 9 ? '9+' : '$draftCount'),
            isLabelVisible: draftCount > 0,
            backgroundColor: const Color(0xFFFCC419),
            textColor: Colors.black87,
            child: const Icon(Icons.inventory_2_rounded),
          ),
          label: 'Catalogue',
        ),
        const NavigationDestination(
          icon: Icon(Icons.bar_chart_outlined),
          selectedIcon: Icon(Icons.bar_chart_rounded),
          label: 'Rapports',
        ),
      ],
      const NavigationDestination(
        icon: Icon(Icons.more_horiz_outlined),
        selectedIcon: Icon(Icons.more_horiz_rounded),
        label: 'Plus',
      ),
    ];

    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => context.go(routes[i]),
        destinations: destinations,
      ),
    );
  }
}
