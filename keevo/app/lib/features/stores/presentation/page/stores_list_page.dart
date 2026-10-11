import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/widget/app_error_widget.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../../core/widget/soft_icon_button.dart';
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

  /// Set when a creation hit the plan limit: the form closes, then the
  /// plan-limit sheet explains it (instead of both at once).
  bool _planLimitHit = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncSilently());
  }

  Future<void> _syncSilently() async {
    try {
      await ref.read(storeListNotifierProvider.notifier).syncFromRemote();
    } catch (_) {
      // Hors ligne : la liste locale reste affichée, sans message.
    }
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
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Synchronisation impossible : ${appErrorMessage(e)}'),
            backgroundColor: Theme.of(context).colorScheme.error,
            behavior: SnackBarBehavior.floating,
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isSyncing = false);
    }
  }

  Future<void> _openCreateForm() async {
    _planLimitHit = false;
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
    if (_planLimitHit && mounted) {
      _planLimitHit = false;
      await showPlanLimitBottomSheet(
          context: context, entity: 'stores', limit: 3);
    }
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

  // Les erreurs sont relancées : le formulaire reste ouvert et affiche le
  // message, au lieu de se fermer comme si la création avait réussi.
  // Exception : la limite du forfait ferme le formulaire, puis la feuille
  // « limite atteinte » s'affiche seule (voir [_openCreateForm]).
  Future<void> _createStore(
      String name, StoreType type, String? address, String? phone) async {
    try {
      await ref
          .read(storeListNotifierProvider.notifier)
          .createStore(name: name, type: type, address: address, phone: phone);
    } on StoreException catch (e) {
      if (e.domainCode != 'PLAN_LIMIT_EXCEEDED') rethrow;
      _planLimitHit = true;
    }
  }

  Future<void> _updateStore(
      String storeId, String name, String? address, String? phone) async {
    await ref.read(storeListNotifierProvider.notifier).updateStore(
          storeId: storeId,
          name: name,
          address: address,
          phone: phone,
        );
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
    final storesAsync = ref.watch(managedStoresProvider(_includeInactive));

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── AppBar — fond neutre, sans bandeau dégradé ──────────────
          SliverAppBar(
            expandedHeight: 96,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            foregroundColor: theme.colorScheme.onSurface,
            flexibleSpace: FlexibleSpaceBar(
              background: ColoredBox(color: theme.colorScheme.surface),
              centerTitle: false,
              title: Text(
                'Mes boutiques',
                style: TextStyle(
                  color: theme.colorScheme.onSurface,
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                ),
              ),
              titlePadding: const EdgeInsets.fromLTRB(56, 0, 16, 16),
            ),
            actions: [
              SoftIconButton(
                icon: Icons.sync_rounded,
                tooltip: 'Synchroniser',
                loading: _isSyncing,
                onPressed: _syncWithFeedback,
              ),
              const SizedBox(width: 12),
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
                    onChanged: (v) => setState(() => _includeInactive = v),
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
              child: AppErrorWidget(
                error: err,
                onRetry: () =>
                    ref.invalidate(managedStoresProvider(_includeInactive)),
              ),
            ),
            data: (stores) {
              if (stores.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SoftIconBadge(
                          icon: Icons.storefront_outlined,
                          size: 72,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Aucune boutique',
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Appuyez sur « Ajouter une boutique » pour en créer une.',
                          style: theme.textTheme.bodyMedium
                              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
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
        backgroundColor: theme.colorScheme.primary,
        foregroundColor: theme.colorScheme.onPrimary,
      ),
    );
  }
}

