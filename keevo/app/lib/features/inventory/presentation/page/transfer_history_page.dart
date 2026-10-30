import '../../../../core/theme/app_theme.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../../core/widget/soft_icon_button.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../domain/model/stock_transfer_model.dart';
import '../provider/global_stock_provider.dart';
import '../provider/stock_transfer_provider.dart';
import '../widget/transfer_form_bottom_sheet.dart';
import '../../../../core/widget/entitlement_upgrade.dart';

/// TransferHistoryPage — paginated list of inter-store stock transfers.
///
/// AC1: FAB "Nouveau transfert" opens the form bottom sheet.
/// AC5: Filter chips by status; list in reverse chronological order.
/// Story 3.3.
class TransferHistoryPage extends ConsumerStatefulWidget {
  const TransferHistoryPage({super.key});

  @override
  ConsumerState<TransferHistoryPage> createState() =>
      _TransferHistoryPageState();
}

class _TransferHistoryPageState extends ConsumerState<TransferHistoryPage> {
  String? _filterStatus;
  final _scroll = ScrollController();
  bool _loadMoreFailed = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Provider of the list shown: active store (source OR destination) and
  /// status chip are part of the query, never applied after a page limit
  /// (v1s-16-23).
  TransferHistoryProvider get _provider => transferHistoryProvider(
        storeId: ref.read(activeStoreIdProvider),
        status: _filterStatus,
      );

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (!_loadMoreFailed && pos.pixels >= pos.maxScrollExtent - 300) {
      _loadMore();
    }
  }

  /// A first page shorter than the screen gives nothing to scroll: load on.
  void _fillViewport() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients || _loadMoreFailed) return;
      if (_scroll.position.maxScrollExtent <= 0) _loadMore();
    });
  }

  Future<void> _loadMore() async {
    final provider = _provider;
    final notifier = ref.read(provider.notifier);
    if (notifier.isLoadingMore) return;
    final current = ref.read(provider).valueOrNull;
    if (current == null || !current.hasMore) return;
    setState(() => _loadMoreFailed = false);
    try {
      await notifier.loadMore();
    } catch (_) {
      if (mounted) _loadMoreFailed = true;
    } finally {
      if (mounted) setState(() {});
    }
  }

  void _resetLoadMoreFailed() {
    if (_loadMoreFailed && mounted) setState(() => _loadMoreFailed = false);
  }

  void _selectStatus(String? status) => setState(() {
        _filterStatus = status;
        _loadMoreFailed = false;
      });

  void _openNewTransfer() => showTransferFormBottomSheet(
        context: context,
        sourceStoreId: '',
        sourceStoreName: '',
        productId: '',
        productName: '',
        destinationStores: const [],
      );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    // Active store (sent AND received) + status chip go into the query.
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final provider = transferHistoryProvider(
        storeId: activeStoreId, status: _filterStatus);
    final historyAsync = ref.watch(provider);
    // A new list (store change, refresh, sync / form invalidation) starts
    // with auto-load on, never on a stale « Réessayer ».
    ref.listen(activeStoreIdProvider, (_, __) => _resetLoadMoreFailed());
    ref.listen(provider, (_, next) {
      if (next.isLoading) _resetLoadMoreFailed();
    });
    final stores = ref.watch(storeListNotifierProvider).value ?? [];
    // Store list may still be loading (empty) — no name rather than a crash.
    final activeStoreName = activeStoreId == null
        ? null
        : stores.where((s) => s.id == activeStoreId).firstOrNull?.name;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: RefreshIndicator(
        onRefresh: () async {
          setState(() => _loadMoreFailed = false);
          ref.invalidate(transferHistoryProvider);
          try {
            await ref.read(provider.future);
          } catch (_) {
            // The error state is shown by the list itself.
          }
        },
        child: CustomScrollView(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          // ── App bar — fond neutre, même style que la Vue Stock ─────────
          SliverAppBar(
            expandedHeight: 128,
            floating: false,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: cs.surface,
            leading: Navigator.of(context).canPop()
                ? IconButton(
                    icon: Icon(Icons.arrow_back_ios_new_rounded,
                        color: cs.onSurface, size: 20),
                    tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                    onPressed: () => Navigator.maybePop(context),
                  )
                : null,
            actions: [
              SoftIconButton(
                key: const Key('new_transfer_fab'),
                icon: Icons.add_rounded,
                iconSize: 20,
                tooltip: 'Nouveau transfert',
                onPressed: _openNewTransfer,
              ),
              const SizedBox(width: 12),
            ],
            flexibleSpace: FlexibleSpaceBar(
              background: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      Text(
                        'Transferts',
                        style: theme.textTheme.headlineMedium?.copyWith(
                          color: cs.onSurface,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.3,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          Icon(
                            activeStoreId != null
                                ? Icons.place_outlined
                                : Icons.storefront_outlined,
                            size: 16,
                            color: cs.onSurfaceVariant,
                          ),
                          const SizedBox(width: 4),
                          Expanded(
                            child: Text(
                              // The list is filtered as soon as a store is
                              // active, even while its name is loading.
                              activeStoreId == null
                                  ? 'Toutes les boutiques'
                                  : activeStoreName ?? '…',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: cs.onSurfaceVariant,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // ── Filter bar ─────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: _FilterBar(
              selected: _filterStatus,
              onSelect: _selectStatus,
            ),
          ),

          // ── Transfer list ───────────────────────────────────────────────
          // Rows + footer as a SliverList: only visible rows are laid out.
          ...historyAsync.when(
            loading: () => const [
              SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 80),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ),
            ],
            error: (e, _) => [
              SliverToBoxAdapter(
                child: AppErrorWidget(
                  error: e,
                  onRetry: () => ref.invalidate(provider),
                ),
              ),
            ],
            data: (state) {
              final transfers = state.items;
              if (transfers.isEmpty) {
                return [
                  SliverToBoxAdapter(
                    child: _EmptyState(
                      hasFilter:
                          _filterStatus != null || activeStoreId != null,
                    ),
                  ),
                ];
              }
              final notifier = ref.read(provider.notifier);
              final showFooter = state.hasMore || notifier.isLoadingMore;
              if (state.hasMore && !notifier.isLoadingMore) _fillViewport();
              return [
                SliverPadding(
                  padding: const EdgeInsets.only(bottom: 100),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (ctx, i) {
                        if (i == transfers.length) {
                          return _ListFooter(
                            failed:
                                _loadMoreFailed && !notifier.isLoadingMore,
                            onRetry: _loadMore,
                          );
                        }
                        return _TransferTile(transfer: transfers[i]);
                      },
                      childCount: transfers.length + (showFooter ? 1 : 0),
                    ),
                  ),
                ),
              ];
            },
          ),
        ],
      ),
      ),

      // AC1 — FAB opens transfer form, visible to OWNER and EMPLOYEE.
      // Story v1s-12-11 (FR36): OWNER transfers from any store; EMPLOYEE only
      // from their own store — the form locks the source, and the backend
      // (StockTransferController.transfer(), TransferSyncHandler) enforces it.
      // Couleur pleine + ombre douce, comme le FAB de la Vue Stock.
      floatingActionButton: Container(
        decoration: BoxDecoration(
          color: cs.primary,
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: cs.primary.withValues(alpha: 0.25),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: FloatingActionButton.extended(
          key: const Key('new_transfer_fab_bottom'),
          onPressed: _openNewTransfer,
          backgroundColor: Colors.transparent,
          foregroundColor: cs.onPrimary,
          elevation: 0,
          icon: const Icon(Icons.swap_horiz_rounded),
          label: const Text('Nouveau transfert',
              style: TextStyle(fontWeight: FontWeight.w600)),
        ),
      ),
    );
  }
}

