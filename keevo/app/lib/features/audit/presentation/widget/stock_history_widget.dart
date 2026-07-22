import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../provider/audit_provider.dart';

/// StockHistoryWidget — displays an entity's paginated audit trail.
///
/// Accepts optional [entityType] and [entityId] for filtered queries.
/// When both are null, shows the full tenant log.
/// Implements infinite scroll: triggers [AuditHistoryNotifier.loadMore()]
/// when the user scrolls within 300px of the bottom.
///
/// AC6: embeddable in any entity detail screen (product, user, store).
class StockHistoryWidget extends ConsumerStatefulWidget {
  /// Optional filter — entity type (e.g. "Product", "User").
  final String? entityType;

  /// Optional filter — entity UUID string.
  final String? entityId;

  const StockHistoryWidget({
    super.key,
    this.entityType,
    this.entityId,
  });

  @override
  ConsumerState<StockHistoryWidget> createState() => _StockHistoryWidgetState();
}

class _StockHistoryWidgetState extends ConsumerState<StockHistoryWidget> {
  late final ScrollController _scrollController;
  bool _isLoadingMore = false;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController()..addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    final pos = _scrollController.position;
    if (pos.pixels >= pos.maxScrollExtent - 300) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore) return;
    final notifier = ref.read(
      auditHistoryNotifierProvider(
        entityType: widget.entityType,
        entityId: widget.entityId,
      ).notifier,
    );
    if (!notifier.hasMore) return;
    setState(() => _isLoadingMore = true);
    await notifier.loadMore();
    if (mounted) setState(() => _isLoadingMore = false);
  }

  @override
  Widget build(BuildContext context) {
    final historyAsync = ref.watch(
      auditHistoryNotifierProvider(
        entityType: widget.entityType,
        entityId: widget.entityId,
      ),
    );
    final currentUserIdAsync = ref.watch(currentUserIdProvider);
    final currentUserPhone   = ref.watch(currentUserPhoneProvider);
    final currentUserId      = currentUserIdAsync.valueOrNull;

    return historyAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (err, _) => Center(
        child: Text(
          'Erreur lors du chargement de l\'historique',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.error,
              ),
        ),
      ),
      data: (entries) {
        final notifier = ref.read(
          auditHistoryNotifierProvider(
            entityType: widget.entityType,
            entityId: widget.entityId,
          ).notifier,
        );
        return _buildList(context, entries, currentUserId, currentUserPhone,
            notifier.hasMore);
      },
    );
  }

  Widget _buildList(
    BuildContext context,
    List<AuditEntryDto> entries,
    String? currentUserId,
    String? currentUserPhone,
    bool hasMore,
  ) {
    if (entries.isEmpty) {
      return const Center(
        child: _EmptyStateWidget(),
      );
    }

    final showFooter = hasMore || _isLoadingMore;
    final itemCount = entries.length + (showFooter ? 1 : 0);

    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(
        auditHistoryNotifierProvider(
          entityType: widget.entityType,
          entityId: widget.entityId,
        ),
      ),
      child: ListView.separated(
        controller: _scrollController,
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: itemCount,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          if (index == entries.length) {
            return const Padding(
              padding: EdgeInsets.symmetric(vertical: 20),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          return _AuditEntryTile(
            entry: entries[index],
            currentUserId: currentUserId,
            currentUserPhone: currentUserPhone,
          );
        },
      ),
    );
  }
}

// ── Internal widgets ──────────────────────────────────────────────────────────

class _EmptyStateWidget extends StatelessWidget {
  const _EmptyStateWidget();

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(
          Icons.history,
          size: 64,
          color: Theme.of(context).colorScheme.outlineVariant,
        ),
        const SizedBox(height: 16),
        Text(
          'Aucun historique disponible',
          style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
        ),
      ],
    );
  }
}

class _AuditEntryTile extends StatelessWidget {
  final AuditEntryDto entry;
  final String? currentUserId;
  final String? currentUserPhone;

  const _AuditEntryTile({
    required this.entry,
    required this.currentUserId,
    required this.currentUserPhone,
  });

  /// Returns the actor label: "Vous" with phone if current user,
  /// the phone number from backend JOIN for other users, or fallback short ID.
  String _actorLabel() {
    final isSelf = currentUserId != null &&
        entry.userId == currentUserId;
    if (isSelf) {
      // Prefer backend actorPhone (authoritative) over locally stored phone
      // to avoid stale values after two-step login user switch.
      final phone = entry.actorPhone ?? currentUserPhone;
      return phone != null ? 'Vous ($phone)' : 'Vous';
    }
    // actorPhone is resolved by the backend via LEFT JOIN public.users
    return entry.actorPhone ??
        entry.userId.substring(entry.userId.length - 8);
  }

  /// Maps backend action codes to French section titles.
  static String _actionLabel(String action) => switch (action) {
        'USER_REGISTERED'         => 'Inscription',
        'USER_AUTHENTICATED'      => 'Connexion',
        'ONBOARDING_COMPLETED'    => 'Configuration boutique',
        'PRODUCT_CREATED'         => 'Nouveau produit',
        'PRODUCT_UPDATED'         => 'Produit modifié',
        'PRODUCT_ARCHIVED'        => 'Produit archivé',
        'PRICE_OVERRIDDEN'        => 'Prix de vente modifié',
        'STOCK_ADJUSTED'          => 'Mouvement de stock',
        'STOCK_THRESHOLD_BREACHED'=> 'Alerte stock',
        'CLIENT_CREATED'          => 'Nouveau client',
        'CLIENT_ARCHIVED'         => 'Client archivé',
        'SUPPLIER_CREATED'        => 'Nouveau fournisseur',
        'SUPPLIER_ARCHIVED'       => 'Fournisseur archivé',
        'STORE_CREATED'           => 'Point de vente créé',
        'STORE_UPDATED'           => 'Point de vente modifié',
        'STORE_DEACTIVATED'       => 'Point de vente désactivé',
        'SALE_COMPLETED'          => 'Vente enregistrée',
        'TRANSFER_COMPLETED'      => 'Transfert effectué',
        'STOCK_RECEIVED'          => 'Entrée de stock',
        _ => action.replaceAll('_', ' ').toLowerCase(),
      };

