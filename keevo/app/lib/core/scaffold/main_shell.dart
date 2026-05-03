import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/inventory/presentation/provider/global_stock_provider.dart';
import '../../features/notifications/presentation/provider/notification_provider.dart';
import '../../features/pos/data/datasource/local_day_closure_datasource.dart';
import '../../features/pos/presentation/provider/day_closure_providers.dart';
import '../../features/pos/presentation/provider/pos_providers.dart';
import '../../features/reports/presentation/provider/report_history_providers.dart';
import '../../features/stores/presentation/provider/active_store_provider.dart';
import '../../features/stores/presentation/provider/store_provider.dart';
import '../../features/sync_indicator/presentation/widget/sync_warning_banner.dart';
import '../di/providers.dart';
import '../router/app_router.dart';
import '../theme/app_theme.dart';
import '../sync/auto_closure_notification_checker.dart';
import '../sync/sync_trigger_notifier.dart';
import 'offline_gate_banner.dart';

/// MainShell — persistent bottom navigation scaffold wrapping the main sections.
///
/// UX spec: OWNER sees 5 onglets:   Dashboard / Caisse / Catalogue / Rapports / Plus
/// EMPLOYEE sees 4 onglets: Caisse / Catalogue (read-only) / Rapports / Plus (HF-2 AC5)
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

  /// EMPLOYEE tab routes — POS + Catalogue (read-only) + Rapports (day closure) + settings (HF-2 AC5)
  static const _employeeRoutes = ['/pos', '/products', '/reports', '/settings'];

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
      _checkMissedDayClosure();
      _initFcm();
    });
  }

  /// Initialise Firebase Messaging et enregistre le token FCM sur le backend.
  /// Best-effort: aucune erreur ne remonte à l'UI.
  Future<void> _initFcm() async {
    try {
      final fcmService = ref.read(fcmServiceProvider);
      debugPrint('[FCM] Initializing Firebase Messaging...');
      await fcmService.initialize(
        appRouter,
        onMessage: (message) {
          final type = message.data['type'] as String? ?? '';
          if (type.contains('REPORT')) {
            // Invalidate the entire reportHistory family so the list refreshes
            // immediately instead of waiting for the next periodic sync cycle.
            ref.invalidate(reportHistoryProvider);
          }
        },
      );
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

  /// Checks on app startup whether a day closure was missed (e.g., backend
  /// scheduler didn't fire, app was closed before EOD).
  ///
  /// Triggers a local automatic closure for each active store that:
  ///   - has no closure recorded for today, AND
  ///   - has unclosed sales from a previous day (last closure before today).
  ///
  /// Only fires after 20:00 local time (default EOD) to avoid premature closure.
  Future<void> _checkMissedDayClosure() async {
    if (!mounted) return;

    // Only trigger after configured EOD (20:00 local).
    final now = DateTime.now();
    if (now.hour < 20) return;

    final role = ref.read(currentUserRoleProvider);
    if (role == 'EMPLOYEE') return; // Employees don't trigger auto-closure.

    final userId = ref.read(currentUserIdProvider).valueOrNull;
    if (userId == null) return;

    final db = ref.read(appDatabaseProvider);
    final localDS = LocalDayClosureDataSource(db);

    final storesAsync = ref.read(storeListNotifierProvider);
    final stores = storesAsync.valueOrNull ?? [];

    final startOfToday = DateTime(now.year, now.month, now.day);

    for (final store in stores) {
      try {
        final lastClosure = await localDS.getLastClosure(store.id);
        // Skip if already closed today.
        if (lastClosure != null && lastClosure.closedAt.isAfter(startOfToday)) {
          continue;
        }
        // Only close if there are sales since the last closure.
        final salesCount = await localDS.getTodaySalesCount(store.id);
        if (salesCount == 0) continue;

        debugPrint('[AutoClose] Missed closure for store ${store.id} — triggering locally');
        await ref.read(closeDayUseCaseProvider).execute(
          storeId: store.id,
          actorId: userId,
          isAutomatic: true,
        );
        debugPrint('[AutoClose] Closure done for store ${store.id}');
      } catch (e) {
        // DAY_ALREADY_CLOSED or other transient errors — not fatal.
        debugPrint('[AutoClose] Skipped store ${store.id}: $e');
      }
    }
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

    // Low stock badge — sum lowStockCount across all stores (AC8)
    final lowStockCount = isEmployee
        ? 0
        : (ref
                .watch(globalStockOverviewProvider)
                .valueOrNull
                ?.fold<int>(0, (sum, s) => sum + s.lowStockCount) ??
            0);

    final destinations = <NavigationDestination>[
      if (!isEmployee)
        const NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          selectedIcon: Icon(Icons.dashboard_rounded),
          label: 'Dashboard',
        ),
      NavigationDestination(
        icon: const Icon(Icons.point_of_sale_outlined),
        selectedIcon: const Icon(Icons.point_of_sale_rounded),
        label: 'Caisse',
      ),
      // Catalogue — read-only for EMPLOYEE (mutation actions hidden in CatalogPage itself)
      NavigationDestination(
        icon: Badge(
          label: Text(lowStockCount > 9 ? '9+' : '$lowStockCount'),
          isLabelVisible: lowStockCount > 0 && !isEmployee,
          backgroundColor: AppTheme.warning,
          textColor: AppTheme.darkSurface,
          child: Badge(
            label: Text(draftCount > 9 ? '9+' : '$draftCount'),
            isLabelVisible: draftCount > 0 && lowStockCount == 0 && !isEmployee,
            backgroundColor: AppTheme.warning,
            textColor: AppTheme.darkSurface,
            child: const Icon(Icons.inventory_2_outlined),
          ),
        ),
        selectedIcon: Badge(
          label: Text(lowStockCount > 9 ? '9+' : '$lowStockCount'),
          isLabelVisible: lowStockCount > 0 && !isEmployee,
          backgroundColor: AppTheme.warning,
          textColor: AppTheme.darkSurface,
          child: Badge(
            label: Text(draftCount > 9 ? '9+' : '$draftCount'),
            isLabelVisible: draftCount > 0 && lowStockCount == 0 && !isEmployee,
            backgroundColor: AppTheme.warning,
            textColor: AppTheme.darkSurface,
            child: const Icon(Icons.inventory_2_rounded),
          ),
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

