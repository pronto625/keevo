import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';

import '../../../../core/entitlements/entitlements_provider.dart';
import '../../../dashboard/domain/model/dashboard_snapshot.dart';
import '../../../dashboard/presentation/provider/dashboard_providers.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';

/// Route of the free local « stock bas » list (`?all=true`: every store).
const kLocalLowStockRoute = '/stock/low';

/// True when the multi-store stock overview is available to this session: the
/// server confirmed (in this session) that the tenant's `multi-store` module
/// is readable. Owner and employee alike (15.1: the overview serves both).
/// Any failure, an offline device or a slow answer (> [timeout]) → false: the
/// caller opens the free local list. A slow answer is kept for the next call;
/// a failure is not, the next call asks the server again.
Future<bool> isMultiStoreOverviewAvailable(
  ProviderContainer container, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  try {
    final entitlements = await container
        .read(entitlementsProvider.notifier)
        .ensureServerState()
        .timeout(timeout);
    return entitlements.isReadable(kMultiStoreModuleId);
  } catch (_) {
    // Timeout (the answer is kept for the next call) or failure.
    return false;
  }
}

/// A tap is already being routed (no double push on a quick double tap).
bool _lowStockViewOpening = false;

/// 13.10b — opens the low-stock view the session is entitled to: the
/// multi-store overview (premium `multi-store` readable) or the free local
/// list ([LocalLowStockPage]) — Free tenant, offline.
///
/// [overviewRoute] is the premium destination (the dashboard tile filters on
/// low stock, the catalogue button opens the whole overview). [allStores]:
/// the local list covers every store (the dashboard tile counts them all)
/// instead of the active one.
///
/// Reads the providers through the scope's container, not a widget ref: the
/// tapped widget may be gone when the answer arrives.
Future<void> openLowStockView(
  BuildContext context, {
  String overviewRoute = '/stock/overview?showLowOnly=true',
  bool allStores = false,
}) async {
  if (_lowStockViewOpening) return;
  _lowStockViewOpening = true;
  try {
    final router = GoRouter.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    final premium = await isMultiStoreOverviewAvailable(container);
    router.push(premium
        ? overviewRoute
        : allStores
            ? '$kLocalLowStockRoute?all=true'
            : kLocalLowStockRoute);
  } finally {
    _lowStockViewOpening = false;
  }
}

/// LocalLowStockPage — products at or below their threshold, read from the
/// device (Drift): of the active store, or of every active store when
/// [allStores] (or no store is chosen). Free, works offline.
/// Route: [kLocalLowStockRoute].
class LocalLowStockPage extends ConsumerWidget {
  final bool allStores;

  const LocalLowStockPage({super.key, this.allStores = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final String? storeId = allStores ? null : ref.watch(activeStoreIdProvider);
    final async = ref.watch(localLowStockProductsProvider(storeId));
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.surfaceContainerLowest,
      appBar: AppBar(
        title: const Text('Stock bas'),
        backgroundColor: cs.surface,
        scrolledUnderElevation: 0,
      ),
      body: RefreshIndicator(
        onRefresh: () async =>
            ref.invalidate(localLowStockProductsProvider(storeId)),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => AppErrorWidget(
            error: e,
            onRetry: () =>
                ref.invalidate(localLowStockProductsProvider(storeId)),
          ),
          data: (items) => items.isEmpty
              ? const _Empty()
              : ListView.separated(
                  key: const Key('local-low-stock-list'),
                  padding: const EdgeInsets.all(16),
                  itemCount: items.length + 1,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, i) => i == 0
                      ? _Header(
                          // Products, as the dashboard tile counts them (a
                          // product low in 2 stores is one product).
                          count: items.map((e) => e.productId).toSet().length,
                          allStores: storeId == null)
                      : _LowStockTile(item: items[i - 1]),
                ),
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final int count;
  final bool allStores;
  const _Header({required this.count, required this.allStores});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final scope = allStores ? 'toutes les boutiques' : 'boutique active';
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Text(
        '$count produit${count > 1 ? 's' : ''} à réapprovisionner · $scope',
        style: Theme.of(context)
            .textTheme
            .bodyMedium
            ?.copyWith(color: cs.onSurfaceVariant),
      ),
    );
  }
}

class _LowStockTile extends StatelessWidget {
  final LowStockProduct item;
  const _LowStockTile({required this.item});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final out = item.quantity <= 0;
    final color = out ? AppTheme.errorColor : AppTheme.warning;
    return Container(
      decoration: AppTheme.softCardDecoration(cs),
      child: ListTile(
        leading: SoftIconBadge(
          icon: out ? Icons.remove_shopping_cart_outlined : Icons.inventory_2,
          color: color,
          size: 40,
        ),
        title: Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: Text([
          'Seuil : ${item.threshold}',
          if (item.storeName != null) item.storeName!,
        ].join(' · ')),
        trailing: Text(
          out ? 'Rupture' : '${item.quantity}',
          style: TextStyle(fontWeight: FontWeight.w700, color: color),
        ),
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Scrollable so the pull-to-refresh still works on an empty list.
    return ListView(
      children: [
        const SizedBox(height: 96),
        Center(
          child: SoftIconBadge(
            icon: Icons.check_circle_outline_rounded,
            color: AppTheme.success,
            size: 72,
          ),
        ),
        const SizedBox(height: 16),
        Text(
          'Aucun produit en stock bas',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(
          'Tous vos produits sont au-dessus de leur seuil.',
          textAlign: TextAlign.center,
          style: Theme.of(context)
              .textTheme
              .bodySmall
              ?.copyWith(color: cs.onSurfaceVariant),
        ),
      ],
    );
  }
}