  /// Builds a human-readable description from the action + JSON payloads.
  static String _buildDescription(
      String action, String? valueBefore, String? valueAfter) {
    Map<String, dynamic> after = {};
    Map<String, dynamic> before = {};
    try {
      if (valueAfter != null) after = jsonDecode(valueAfter) as Map<String, dynamic>;
      if (valueBefore != null) before = jsonDecode(valueBefore) as Map<String, dynamic>;
    } catch (_) {}

    switch (action) {
      case 'USER_REGISTERED':
        return 'Nouveau compte créé';

      case 'USER_AUTHENTICATED':
        final role = after['role'] as String?;
        return role == 'OWNER'
            ? 'Connexion en tant que Propriétaire'
            : 'Connexion en tant qu\'Employé';

      case 'ONBOARDING_COMPLETED':
        final store = after['storeName'] as String?;
        return store != null
            ? 'Boutique «$store» configurée'
            : 'Configuration boutique terminée';

      case 'PRODUCT_CREATED':
        final name = after['productName'] as String?;
        return name != null ? 'Produit «$name» ajouté' : 'Nouveau produit ajouté';

      case 'PRODUCT_UPDATED':
        final name = after['productName'] as String?;
        return name != null ? 'Produit «$name» mis à jour' : 'Produit mis à jour';

      case 'PRODUCT_ARCHIVED':
        final name = after['productName'] as String?;
        return name != null ? 'Produit «$name» archivé' : 'Produit archivé';

      case 'PRICE_OVERRIDDEN':
        final from = before['cataloguePrice'];
        final to   = after['appliedPrice'];
        if (from != null && to != null) return 'Prix modifié : $from → $to FCFA';
        return 'Prix de vente modifié';

      case 'STOCK_ADJUSTED':
        final type      = after['movementType'] as String?;
        final change    = after['quantityChange'];
        final afterQty  = after['quantityAfter'];
        final sign      = (change is num && change >= 0) ? '+' : '';
        if (change != null && afterQty != null) {
          return 'Stock ${_movementLabel(type)} : ${sign}$change unité(s) · total : $afterQty';
        }
        return 'Ajustement de stock';

      case 'STOCK_THRESHOLD_BREACHED':
        final product   = after['productName'] as String?;
        final qty       = after['currentQuantity'];
        final threshold = after['threshold'];
        if (product != null) {
          return 'Stock critique : «$product» — $qty unité(s) (seuil : $threshold)';
        }
        return 'Seuil de stock atteint';

      case 'CLIENT_CREATED':
        final name = after['name'] as String?;
        return name != null ? 'Client «$name» ajouté' : 'Nouveau client ajouté';

      case 'CLIENT_ARCHIVED':
        final name = after['name'] as String?;
        return name != null ? 'Client «$name» archivé' : 'Client archivé';

      case 'SUPPLIER_CREATED':
        final name = after['name'] as String?;
        return name != null ? 'Fournisseur «$name» ajouté' : 'Nouveau fournisseur ajouté';

      case 'SUPPLIER_ARCHIVED':
        final name = after['name'] as String?;
        return name != null ? 'Fournisseur «$name» archivé' : 'Fournisseur archivé';

      case 'STORE_CREATED':
        final name  = after['name'] as String?;
        final type  = after['type'] as String?;
        final label = type == 'WAREHOUSE' ? 'Entrepôt' : 'Boutique';
        return name != null ? '$label «$name» créé(e)' : 'Nouveau point de vente créé';

      case 'STORE_UPDATED':
        final name = after['newName'] as String?;
        return name != null ? 'Boutique renommée : «$name»' : 'Boutique mise à jour';

      case 'STORE_DEACTIVATED':
        final name = after['name'] as String?;
        return name != null ? 'Boutique «$name» désactivée' : 'Boutique désactivée';

      default:
        return '';
    }
  }

  static String _movementLabel(String? type) => switch (type) {
        'SALE'          => 'vendu',
        'PURCHASE'      => 'réceptionné',
        'ADJUSTMENT'    => 'ajusté',
        'TRANSFER_OUT'  => 'transféré (sortie)',
        'TRANSFER_IN'   => 'transféré (entrée)',
        'SALE_CANCELLED' => 'restauré (vente annulée)',
        _               => 'modifié',
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final local = entry.occurredAt.toLocal();
    final formattedDate =
        '${local.day.toString().padLeft(2, '0')}/${local.month.toString().padLeft(2, '0')}/${local.year} '
        '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';

    final description = _buildDescription(entry.action, entry.valueBefore, entry.valueAfter);
    final actor = _actorLabel();

    return ListTile(
      dense: true,
      title: Text(
        _actionLabel(entry.action),
        style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            actor,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.primary,
              fontWeight: FontWeight.w500,
            ),
          ),
          if (description.isNotEmpty)
            Text(
              description,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
        ],
      ),
      trailing: Text(
        formattedDate,
        style: theme.textTheme.bodySmall,
      ),
    );
  }
}
