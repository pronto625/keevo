import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/notifications/presentation/provider/notification_provider.dart';
import '../../features/pos/presentation/provider/pos_providers.dart';
import '../../features/stores/presentation/provider/active_store_provider.dart';
import '../../features/stores/presentation/provider/store_provider.dart';
import '../../features/sync_indicator/presentation/widget/sync_warning_banner.dart';
import '../di/providers.dart';
import '../router/app_router.dart';
import '../sync/auto_closure_notification_checker.dart';
import '../sync/sync_trigger_notifier.dart';
import 'offline_gate_banner.dart';

/// MainShell — persistent bottom navigation scaffold wrapping the main sections.
///
/// UX spec: OWNER sees 4 onglets: Caisse / Catalogue / Rapports / Plus
/// EMPLOYEE sees 2 onglets: Caisse / Plus (AC5 — restricted navigation)
///
/// Clients & Fournisseurs are accessible from the Plus (Settings) page.
class MainShell extends ConsumerStatefulWidget {
  final Widget child;
  const MainShell({required this.child, super.key});

  @override
  ConsumerState<MainShell> createState() => _MainShellState();
}

class _MainShellState extends ConsumerState<MainShell> {
  /// OWNER tab routes — full navigation (5 tabs: Dashboard / Caisse / Catalogue / Rapports / Plus)
  static const _ownerRoutes = ['/dashboard', '/pos', '/products', '/reports', '/settings'];

  /// EMPLOYEE tab routes — POS + Rapports (day closure) + settings (AC5)
  static const _employeeRoutes = ['/pos', '/reports', '/settings'];

  static int _tabIndex(String location, List<String> routes) {
    for (int i = routes.length - 1; i >= 0; i--) {
      if (location.startsWith(routes[i])) return i;
    }
    return 0;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkAutoClosureNotifications();
      _initFcm();
    });
  }

  /// Initialise Firebase Messaging et enregistre le token FCM sur le backend.
  /// Best-effort: aucune erreur ne remonte à l'UI.
  Future<void> _initFcm() async {
    try {
      final fcmService = ref.read(fcmServiceProvider);
      debugPrint('[FCM] Initializing Firebase Messaging...');
      await fcmService.initialize(appRouter);
      debugPrint('[FCM] Getting FCM token...');
      final token = await fcmService.getToken();
      if (token == null) {
        debugPrint('[FCM] getToken() returned null — skipping registration (no GMS or permission denied)');
        return;
      }
      debugPrint('[FCM] Token obtained: ${token.substring(0, 20)}...');
      await ref.read(remoteDeviceTokenDataSourceProvider).registerToken(
        token: token,
        platform: _fcmPlatform(),
      );
      debugPrint('[FCM] Token registered with backend ✅');
    } catch (e, st) {
      debugPrint('[FCM] Token registration failed: $e\n$st');
    }
  }

  String _fcmPlatform() {
    if (Platform.isAndroid) return 'ANDROID';
    if (Platform.isIOS) return 'IOS';
    if (Platform.isWindows) return 'WINDOWS';
    return 'LINUX';
  }

  Future<void> _checkAutoClosureNotifications() async {
    if (!mounted) return;
    final db = ref.read(appDatabaseProvider);
    final prefs = ref.read(sharedPreferencesProvider);
    final checker = AutoClosureNotificationChecker(db: db, prefs: prefs);
    final unnotified = await checker.getUnnotified();
    if (!mounted || unnotified.isEmpty) return;

    final dateFormat = DateFormat('d MMMM yyyy', 'fr_FR');
    final messenger = ScaffoldMessenger.of(context);
    for (final closure in unnotified) {
      messenger.showSnackBar(SnackBar(
        content: Text(
          'Clôture automatique\u202f: ${closure.storeName} — ${dateFormat.format(closure.closedAt)}',
        ),
        duration: const Duration(seconds: 5),
        behavior: SnackBarBehavior.floating,
      ));
      // Slight delay between multiple SnackBars to avoid overlap.
      await Future.delayed(const Duration(milliseconds: 300));
    }
    await checker.markNotified(unnotified.map((c) => c.id).toList());
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).uri.toString();
    final role = ref.watch(currentUserRoleProvider);
    final isEmployee = role == 'EMPLOYEE';
    final routes = isEmployee ? _employeeRoutes : _ownerRoutes;
    final index = _tabIndex(location, routes);
    final draftCountAsync = ref.watch(pendingDraftsCountProvider);
    final draftCount = draftCountAsync.valueOrNull ?? 0;

    // Eagerly initialize SyncTriggerNotifier so connectivity listeners
    // and periodic sync timer are active from the moment the shell loads.
    ref.watch(syncTriggerNotifierProvider);

    final storeId = ref.watch(activeStoreIdProvider);

    // OWNER auto-select: when no store is chosen, pick the first available.
    if (storeId == null && !isEmployee) {
      final storesAsync = ref.watch(storeListNotifierProvider);
      final stores = storesAsync.valueOrNull;
      if (stores != null && stores.isNotEmpty) {
        // Schedule after build to avoid modifying state during build.
        Future.microtask(() {
          ref
              .read(activeStoreIdProvider.notifier)
              .setActiveStore(stores.first.id);
        });
      }
    }

    final pendingSalesCount = isEmployee
        ? 0
        : (ref.watch(pendingSalesCountProvider(storeId)).valueOrNull ?? 0);

    final destinations = <NavigationDestination>[
      if (!isEmployee)
        const NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          selectedIcon: Icon(Icons.dashboard_rounded),
          label: 'Dashboard',
        ),
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
      if (!isEmployee)
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
      const NavigationDestination(
        icon: Icon(Icons.more_horiz_outlined),
        selectedIcon: Icon(Icons.more_horiz_rounded),
        label: 'Plus',
      ),
    ];

    return Scaffold(
      body: Column(
        children: [
          const SyncWarningBanner(),
          const OfflineGateBanner(),
          Expanded(child: widget.child),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => context.go(routes[i]),
        destinations: destinations,
      ),
    );
  }
}

