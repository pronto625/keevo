import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../features/catalog/presentation/provider/product_provider.dart';
import '../../features/inventory/presentation/provider/global_stock_provider.dart';
import '../../features/notifications/presentation/provider/notification_provider.dart';
import '../../features/pos/data/datasource/local_day_closure_datasource.dart';
import '../../features/pos/presentation/provider/day_closure_providers.dart';
import '../../features/reports/presentation/provider/report_history_providers.dart';
import '../../features/stores/presentation/provider/active_store_provider.dart';
import '../../features/stores/presentation/provider/store_provider.dart';
import '../di/providers.dart';
import '../router/app_router.dart';
import '../theme/app_theme.dart';
import '../sync/auto_closure_notification_checker.dart';
import '../sync/sync_trigger_notifier.dart';
import 'shell_banners.dart';
import 'shell_tab_back_scope.dart';

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
  /// Story 9-5 — fetches the Keevo announcements again when the app resumes.
  AppLifecycleListener? _lifecycle;

  /// OWNER tab routes — full navigation (5 tabs: Dashboard / Caisse / Catalogue / Rapports / Plus)
  static const _ownerRoutes = [
    '/dashboard',
    '/pos',
    '/products',
    '/reports',
    '/settings'
  ];

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
      _syncAnnouncements();
    });
    _lifecycle = AppLifecycleListener(onResume: _syncAnnouncements);
  }

  @override
  void dispose() {
    _lifecycle?.dispose();
    super.dispose();
  }

  /// Story 9-5 — stores the Keevo announcements not received / tapped as
  /// pushes. Best-effort: never throws, skipped offline.
  void _syncAnnouncements() {
    if (!mounted) return;
    unawaited(ref.read(announcementSyncServiceProvider).sync());
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
          // Story 14.10: employee action notifications — invalidate relevant providers
          if (type.startsWith('EMPLOYEE_PRODUCT_CREATED') ||
              type.startsWith('EMPLOYEE_STOCK_MODIFIED')) {
            // Invalidate product list to show new/changed products immediately
            ref.invalidate(productListProvider);
          }
          if (type.startsWith('EMPLOYEE_STOCK_MODIFIED')) {
            // Invalidate the multi-store stock overview so quantities refresh too
            ref.invalidate(globalStockOverviewProvider);
          }
        },
      );
      // v1s-16-2: single registration path (records the accepted token
      // so sign-out can revoke it).
      await fcmService.registerCurrentToken();
    } catch (e, st) {
      debugPrint('[FCM] Initialization failed: $e\n$st');
    }
  }

  // MainShell sits under a plain ShellRoute and can be disposed/recreated
  // mid-session (e.g. the global auth redirect bouncing through /splash).
  // This guard is process-lifetime (static, not per-instance) so a second
  // concurrent run never races the first one's read of "already notified"
  // IDs before it has had a chance to persist them.
  static bool _autoClosureCheckInFlight = false;

  Future<void> _checkAutoClosureNotifications() async {
    if (_autoClosureCheckInFlight) return;
    _autoClosureCheckInFlight = true;
    try {
      if (!mounted) return;
      final db = ref.read(appDatabaseProvider);
      final prefs = ref.read(sharedPreferencesProvider);
      final checker = AutoClosureNotificationChecker(db: db, prefs: prefs);
      // J21: an employee only hears about their own store's closure (owner:
      // all stores). No assigned store → nothing to show.
      final isEmployee = ref.read(currentUserRoleProvider) == 'EMPLOYEE';
      final ownStoreId = ref.read(activeStoreIdProvider);
      if (isEmployee && ownStoreId == null) return;
      final unnotified = await checker.getUnnotified(
          onlyStoreId: isEmployee ? ownStoreId : null);
      if (unnotified.isEmpty) return;

      final dateFormat = DateFormat('d MMMM yyyy', 'fr_FR');
      for (final closure in unnotified) {
        // Persist "seen" BEFORE showing: if the widget is disposed or an
        // error interrupts this loop, the closure must never come back on
        // the next check instead of being shown once and then looping forever.
        await checker.markNotified([closure.id]);
        if (!mounted) continue;
        await _showTopClosureNotice(
          'Clôture automatique\u202f: ${closure.storeName} — ${dateFormat.format(closure.closedAt)}',
        );
      }
    } finally {
      _autoClosureCheckInFlight = false;
    }
  }

  /// Shows [message] as a small floating card pinned near the top of the
  /// screen, using the Overlay directly (not MaterialBanner/SnackBar) so it
  /// never resizes the Scaffold body underneath it — a MaterialBanner is part
  /// of the layout and visibly shifts the page down/up as it appears/hides.
  Future<void> _showTopClosureNotice(String message) async {
    final overlayState = Overlay.of(context);
    late OverlayEntry entry;
    entry = OverlayEntry(
      builder: (overlayContext) {
        final theme = Theme.of(overlayContext);
        return Positioned(
          top: MediaQuery.of(overlayContext).padding.top + 8,
          left: 12,
          right: 12,
          // Ombre douce partagée plutôt que l'élévation Material par défaut.
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
              boxShadow: AppTheme.softShadow(theme.colorScheme),
            ),
            child: Material(
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
              color: theme.colorScheme.inverseSurface,
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    Icon(Icons.event_available_outlined,
                        color: theme.colorScheme.onInverseSurface),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        message,
                        style: TextStyle(
                            color: theme.colorScheme.onInverseSurface),
                      ),
                    ),
                    IconButton(
                      icon: Icon(Icons.close_rounded,
                          size: 18, color: theme.colorScheme.onInverseSurface),
                      onPressed: () {
                        if (entry.mounted) entry.remove();
                      },
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    overlayState.insert(entry);
    await Future.delayed(const Duration(seconds: 5));
    if (entry.mounted) entry.remove();
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

        debugPrint(
            '[AutoClose] Missed closure for store ${store.id} — triggering locally');
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

    final cs = Theme.of(context).colorScheme;

    // J2 — retour système sur un onglet autre que le premier : on revient au
    // premier onglet au lieu de quitter l'application.
    return ShellTabBackScope(
      tabIndex: index,
      onGoToFirstTab: () => context.go(routes.first),
      child: Scaffold(
        // v1s-16-13: one banner at a time, by priority (suspension, deletion
        // pending — v1s-14-5 AC8 —, offline gate, sync failure, trial expired).
        body: ShellBannerLayout(child: widget.child),
        // Soft/modern rebrand (2026-09-16) : coins hauts arrondis + ombre
        // douce (au lieu de l'élévation Material par défaut, plus dure) pour
        // que la barre lise comme une "sheet" posée sur le contenu plutôt
        // qu'un bandeau plat collé au bord de l'écran.
        bottomNavigationBar: Container(
          decoration: BoxDecoration(
            color: cs.surface,
            borderRadius: const BorderRadius.vertical(
              top: Radius.circular(AppTheme.radiusXL),
            ),
            boxShadow: [
              BoxShadow(
                color: cs.shadow.withAlpha(20),
                blurRadius: 20,
                offset: const Offset(0, -4),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: const BorderRadius.vertical(
              top: Radius.circular(AppTheme.radiusXL),
            ),
            child: NavigationBar(
              selectedIndex: index,
              onDestinationSelected: (i) => context.go(routes[i]),
              destinations: destinations,
            ),
          ),
        ),
      ),
    );
  }
}
