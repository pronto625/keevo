import '../../../../core/theme/app_theme.dart';
import '../../../../core/di/providers.dart';
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
    final historyAsync = ref.watch(transferHistoryProvider());

    // Filter by active store if one is selected
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final stores = ref.watch(storeListNotifierProvider).value ?? [];
    final activeStoreName = activeStoreId == null
        ? null
        : stores.firstWhere(
            (s) => s.id == activeStoreId,
            orElse: () => stores.first,
          ).name;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── Gradient header ─────────────────────────────────────────────
          SliverAppBar(
            expandedHeight: 140,
            floating: false,
            pinned: true,
            elevation: 0,
            backgroundColor: Colors.transparent,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      theme.colorScheme.primary,        // #3B5BDB indigo royal
                      AppTheme.primaryGradientEnd,          // bleu ciel — gradient UX spec
                    ],
                  ),
                ),
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 16, 24, 16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '🔄 Transferts',
                                    style: theme.textTheme.headlineMedium
                                        ?.copyWith(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    activeStoreName != null
                                        ? '📍 $activeStoreName'
                                        : 'Toutes les boutiques',
                                    style: theme.textTheme.bodyMedium
                                        ?.copyWith(
                                      color: Colors.white.withValues(alpha: 0.9),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            // FAB-style button in header
                            Container(
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: 0.2),
                                borderRadius: BorderRadius.circular(14),
                              ),
                              child: IconButton(
                                key: const Key('new_transfer_fab'),
                                icon: const Icon(Icons.add_rounded,
                                    color: Colors.white),
                                tooltip: 'Nouveau transfert',
                                onPressed: _openNewTransfer,
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
          ),

          // ── Filter bar ─────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: _FilterBar(
              selected: _filterStatus,
              onSelect: (v) => setState(() => _filterStatus = v),
            ),
          ),

          // ── Transfer list ───────────────────────────────────────────────
          SliverToBoxAdapter(
            child: historyAsync.when(
              loading: () =>
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 80),
                    child: Center(child: CircularProgressIndicator()),
                  ),
              error: (e, _) => Padding(
                padding: const EdgeInsets.all(24),
                child: _ErrorCard(message: e.toString()),
              ),
              data: (transfers) {
                // 1. Restrict to active store if set:
                //    Show transfers where the active store is either the
                //    SOURCE (outgoing) or the DESTINATION (incoming).
                final storeFiltered = activeStoreId == null
                    ? transfers
                    : transfers
                        .where((t) =>
                            t.destinationStoreId == activeStoreId ||
                            t.sourceStoreId == activeStoreId)
                        .toList();
                // 2. Further filter by status chip
                final filtered = _filterStatus == null
                    ? storeFiltered
                    : storeFiltered
                        .where((t) => t.status == _filterStatus)
                        .toList();
                if (filtered.isEmpty) {
                  return _EmptyState(
                    hasFilter:
                        _filterStatus != null || activeStoreId != null,
                  );
                }
                return RefreshIndicator(
                  onRefresh: () async =>
                      ref.invalidate(transferHistoryProvider),
                  child: ListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    padding: const EdgeInsets.only(bottom: 100),
                    itemCount: filtered.length,
                    itemBuilder: (ctx, i) =>
                        _TransferTile(transfer: filtered[i]),
                  ),
                );
              },
            ),
          ),
        ],
      ),

      // AC1 — FAB opens transfer form (Story 12.6: OWNER-only)
      floatingActionButton: ref.watch(currentUserRoleProvider) != 'EMPLOYEE'
          ? FloatingActionButton.extended(
              key: const Key('new_transfer_fab_bottom'),
              onPressed: _openNewTransfer,
              icon: const Icon(Icons.swap_horiz_rounded),
              label: const Text('Nouveau transfert'),
              backgroundColor: theme.colorScheme.primary,
              foregroundColor: Colors.white,
            )
          : null,
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
          Icon(Icons.swap_horiz_rounded,
              size: 56, color: theme.colorScheme.outlineVariant),
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