/// End of the loaded list: spinner while the next page loads, « Réessayer »
/// when it failed (v1s-16-23).
class _ListFooter extends StatelessWidget {
  final bool failed;
  final VoidCallback onRetry;

  const _ListFooter({required this.failed, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    if (failed) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Center(
          child: TextButton.icon(
            key: const Key('transfer_history_retry'),
            onPressed: onRetry,
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('Réessayer'),
          ),
        ),
      );
    }
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 20),
      child: Center(child: CircularProgressIndicator()),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final bool hasFilter;
  const _EmptyState({required this.hasFilter});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 60, horizontal: 32),
      child: Column(
        children: [
          const SoftIconBadge(icon: Icons.swap_horiz_rounded, size: 72),
          const SizedBox(height: 16),
          Text(
            hasFilter
                ? 'Aucun transfert pour ce filtre'
                : 'Aucun transfert enregistré',
            style: theme.textTheme.titleMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }
}

/// AC5 — Filter bar: All / In transit / Completed / Pending / Conflict
class _FilterBar extends StatelessWidget {
  final String? selected;
  final ValueChanged<String?> onSelect;

  const _FilterBar({required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        border: Border(
          bottom: BorderSide(
            color: theme.colorScheme.outlineVariant,
            width: 0.5,
          ),
        ),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Row(
          children: [
            _Chip(
                label: 'Tous',
                active: selected == null,
                onTap: () => onSelect(null)),
            const SizedBox(width: 8),
            _Chip(
                label: 'En transit',
                icon: Icons.local_shipping_outlined,
                active: selected == 'IN_TRANSIT',
                color: AppTheme.iconTeal,
                onTap: () => onSelect('IN_TRANSIT')),
            const SizedBox(width: 8),
            _Chip(
                label: 'Effectués',
                icon: Icons.check_circle_outline_rounded,
                active: selected == 'COMPLETED',
                color: AppTheme.success,
                onTap: () => onSelect('COMPLETED')),
            const SizedBox(width: 8),
            _Chip(
                label: 'En attente',
                icon: Icons.schedule_rounded,
                active: selected == 'PENDING_SYNC',
                color: AppTheme.warning,
                onTap: () => onSelect('PENDING_SYNC')),
            const SizedBox(width: 8),
            _Chip(
                label: 'Conflits',
                icon: Icons.error_outline_rounded,
                active: selected == 'CONFLICT',
                color: AppTheme.errorColor,
                onTap: () => onSelect('CONFLICT')),
          ],
        ),
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  final String label;
  final bool active;
  final VoidCallback onTap;
  final Color? color;
  final IconData? icon;

  const _Chip({
    required this.label,
    required this.active,
    required this.onTap,
    this.color,
    this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final activeColor = color ?? theme.colorScheme.primary;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: active
              ? activeColor.withValues(alpha: 0.12)
              : theme.colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: active ? activeColor : Colors.transparent,
            width: 1.5,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null) ...[
              Icon(icon,
                  size: 14,
                  color: active
                      ? activeColor
                      : theme.colorScheme.onSurfaceVariant),
              const SizedBox(width: 4),
            ],
            Text(
              label,
              style: theme.textTheme.labelMedium?.copyWith(
                color:
                    active ? activeColor : theme.colorScheme.onSurfaceVariant,
                fontWeight: active ? FontWeight.w600 : FontWeight.normal,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TransferTile extends ConsumerStatefulWidget {
  final StockTransferModel transfer;

  const _TransferTile({required this.transfer});

  @override
  ConsumerState<_TransferTile> createState() => _TransferTileState();
}

class _TransferTileState extends ConsumerState<_TransferTile> {
  bool _isReceiving = false;

  Future<void> _receive() async {
    if (_isReceiving) return; // guard double-tap
    setState(() => _isReceiving = true);

    final messenger = ScaffoldMessenger.of(context);
    // 13.10b: built before the await (the context may be gone after it).
    final errorSnackBar = appErrorSnackBarFor(context);
    try {
      // Call repository directly — avoids AutoDispose "Bad state" from the
      // shared CompleteTransferNotifier being disposed mid-await.
      await ref
          .read(stockTransferRepositoryProvider)
          .completeTransfer(widget.transfer.id);

      ref.invalidate(transferHistoryProvider);
      ref.invalidate(globalStockOverviewProvider);
      ref.invalidate(storeStockDetailProvider);  // all store instances
      ref.invalidate(productListProvider);
      ref.invalidate(productListForPickerProvider);
      ref.invalidate(stockNotifierProvider);     // all product instances

      if (mounted) {
        messenger.showSnackBar(SnackBar(
          content: Row(children: [
            const Icon(Icons.check_circle_outline, color: AppTheme.onBrand, size: 18),
            const SizedBox(width: 10),
            Expanded(
                child: Text(
              'Stock réceptionné : ${widget.transfer.quantity}× ${widget.transfer.productName.isNotEmpty ? widget.transfer.productName : 'Produit'}',
              overflow: TextOverflow.ellipsis,
            )),
          ]),
          backgroundColor: AppTheme.successStrong,
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 4),
        ));
      }
    } on DioException catch (e) {
      final body = e.response?.data;
      final domainCode = body is Map ? body['domainCode'] as String? : null;

      // Already completed by another session → refresh silently (tile vanishes)
      if (domainCode == 'TRANSFER_INVALID_STATUS') {
        ref.invalidate(transferHistoryProvider);
        return;
      }

      // v1s-16-20: never the raw server text (ids, status codes).
      // 13.10b: a module refusal carries « Passer à Premium ».
      if (mounted) {
        messenger.showSnackBar(errorSnackBar(
          e,
          backgroundColor: AppTheme.errorStrong,
          behavior: SnackBarBehavior.floating,
        ));
      }
    } catch (e) {
      if (mounted) {
        messenger.showSnackBar(errorSnackBar(
          e,
          backgroundColor: AppTheme.errorStrong,
          behavior: SnackBarBehavior.floating,
        ));
      }
    } finally {
      if (mounted) setState(() => _isReceiving = false);
    }
  }

  /// Story v1s-12-9 — EMPLOYEE strict scope: only show "Réceptionner" when
  /// activeStoreId exactly matches the transfer's destination store.
  /// OWNER (role == 'OWNER' or null/unknown) keeps the existing behavior:
  /// activeStoreId == null (all stores) OR == destinationStoreId.
  bool _shouldShowReceiveButton(WidgetRef ref) {
    final role = ref.watch(currentUserRoleProvider);
    final activeStoreId = ref.watch(activeStoreIdProvider);
    if (role == 'EMPLOYEE') {
      return activeStoreId == widget.transfer.destinationStoreId;
    }
    // OWNER or unknown role — existing behavior, never scoped
    return activeStoreId == null ||
        activeStoreId == widget.transfer.destinationStoreId;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final dateLabel =
        DateFormat('dd/MM/yyyy · HH:mm').format(widget.transfer.occurredAt.toLocal());
    // v1s-16-20: a missing name reads as a neutral label, never the id.
    final srcName = widget.transfer.sourceStoreName.isNotEmpty
        ? widget.transfer.sourceStoreName
        : 'Boutique d\'origine';
    final dstName = widget.transfer.destinationStoreName.isNotEmpty
        ? widget.transfer.destinationStoreName
        : 'Boutique de destination';
    final productLabel = widget.transfer.productName.isNotEmpty
        ? widget.transfer.productName
        : 'Produit';

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      elevation: 0,
      color: cs.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        side: BorderSide(color: cs.outlineVariant, width: 0.8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Row 1: product + badge ─────────────────────────────────
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SoftIconBadge(icon: Icons.inventory_2_outlined, size: 36),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        productLabel,
                        style: theme.textTheme.bodyLarge?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        dateLabel,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                _StatusBadge(status: widget.transfer.status),
              ],
            ),
            const SizedBox(height: 10),
            // ── Row 2: source → destination ────────────────────────────
            Row(
              children: [
                Icon(Icons.store_outlined,
                    size: 14, color: cs.onSurfaceVariant),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(
                    srcName,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontWeight: FontWeight.w500,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6),
                  child: Icon(Icons.arrow_forward_rounded,
                      size: 14, color: cs.primary),
                ),
                Icon(Icons.store_mall_directory_outlined,
                    size: 14, color: cs.onSurfaceVariant),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(
                    dstName,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontWeight: FontWeight.w500,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                // Quantity pill
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: cs.secondaryContainer,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    '× ${widget.transfer.quantity}',
                    style: theme.textTheme.labelSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: cs.onSecondaryContainer,
                    ),
                  ),
                ),
              ],
            ),
            // ── Row 3: Réceptionner button (IN_TRANSIT, destination store only) ──
            // Story v1s-12-9: EMPLOYEE strict scope (activeStoreId == destinationStoreId),
            // OWNER unchanged (null OR destination).
            if (widget.transfer.status == 'IN_TRANSIT' &&
                _shouldShowReceiveButton(ref)) ...[
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: Key('receive_transfer_${widget.transfer.id}'),
                  onPressed: _isReceiving ? null : _receive,
                  icon: _isReceiving
                      ? SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Theme.of(context).colorScheme.onSurfaceVariant),
                        )
                      : const Icon(Icons.download_done_rounded, size: 18),
                  label: const Text('Réceptionner le stock',
                      style: TextStyle(fontWeight: FontWeight.w600)),
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.primary,
                    foregroundColor: Theme.of(context).colorScheme.onPrimary,
                    minimumSize: const Size.fromHeight(44),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12)),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String status;

  const _StatusBadge({required this.status});

  @override
  Widget build(BuildContext context) {
    final (icon, label, color) = switch (status) {
      'IN_TRANSIT'   => (Icons.local_shipping_outlined,      'En transit',         AppTheme.iconTeal),
      'COMPLETED'    => (Icons.check_circle_outline_rounded, 'Effectué',           AppTheme.success),
      'PENDING_SYNC' => (Icons.schedule_rounded,             'En attente de sync', AppTheme.warning),
      'CONFLICT'     => (Icons.error_outline_rounded,        'Conflit',            AppTheme.errorColor),
      _              => (Icons.circle_outlined,              _humanize(status),    Theme.of(context).colorScheme.onSurfaceVariant),
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: color),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

/// `SOME_STATUS` → « Some status » — an unknown status is never shown raw
/// (v1s-16-20).
String _humanize(String code) {
  final s = code.replaceAll('_', ' ').trim().toLowerCase();
  return s.isEmpty ? code : s[0].toUpperCase() + s.substring(1);
}
