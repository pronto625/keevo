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

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.5,
      minChildSize: 0.3,
      maxChildSize: 0.85,
      builder: (context, scrollController) {
        return Column(
          children: [
            // Handle bar
            Container(
              margin: const EdgeInsets.symmetric(vertical: 8),
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.shade300,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Panier (${cart.length})',
                      style: Theme.of(context).textTheme.titleMedium),
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
                          child: const Text('Vider'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const Divider(),
            Expanded(
              child: ListView.builder(
                controller: scrollController,
                itemCount: cart.length,
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
            const Divider(),
            // Total breakdown
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Column(
                children: [
                  if (discountAmount > 0) ...[
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Sous-total',
                            style: Theme.of(context).textTheme.bodyMedium),
                        Text(_currencyFormat.format(subtotal),
                            style: Theme.of(context).textTheme.bodyMedium),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Réduction',
                            style: TextStyle(color: Colors.red.shade700)),
                        Text('−${_currencyFormat.format(discountAmount)}',
                            style: TextStyle(color: Colors.red.shade700)),
                      ],
                    ),
                    const Divider(height: 12),
                  ],
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(discountAmount > 0 ? 'Total à payer' : 'Total',
                          style: Theme.of(context).textTheme.titleMedium),
                      Text(
                        _currencyFormat.format(finalTotal),
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.bold,
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
                child: FilledButton(
                  onPressed: cart.isEmpty ? null : onEncaisser,
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFF3B5BDB),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                  child: const Text('Encaisser'),
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

    return Dismissible(
      key: ValueKey(item.id),
      direction: DismissDirection.endToStart,
      onDismissed: (_) => widget.onDismissed(),
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 16),
        color: Colors.red,
        child: const Icon(Icons.delete, color: Colors.white),
      ),
      child: ListTile(
        title: Text(item.productName,
            maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: _isEditing
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
                      decoration: const InputDecoration(
                        isDense: true,
                        contentPadding:
                            EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                        border: OutlineInputBorder(),
                      ),
                      onSubmitted: (_) => _confirmPrice(),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.check, size: 18),
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
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(_currencyFormat.format(item.appliedUnitPrice)),
                    if (item.isPriceOverridden)
                      Text('Prix modifié',
                          style: TextStyle(
                              color: Colors.red.shade700, fontSize: 11)),
                  ],
                ),
              ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.remove_circle_outline),
              onPressed: widget.onDecrement,
              visualDensity: VisualDensity.compact,
            ),
            Text('${item.quantity}',
                style: Theme.of(context).textTheme.bodyLarge),
            IconButton(
              icon: const Icon(Icons.add_circle_outline),
              onPressed: widget.onIncrement,
              visualDensity: VisualDensity.compact,
            ),
            const SizedBox(width: 8),
            Text(
              _currencyFormat.format(item.subtotal),
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}
