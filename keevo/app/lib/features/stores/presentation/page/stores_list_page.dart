import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/widget/app_error_widget.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../features/settings/presentation/widget/plan_limit_bottom_sheet.dart';
import '../../domain/exception/store_exception.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';
import '../provider/store_provider.dart';
import '../widget/store_card.dart';
import '../widget/store_form_bottom_sheet.dart';

/// StoresListPage — main screen for store & warehouse management.
///
/// Access path: Settings → "Mes boutiques" (via /stores route).
/// Story 3.1 — Task 20.3.
class StoresListPage extends ConsumerStatefulWidget {
  const StoresListPage({super.key});

  @override
  ConsumerState<StoresListPage> createState() => _StoresListPageState();
}

class _StoresListPageState extends ConsumerState<StoresListPage> {
  bool _includeInactive = false;
  bool _isSyncing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncSilently());
  }

  Future<void> _syncSilently() async {
    await ref.read(storeListNotifierProvider.notifier).syncFromRemote();
  }

  Future<void> _syncWithFeedback() async {
    setState(() => _isSyncing = true);
    try {
      await ref.read(storeListNotifierProvider.notifier).syncFromRemote();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Row(children: [
              Icon(Icons.check_circle_rounded, color: Colors.white, size: 18),
              SizedBox(width: 8),
              Text('Boutiques synchronisées'),
            ]),
            backgroundColor: AppTheme.primary,
            behavior: SnackBarBehavior.floating,
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isSyncing = false);
    }
  }

  Future<void> _openCreateForm() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => StoreFormBottomSheet(
        onSubmit: (name, type, address, phone) =>
            _createStore(name, type, address, phone),
      ),
    );
  }

  Future<void> _openEditForm(StoreModel store) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => StoreFormBottomSheet(
        initialStore: store,
        onSubmit: (name, _, address, phone) =>
            _updateStore(store.id, name, address, phone),
      ),
    );
  }

  Future<void> _createStore(
      String name, StoreType type, String? address, String? phone) async {
    try {
      await ref
          .read(storeListNotifierProvider.notifier)
          .createStore(name: name, type: type, address: address, phone: phone);
    } on StoreException catch (e) {
      if (!mounted) return;
      if (e.domainCode == 'PLAN_LIMIT_EXCEEDED') {
        await showPlanLimitBottomSheet(
            context: context, entity: 'stores', limit: 3);
      } else if (e.domainCode == 'WAREHOUSE_ALREADY_EXISTS') {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.message),
            backgroundColor: Theme.of(context).colorScheme.error,
            behavior: SnackBarBehavior.floating,
            margin: const EdgeInsets.all(16),
          ),
        );
      } else {
        _showErrorSnackBar(e.message);
      }
    }
  }

  Future<void> _updateStore(
      String storeId, String name, String? address, String? phone) async {
    try {
      await ref.read(storeListNotifierProvider.notifier).updateStore(
            storeId: storeId,
            name: name,
            address: address,
            phone: phone,
          );
    } on StoreException catch (e) {
      if (mounted) _showErrorSnackBar(e.message);
    }
  }

  Future<void> _deactivateStore(String storeId) async {
    try {
      await ref
          .read(storeListNotifierProvider.notifier)
          .deactivateStore(storeId);
    } on StoreException catch (e) {
      if (mounted) _showErrorSnackBar(e.message);
    }
  }

  void _showErrorSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Theme.of(context).colorScheme.error,
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.all(16),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final storesAsync = ref.watch(storeListNotifierProvider);

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── AppBar ──────────────────────────────────────────────────
          SliverAppBar(
            expandedHeight: 140,
            pinned: true,
            elevation: 0,
            backgroundColor: Colors.transparent,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [AppTheme.primary, AppTheme.primary.withAlpha(180)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
              ),
              title: const Text(
                'Mes boutiques',
                style: TextStyle(color: Colors.white, fontSize: 18),
              ),
              titlePadding: const EdgeInsets.only(left: 16, bottom: 16),
            ),
            actions: [
              IconButton(
                icon: _isSyncing
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.sync_rounded, color: Colors.white),
                onPressed: _isSyncing ? null : _syncWithFeedback,
                tooltip: 'Synchroniser',
              ),
            ],
          ),

          // ── Filter toggle ─────────────────────────────────────────
          SliverToBoxAdapter(
            child: Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                children: [
                  const Text('Voir les désactivées'),
                  const Spacer(),
                  Switch(
                    value: _includeInactive,
                    onChanged: (v) {
                      setState(() => _includeInactive = v);
                      ref
                          .read(storeListNotifierProvider.notifier)
                          .refresh(includeInactive: v);
                    },
                  ),
                ],
              ),
            ),
          ),

          // ── Store list ─────────────────────────────────────────────
          storesAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (err, _) => SliverFillRemaining(
              child: AppErrorWidget(error: err),
            ),
            data: (stores) {
              if (stores.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text('🏪',
                            style: TextStyle(fontSize: 48)),
                        const SizedBox(height: 12),
                        Text(
                          'Aucune boutique',
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Appuyez sur + pour en créer une.',
                          style: theme.textTheme.bodyMedium
                              ?.copyWith(color: theme.colorScheme.outline),
                        ),
                      ],
                    ),
                  ),
                );
              }
              return SliverList(
                delegate: SliverChildBuilderDelegate(
                  (_, i) {
                    final store = stores[i];
                    return StoreCard(
                      store: store,
                      onEdit: () => _openEditForm(store),
                      onDeactivate: () => _deactivateStore(store.id),
                    );
                  },
                  childCount: stores.length,
                ),
              );
            },
          ),

          // ── Bottom padding for FAB ─────────────────────────────────
          const SliverToBoxAdapter(child: SizedBox(height: 80)),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openCreateForm,
        icon: const Icon(Icons.add_rounded),
        label: const Text('Ajouter une boutique'),
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
      ),
    );
  }
}
