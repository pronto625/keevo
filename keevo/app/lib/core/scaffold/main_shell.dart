import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/catalog/presentation/provider/product_provider.dart';

/// MainShell — persistent bottom navigation scaffold wrapping the 4 main sections.
///
/// Routes inside the ShellRoute (/pos, /products, /clients, /suppliers) are
/// displayed as the [child] body while the [NavigationBar] stays visible.
/// Sub-routes (form pages) push on top of the shell and don't show the nav bar.
class MainShell extends ConsumerWidget {
  final Widget child;
  const MainShell({required this.child, super.key});

  static int _tabIndex(String location) {
    if (location.startsWith('/products')) return 1;
    if (location.startsWith('/clients')) return 2;
    if (location.startsWith('/suppliers')) return 3;
    return 0; // /pos and anything else
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final location = GoRouterState.of(context).uri.toString();
    final index = _tabIndex(location);
    final draftCountAsync = ref.watch(pendingDraftsCountProvider);
    final draftCount = draftCountAsync.valueOrNull ?? 0;

    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) {
          switch (i) {
            case 0:
              context.go('/pos');
            case 1:
              context.go('/products');
            case 2:
              context.go('/clients');
            case 3:
              context.go('/suppliers');
          }
        },
        destinations: [
          const NavigationDestination(
            icon: Icon(Icons.point_of_sale_outlined),
            selectedIcon: Icon(Icons.point_of_sale_rounded),
            label: 'Caisse',
          ),
          NavigationDestination(
            icon: Badge(
              label: Text(draftCount > 9 ? '9+' : '$draftCount'),
              isLabelVisible: draftCount > 0,
              backgroundColor: const Color(0xFFFCC419), // colorWarning
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
            icon: Icon(Icons.people_outline_rounded),
            selectedIcon: Icon(Icons.people_rounded),
            label: 'Clients',
          ),
          const NavigationDestination(
            icon: Icon(Icons.local_shipping_outlined),
            selectedIcon: Icon(Icons.local_shipping_rounded),
            label: 'Fournisseurs',
          ),
        ],
      ),
    );
  }
}
