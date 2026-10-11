import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../domain/exception/product_exception.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_status.dart';
import '../provider/product_provider.dart';
import '../provider/stock_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../pos/presentation/provider/pos_providers.dart';
import '../../../pos/presentation/provider/day_closure_providers.dart';
import '../../../../core/di/providers.dart';
import 'cross_store_availability_bottom_sheet.dart';

/// ProductCard — displays a product in the catalogue list.
///
/// Long-press shows a context menu with an "Archiver" option (AC6).
class ProductCard extends ConsumerWidget {
  final ProductModel product;

  const ProductCard({super.key, required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final actions = ref.read(productActionsProvider);
    final stockState = ref.watch(stockNotifierProvider(product.id));
    final activeStoreId = ref.watch(activeStoreIdProvider);
    // Compute visible stock: for active store only, or total across all stores.
    final visibleLevels = activeStoreId == null
        ? stockState.levels
        : stockState.levels.where((l) => l.storeId == activeStoreId).toList();
    // Same scope as the displayed quantity.
    final isLowStock = visibleLevels.any((l) => l.isLow);
    final totalStock = visibleLevels.fold<int>(0, (sum, l) => sum + l.quantity);
    final hasStockData = stockState.levels.isNotEmpty;

    final cs = theme.colorScheme;
    final stockColor = totalStock == 0
        ? AppTheme.errorColor
        : isLowStock
            ? AppTheme.warning
            : Colors.teal;

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: AppTheme.softCardDecoration(cs),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          onTap: () => _openEdit(context, ref),
          onLongPress: () => _showContextMenu(context, ref, actions),
          child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  // Avatar modernisé avec animation
                  Hero(
                    tag: 'product-avatar-${product.id}',
                    child: _buildModernAvatar(theme),
                  ),
                  const SizedBox(width: 16),
                  // Contenu principal
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                product.name,
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.w600,
                                  letterSpacing: -0.2,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          product.sku.isNotEmpty ? 'REF: ${product.sku}' : 'Sans référence',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 8),
                        // Ligne prix et statut — Wrap : brouillon + stock bas
                        // ne tiennent pas sur une ligne à 360dp.
                        Wrap(
                          spacing: 6,
                          runSpacing: 6,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 160),
                              child: Container(
                                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                decoration: BoxDecoration(
                                  color: AppTheme.softTint(theme.brightness),
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Text(
                                  '${product.price} XAF',
                                  style: theme.textTheme.titleSmall?.copyWith(
                                    color: theme.colorScheme.primary,
                                    fontWeight: FontWeight.bold,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ),
                            // Stock badge
                            if (hasStockData)
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                                decoration: BoxDecoration(
                                  color: stockColor.withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.inventory_2_outlined,
                                      size: 12,
                                      color: stockColor,
                                    ),
                                    const SizedBox(width: 4),
                                    Text(
                                      '$totalStock',
                                      style: theme.textTheme.labelSmall?.copyWith(
                                        color: stockColor,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            // Indicateur d'état
                            // Badge Archivé
                            if (product.archived)
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  color: AppTheme.warning.withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.archive_outlined,
                                      size: 12,
                                      color: AppTheme.warning,
                                    ),
                                    const SizedBox(width: 4),
                                    Text(
                                      'Archivé',
                                      style: theme.textTheme.labelSmall?.copyWith(
                                        color: AppTheme.warning,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            // Badge Brouillon (AC6 — DRAFT status)
                            if (product.status == ProductStatus.draft) ...[
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  color: AppTheme.warning
                                      .withValues(alpha: 0.18),
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(Icons.edit_note_rounded,
                                        size: 12, color: Color(0xFFF08C00)),
                                    const SizedBox(width: 4),
                                    Text(
                                      'Brouillon',
                                      style:
                                          theme.textTheme.labelSmall?.copyWith(
                                        color: const Color(0xFFF08C00),
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                            if (isLowStock) ...
                              [
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                  decoration: BoxDecoration(
                                    color: AppTheme.errorColor.withValues(alpha: 0.12),
                                    borderRadius: BorderRadius.circular(20),
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Icon(
                                        Icons.warning_amber_rounded,
                                        size: 12,
                                        color: AppTheme.errorColor,
                                      ),
                                      const SizedBox(width: 4),
                                      Text(
                                        'Stock bas',
                                        style: theme.textTheme.labelSmall?.copyWith(
                                          color: AppTheme.errorColor,
                                          fontWeight: FontWeight.w500,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                          ],
                        ),
                      ],
                    ),
                  ),
                  // Indicateur d'interaction
                  const SizedBox(width: 8),
                  const SoftIconBadge(icon: Icons.touch_app_outlined, size: 36),
                ],
              ),
            ),
        ),
      ),
    );
  }

  Widget _buildModernAvatar(ThemeData theme) {
    if (product.photoUrl != null && product.photoUrl!.isNotEmpty) {
      return SizedBox(
        width: 60,
        height: 60,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          child: _buildProductImage(product.photoUrl!, theme),
        ),
      );
    }

    return _buildInitials(theme);
  }

  void _showContextMenu(
    BuildContext context,
    WidgetRef ref,
    ProductActions actions,
  ) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      // La feuille dessine sa propre poignée ; sans ceci celle du thème
      // flotterait au-dessus, sur le fond transparent.
      showDragHandle: false,
      builder: (_) => Container(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface,
          borderRadius: const BorderRadius.vertical(
              top: Radius.circular(AppTheme.radiusXL)),
        ),
        child: SafeArea(
          bottom: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Handle moderne
              Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(top: 12, bottom: 20),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              // En-tête avec avatar
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                child: Row(
                  children: [
                    _buildModernAvatar(Theme.of(context)),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            product.name,
                            style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.2,
                            ),
                          ),
                          Text(
                            '${product.price} XAF',
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context).colorScheme.primary,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              // Option: Valider (DRAFT + OWNER only)
              if (product.status == ProductStatus.draft &&
                  ref.read(currentUserRoleProvider) == 'OWNER')
                _buildMenuOption(
                  context,
                  icon: Icons.check_circle_outline_rounded,
                  title: 'Valider ce produit',
                  subtitle: 'Promouvoir de Brouillon → Actif',
                  color: AppTheme.success,
                  onTap: () {
                    Navigator.pop(context);
                    _confirmPromote(context, ref);
                  },
                ),
              // Options
              _buildMenuOption(
                context,
                icon: Icons.edit_outlined,
                title: 'Modifier',
                subtitle: 'Éditer les informations',
                color: Theme.of(context).colorScheme.primary,
                onTap: () {
                  Navigator.pop(context);
                  _openEdit(context, ref);
                },
              ),
              _buildMenuOption(
                context,
                icon: Icons.store_outlined,
                title: 'Disponibilité cross-boutique',
                subtitle: 'Voir le stock dans toutes les boutiques',
                color: Colors.teal,
                onTap: () {
                  Navigator.pop(context);
                  showCrossStoreAvailabilitySheet(
                    context: context,
                    productId: product.id,
                    productName: product.name,
                  );
                },
              ),
              if (!product.archived)
                _buildMenuOption(
                  context,
                  icon: Icons.archive_outlined,
                  title: 'Archiver',
                  subtitle: 'Masquer du catalogue',
                  color: AppTheme.warning,
                  onTap: () {
                    Navigator.pop(context);
                    _confirmArchive(context, actions);
                  },
                )
              else
                _buildMenuOption(
                  context,
                  icon: Icons.unarchive_outlined,
                  title: 'Désarchiver',
                  subtitle: 'Remettre dans le catalogue',
                  color: AppTheme.success,
                  onTap: () {
                    Navigator.pop(context);
                    _confirmUnarchive(context, actions);
                  },
                ),
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }

  /// Opens the edit form — except for an EMPLOYEE on a DRAFT product
  /// (v1s-12-10): any EMPLOYEE update of a draft is rejected server-side
  /// (Story 2.4 guard), so the form is not offered rather than letting the
  /// edit diverge locally and fail in sync.
  void _openEdit(BuildContext context, WidgetRef ref) {
    if (product.status == ProductStatus.draft &&
        ref.read(currentUserRoleProvider) != 'OWNER') {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content:
              Text('Seul le propriétaire peut modifier un produit brouillon'),
          behavior: SnackBarBehavior.floating,
        ),
      );
      return;
    }
    context.push('/products/${product.id}/edit', extra: product);
  }

  Widget _buildMenuOption(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
        child: Row(
          children: [
            SoftIconBadge(icon: icon, color: color, size: 44),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    subtitle,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _confirmPromote(BuildContext context, WidgetRef ref) {
    final actions = ref.read(productActionsProvider);
    final storeId = ref.read(activeStoreIdProvider);
    // Plain value instead of a controller: the dialog still renders its
    // TextField during the exit animation, after showDialog has completed.
    var stockText = '';
    showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
          title: const Row(
            children: [
              Icon(Icons.check_circle_outline_rounded, color: AppTheme.success, size: 28),
              SizedBox(width: 12),
              Expanded(child: Text('Valider ce produit ?')),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Ce brouillon deviendra un produit actif, visible dans le catalogue et le POS.',
                style: TextStyle(height: 1.4),
              ),
              if (storeId != null) ...[
                const SizedBox(height: 16),
                TextField(
                  onChanged: (v) => stockText = v,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    // v1s-12-10: additive (recordEntry) — an employee may
                    // already have entered stock when selling the draft.
                    labelText: 'Stock à ajouter',
                    hintText: '0',
                    suffixText: 'unités',
                    helperText: 'S\'ajoute au stock déjà saisi en boutique',
                  ),
                ),
              ],
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text(
                'Annuler',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            FilledButton(
              onPressed: () async {
                final initialStock = int.tryParse(stockText.trim()) ?? 0;
                // Capture all provider reads BEFORE async gap —
                // the ProductCard will be disposed once productListProvider
                // is invalidated (draft removed from list).
                final stockRepo = ref.read(stockRepositoryProvider);
                final localSaleDs = ref.read(localSaleDataSourceProvider);
                final actorIdFuture = ref.read(currentUserIdProvider.future);
                final container = ProviderScope.containerOf(context);
                // The card is disposed once the draft leaves the list:
                // feedback must not depend on its context.
                final messenger = ScaffoldMessenger.of(context);
                Navigator.pop(ctx);
                try {
                  final originalId = product.id;
                  final newId = await actions.promoteToActive(originalId);
                  if (initialStock > 0 && storeId != null) {
                    await stockRepo.recordEntry(
                          productId: newId,
                          storeId: storeId,
                          quantity: initialStock,
                          notes: 'Stock initial à la validation du brouillon',
                        );
                    container.invalidate(stockNotifierProvider(newId));
                    container.invalidate(productListProvider);
                  }
                  // Cascade: auto-validate pending sales that contained this draft.
                  if (storeId != null) {
                    final actorId = await actorIdFuture ?? '';
                    final validated = await localSaleDs
                        .cascadeValidatePendingSales(newId, storeId, actorId);
                    if (validated > 0) {
                      container.invalidate(pendingSalesProvider(storeId));
                      container.invalidate(pendingSalesCountProvider(storeId));
                      container.invalidate(stockNotifierProvider(newId));
                      container.invalidate(todaySummaryProvider); // invalidate whole family
                      container.invalidate(dayClosureStateProvider(storeId));
                    }
                  }
                  container.read(showDraftsOnlyProvider.notifier).state = false;
                  messenger.showSnackBar(
                    SnackBar(
                      content: const Row(
                        children: [
                          Icon(Icons.check_circle, color: Colors.white),
                          SizedBox(width: 8),
                          Text('Produit validé avec succès'),
                        ],
                      ),
                      backgroundColor: AppTheme.success,
                      behavior: SnackBarBehavior.floating,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                  );
                } catch (e) {
                  messenger.showSnackBar(
                    SnackBar(
                      content: Row(
                        children: [
                          const Icon(Icons.error, color: Colors.white),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(e is ProductException
                                ? e.message
                                : appErrorMessage(e)),
                          ),
                        ],
                      ),
                      backgroundColor: AppTheme.errorColor,
                      behavior: SnackBarBehavior.floating,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                  );
                }
              },
              style: FilledButton.styleFrom(
                backgroundColor: AppTheme.success,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: const Text('Valider'),
            ),
          ],
        ),
      ),
    );
  }

  void _confirmArchive(BuildContext context, ProductActions actions) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
        ),
        title: Row(
          children: [
            Icon(
              Icons.archive_outlined,
              color: AppTheme.warning,
              size: 28,
            ),
            const SizedBox(width: 12),
            const Text('Archiver ce produit ?'),
          ],
        ),
        content: const Text(
          'Il ne sera plus visible dans le POS mais son historique sera conservé.',
          style: TextStyle(height: 1.4),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(
              'Annuler',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          FilledButton(
            onPressed: () async {
              // Captured before the await: the card may leave the list once
              // the product lists are invalidated.
              final messenger = ScaffoldMessenger.of(context);
              Navigator.pop(ctx);
              try {
                await actions.archive(product.id);
                messenger.showSnackBar(
                  SnackBar(
                    content: Row(
                      children: [
                        Icon(Icons.check_circle, color: Colors.white),
                        const SizedBox(width: 8),
                        const Text('Produit archivé avec succès'),
                      ],
                    ),
                    backgroundColor: AppTheme.success,
                    behavior: SnackBarBehavior.floating,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                );
              } catch (e) {
                messenger.showSnackBar(
                  SnackBar(
                    content: Row(
                      children: [
                        Icon(Icons.error, color: Colors.white),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(e is ProductException
                              ? e.message
                              : appErrorMessage(e)),
                        ),
                      ],
                    ),
                    backgroundColor: AppTheme.errorColor,
                    behavior: SnackBarBehavior.floating,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                );
              }
            },
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.warning,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: const Text('Archiver'),
          ),
        ],
      ),
    );
  }

  void _confirmUnarchive(BuildContext context, ProductActions actions) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
        ),
        title: Row(
          children: [
            Icon(
              Icons.unarchive_outlined,
              color: AppTheme.success,
              size: 28,
            ),
            const SizedBox(width: 12),
            const Text('Désarchiver ce produit ?'),
          ],
        ),
        content: const Text(
          'Il redeviendra visible dans le catalogue et le POS.',
          style: TextStyle(height: 1.4),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(
              'Annuler',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          FilledButton(
            onPressed: () async {
              // Captured before the await: the card may leave the list once
              // the product lists are invalidated.
              final messenger = ScaffoldMessenger.of(context);
              Navigator.pop(ctx);
              try {
                await actions.unarchive(product.id);
                messenger.showSnackBar(
                  SnackBar(
                    content: Row(
                      children: [
                        Icon(Icons.check_circle, color: Colors.white),
                        const SizedBox(width: 8),
                        const Text('Produit désarchivé avec succès'),
                      ],
                    ),
                    backgroundColor: AppTheme.success,
                    behavior: SnackBarBehavior.floating,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                );
              } catch (e) {
                messenger.showSnackBar(
                  SnackBar(
                    content: Row(
                      children: [
                        Icon(Icons.error, color: Colors.white),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(e is ProductException
                              ? e.message
                              : appErrorMessage(e)),
                        ),
                      ],
                    ),
                    backgroundColor: AppTheme.errorColor,
                    behavior: SnackBarBehavior.floating,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                );
              }
            },
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.success,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: const Text('Désarchiver'),
          ),
        ],
      ),
    );
  }

  Widget _buildProductImage(String url, ThemeData theme) {
    if (url.startsWith('/')) {
      final file = File(url);
      if (file.existsSync()) {
        return Image.file(file, fit: BoxFit.cover);
      }
    }
    return Image.network(url, fit: BoxFit.cover,
      errorBuilder: (_, __, ___) => _buildInitials(theme),
    );
  }

  Widget _buildInitials(ThemeData theme) {
    final initials = product.name.isNotEmpty
        ? product.name.characters.first.toUpperCase()
        : '?';
    // Pastille teintée plutôt qu'un dégradé saturé — même rendu que les
    // badges d'icône des écrans déjà repensés.
    return Container(
      width: 60,
      height: 60,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        color: AppTheme.softTint(theme.brightness),
      ),
      child: Center(
        child: Text(
          initials,
          style: theme.textTheme.headlineSmall?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}