class _ErrorCard extends StatelessWidget {
  final String message;
  const _ErrorCard({required this.message});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: cs.onErrorContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Text(message,
                style: TextStyle(color: cs.onErrorContainer)),
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
                label: '🚚 En transit',
                active: selected == 'IN_TRANSIT',
                color: Colors.teal,
                onTap: () => onSelect('IN_TRANSIT')),
            const SizedBox(width: 8),
            _Chip(
                label: '✅ Effectués',
                active: selected == 'COMPLETED',
                color: AppTheme.success,
                onTap: () => onSelect('COMPLETED')),
            const SizedBox(width: 8),
            _Chip(
                label: '⏳ En attente',
                active: selected == 'PENDING_SYNC',
                color: AppTheme.warning,
                onTap: () => onSelect('PENDING_SYNC')),
            const SizedBox(width: 8),
            _Chip(
                label: '❌ Conflits',
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

  const _Chip({
    required this.label,
    required this.active,
    required this.onTap,
    this.color,
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
        child: Text(
          label,
          style: theme.textTheme.labelMedium?.copyWith(
            color: active ? activeColor : theme.colorScheme.onSurfaceVariant,
            fontWeight: active ? FontWeight.w600 : FontWeight.normal,
          ),
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
            const Icon(Icons.check_circle_outline, color: Colors.white, size: 18),
            const SizedBox(width: 10),
            Expanded(
                child: Text(
              'Stock réceptionné : ${widget.transfer.quantity}× ${widget.transfer.productName.isNotEmpty ? widget.transfer.productName : widget.transfer.productId}',
              overflow: TextOverflow.ellipsis,
            )),
          ]),
          backgroundColor: AppTheme.success,
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

      final msg = (body is Map ? body['error'] ?? body['message'] : null) ??
          'Erreur réseau';
      if (mounted) {
        messenger.showSnackBar(SnackBar(
          content: Text(msg.toString()),
          backgroundColor: AppTheme.errorColor,
          behavior: SnackBarBehavior.floating,
        ));
      }
    } catch (e) {
      if (mounted) {
        messenger.showSnackBar(SnackBar(
          content: Text(e.toString()),
          backgroundColor: AppTheme.errorColor,
          behavior: SnackBarBehavior.floating,
        ));
      }
    } finally {
      if (mounted) setState(() => _isReceiving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final dateLabel =
        DateFormat('dd/MM/yyyy · HH:mm').format(widget.transfer.occurredAt.toLocal());
    final srcName = widget.transfer.sourceStoreName.isNotEmpty
        ? widget.transfer.sourceStoreName
        : widget.transfer.sourceStoreId;
    final dstName = widget.transfer.destinationStoreName.isNotEmpty
        ? widget.transfer.destinationStoreName
        : widget.transfer.destinationStoreId;
    final productLabel = widget.transfer.productName.isNotEmpty
        ? widget.transfer.productName
        : widget.transfer.productId;

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: cs.outlineVariant),
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
                Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: cs.primaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(Icons.inventory_2_outlined,
                      size: 18, color: cs.primary),
                ),
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
            if (widget.transfer.status == 'IN_TRANSIT' &&
                (ref.watch(activeStoreIdProvider) == null ||
                 ref.watch(activeStoreIdProvider) ==
                     widget.transfer.destinationStoreId)) ...[
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: Key('receive_transfer_${widget.transfer.id}'),
                  onPressed: _isReceiving ? null : _receive,
                  icon: _isReceiving
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white),
                        )
                      : const Icon(Icons.download_done_rounded, size: 18),
                  label: const Text('Réceptionner le stock',
                      style: TextStyle(fontWeight: FontWeight.w600)),
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.primary,
                    foregroundColor: Colors.white,
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
      'IN_TRANSIT'   => ('🚚', 'En transit',         Colors.teal),
      'COMPLETED'    => ('✅', 'Effectué',           AppTheme.success),
      'PENDING_SYNC' => ('⏳', 'En attente de sync', AppTheme.warning),
      'CONFLICT'     => ('❌', 'Conflit',             AppTheme.errorColor),
      _              => ('·', status,               Theme.of(context).colorScheme.onSurfaceVariant),
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(icon, style: const TextStyle(fontSize: 10)),
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
