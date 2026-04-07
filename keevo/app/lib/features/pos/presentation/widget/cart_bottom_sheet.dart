import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/cart_item.dart';
import '../provider/cart_provider.dart';
import 'discount_sheet.dart';

/// CartBottomSheet — modal bottom sheet showing cart items with quantity controls.
class CartBottomSheet extends ConsumerWidget {
  final VoidCallback onEncaisser;

  const CartBottomSheet({super.key, required this.onEncaisser});

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  static void show(BuildContext context, {required VoidCallback onEncaisser}) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => CartBottomSheet(onEncaisser: onEncaisser),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartProvider);
    final notifier = ref.read(cartProvider.notifier);
    final subtotal = notifier.totalAmount;
    final discountAmount = notifier.discountAmount;
    final finalTotal = notifier.finalTotal;
    final hasDrafts = notifier.hasDraftProducts;
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.55,
      minChildSize: 0.3,
      maxChildSize: 0.85,
      builder: (context, scrollController) {
        return Column(
          children: [
            // Handle bar
            Container(
              margin: const EdgeInsets.symmetric(vertical: 10),
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: cs.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Text('Panier',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                          )),
                      const SizedBox(width: 8),
                      if (cart.isNotEmpty)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppTheme.primary.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Text(
                            '${cart.length}',
                            style: const TextStyle(
                              color: AppTheme.primary,
                              fontWeight: FontWeight.w700,
                              fontSize: 13,
                            ),
                          ),
                        ),
                    ],
                  ),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (cart.isNotEmpty)
                        TextButton.icon(
                          icon: const Icon(Icons.discount_outlined, size: 18),
                          label: const Text('Réduction'),
                          onPressed: () => DiscountSheet.show(context),
                        ),
                      if (cart.isNotEmpty)
                        TextButton(
                          onPressed: () => notifier.clearCart(),
                          style: TextButton.styleFrom(
                            foregroundColor: AppTheme.errorColor,
                          ),
                          child: const Text('Vider'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            Divider(color: cs.outlineVariant),
            if (hasDrafts)
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: AppTheme.warning,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: AppTheme.warning),
                ),
                child: Row(
                  children: [
                    Icon(Icons.info_outline, color: AppTheme.warning, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Cette vente contient des produits en brouillon. Elle sera validée quand l\'admin les confirmera.',
                        style: TextStyle(
                          color: AppTheme.warning,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            Expanded(
              child: ListView.separated(
                controller: scrollController,
                itemCount: cart.length,
                separatorBuilder: (_, __) => Divider(
                  height: 1,
                  indent: 16,
                  endIndent: 16,
                  color: cs.surfaceContainerHighest,
                ),
                itemBuilder: (context, index) {
                  final item = cart[index];
                  return _CartItemTile(
                    item: item,
                    onIncrement: () =>
                        notifier.updateQuantity(item.id, item.quantity + 1),
                    onDecrement: () =>
                        notifier.updateQuantity(item.id, item.quantity - 1),
                    onDismissed: () => notifier.removeItem(item.id),
                    onPriceChanged: (newPrice) =>
                        notifier.updatePrice(item.id, newPrice),
                  );
                },
              ),
            ),
            Divider(color: cs.outlineVariant),
            // Total breakdown
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Column(
                children: [
                  if (discountAmount > 0) ...[
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Sous-total',
                            style: theme.textTheme.bodyMedium),
                        Text(_currencyFormat.format(subtotal),
                            style: theme.textTheme.bodyMedium),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Réduction',
                            style: TextStyle(color: AppTheme.errorColor)),
                        Text('−${_currencyFormat.format(discountAmount)}',
                            style: TextStyle(color: AppTheme.errorColor)),
                      ],
                    ),
                    const Divider(height: 12),
                  ],
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(discountAmount > 0 ? 'Total à payer' : 'Total',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                          )),
                      Text(
                        _currencyFormat.format(finalTotal),
                        style: theme.textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w800,
                          color: AppTheme.primary,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: SizedBox(
                width: double.infinity,
                height: 52,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: cart.isNotEmpty
                        ? (hasDrafts
                            ? LinearGradient(
                                colors: [AppTheme.warning, AppTheme.warning],
                              )
                            : const LinearGradient(
                                colors: [AppTheme.primary, AppTheme.primaryGradientEnd],
                              ))
                        : null,
                    color: cart.isEmpty ? cs.outlineVariant : null,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: cart.isNotEmpty
                        ? [
                            BoxShadow(
                              color: hasDrafts
                                  ? AppTheme.warning.withValues(alpha: 0.3)
                                  : AppTheme.primary.withValues(alpha: 0.3),
                              blurRadius: 12,
                              offset: const Offset(0, 4),
                            ),
                          ]
                        : null,
                  ),
                  child: Material(
                    color: Colors.transparent,
                    borderRadius: BorderRadius.circular(16),
                    child: InkWell(
                      onTap: cart.isEmpty ? null : onEncaisser,
                      borderRadius: BorderRadius.circular(16),
                      child: Center(
                        child: Text(
                          hasDrafts ? '🔶 Vente brouillon' : 'Encaisser',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _CartItemTile extends StatefulWidget {
  final CartItem item;
  final VoidCallback onIncrement;
  final VoidCallback onDecrement;
  final VoidCallback onDismissed;
  final void Function(int newPrice) onPriceChanged;

  const _CartItemTile({
    required this.item,
    required this.onIncrement,
    required this.onDecrement,
    required this.onDismissed,
    required this.onPriceChanged,
  });

  @override
  State<_CartItemTile> createState() => _CartItemTileState();
}

class _CartItemTileState extends State<_CartItemTile> {
  bool _isEditing = false;
  late TextEditingController _controller;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(
        text: '${widget.item.appliedUnitPrice}');
  }

  @override
  void didUpdateWidget(covariant _CartItemTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_isEditing &&
        oldWidget.item.appliedUnitPrice != widget.item.appliedUnitPrice) {
      _controller.text = '${widget.item.appliedUnitPrice}';
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _confirmPrice() {
    final text = _controller.text.trim();
    final parsed = int.tryParse(text);
    if (parsed != null && parsed >= 0) {
      widget.onPriceChanged(parsed);
    }
    setState(() => _isEditing = false);
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Dismissible(
      key: ValueKey(item.id),
      direction: DismissDirection.endToStart,
      onDismissed: (_) => widget.onDismissed(),
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        decoration: BoxDecoration(
          color: AppTheme.errorColor.withValues(alpha: 0.1),
        ),
        child: const Icon(Icons.delete_outline_rounded,
            color: AppTheme.errorColor, size: 24),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            // Product info
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.productName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 2),
                  _isEditing
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            SizedBox(
                              width: 80,
                              child: TextField(
                                controller: _controller,
                                keyboardType: TextInputType.number,
                                inputFormatters: [
                                  FilteringTextInputFormatter.digitsOnly,
                                ],
                                autofocus: true,
                                style: const TextStyle(fontSize: 14),
                                decoration: InputDecoration(
                                  isDense: true,
                                  contentPadding: const EdgeInsets.symmetric(
                                      horizontal: 8, vertical: 6),
                                  border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                ),
                                onSubmitted: (_) => _confirmPrice(),
                              ),
                            ),
                            const SizedBox(width: 4),
                            IconButton(
                              icon: const Icon(Icons.check_rounded, size: 18,
                                  color: AppTheme.success),
                              onPressed: _confirmPrice,
                              visualDensity: VisualDensity.compact,
                            ),
                          ],
                        )
                      : GestureDetector(
                          onTap: () => setState(() {
                            _controller.text = '${item.appliedUnitPrice}';
                            _isEditing = true;
                          }),
                          child: Row(
                            children: [
                              Text(
                                _currencyFormat.format(item.appliedUnitPrice),
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: cs.onSurfaceVariant,
                                ),
                              ),
                              if (item.isPriceOverridden)
                                Padding(
                                  padding: const EdgeInsets.only(left: 6),
                                  child: Container(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: 6, vertical: 1),
                                    decoration: BoxDecoration(
                                      color: AppTheme.secondary
                                          .withValues(alpha: 0.1),
                                      borderRadius: BorderRadius.circular(6),
                                    ),
                                    child: const Text(
                                      'modifié',
                                      style: TextStyle(
                                        color: AppTheme.secondary,
                                        fontSize: 10,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                ],
              ),
            ),
            // Quantity controls
            Container(
              decoration: BoxDecoration(
                color: cs.surfaceContainerLowest,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: cs.outlineVariant),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  InkWell(
                    onTap: widget.onDecrement,
                    borderRadius: const BorderRadius.horizontal(
                        left: Radius.circular(12)),
                    child: Padding(
                      padding: const EdgeInsets.all(8),
                      child: Icon(Icons.remove_rounded,
                          size: 18, color: cs.onSurfaceVariant),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Text(
                      '${item.quantity}',
                      style: theme.textTheme.bodyLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  InkWell(
                    onTap: widget.onIncrement,
                    borderRadius: const BorderRadius.horizontal(
                        right: Radius.circular(12)),
                    child: const Padding(
                      padding: EdgeInsets.all(8),
                      child: Icon(Icons.add_rounded,
                          size: 18, color: AppTheme.primary),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            // Subtotal
            Text(
              _currencyFormat.format(item.subtotal),
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
