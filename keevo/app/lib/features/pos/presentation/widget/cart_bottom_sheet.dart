import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/cart_item.dart';
import '../provider/cart_provider.dart';
import 'discount_sheet.dart';

/// CartBottomSheet — modal bottom sheet showing cart items with quantity controls.
class CartBottomSheet extends ConsumerStatefulWidget {
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
  ConsumerState<CartBottomSheet> createState() => _CartBottomSheetState();
}

class _CartBottomSheetState extends ConsumerState<CartBottomSheet> {
  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  final _sheetController = DraggableScrollableController();
  bool _wasKeyboardOpen = false;

  @override
  void dispose() {
    _sheetController.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 100;
    if (keyboardOpen && !_wasKeyboardOpen) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _sheetController.isAttached) {
          _sheetController.animateTo(
            1.0,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
          );
        }
      });
    }
    _wasKeyboardOpen = keyboardOpen;
  }

  Future<void> _handleEncaisser(BuildContext context) async {
    final draftItems =
        ref.read(cartProvider).where((i) => i.isDraft).toList();

    if (draftItems.isEmpty) {
      widget.onEncaisser();
      return;
    }

    final initialStocks = <String, int>{};
    for (final item in draftItems) {
      if (!mounted) return;
      final qty = await _showInitialStockDialog(context, item.productName);
      if (qty == null) return; // user cancelled
      initialStocks[item.productId] = qty;
    }

    if (!mounted) return;
    ref.read(draftInitialStocksProvider.notifier).state = initialStocks;
    widget.onEncaisser();
  }

  Future<int?> _showInitialStockDialog(
      BuildContext context, String productName) {
    return showDialog<int>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => _InitialStockDialog(productName: productName),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final notifier = ref.read(cartProvider.notifier);
    final subtotal = notifier.totalAmount;
    final discountAmount = notifier.discountAmount;
    final finalTotal = notifier.finalTotal;
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return DraggableScrollableSheet(
      controller: _sheetController,
      expand: false,
      initialChildSize: 0.55,
      minChildSize: 0.3,
      maxChildSize: 1.0,
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
                    onQuantityChanged: (qty) =>
                        notifier.setQuantity(item.id, qty),
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
                        ? const LinearGradient(
                            colors: [AppTheme.primary, AppTheme.primaryGradientEnd],
                          )
                        : null,
                    color: cart.isEmpty ? cs.outlineVariant : null,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: cart.isNotEmpty
                        ? [
                            BoxShadow(
                              color: AppTheme.primary.withValues(alpha: 0.3),
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
                      onTap: cart.isEmpty
                          ? null
                          : () => _handleEncaisser(context),
                      borderRadius: BorderRadius.circular(16),
                      child: Center(
                        child: Text(
                          'Encaisser',
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
  final void Function(int newQty) onQuantityChanged;

  const _CartItemTile({
    required this.item,
    required this.onIncrement,
    required this.onDecrement,
    required this.onDismissed,
    required this.onPriceChanged,
    required this.onQuantityChanged,
  });

  @override
  State<_CartItemTile> createState() => _CartItemTileState();
}

class _CartItemTileState extends State<_CartItemTile> {
  // ── Price editing ────────────────────────────────────────────────────────
  bool _isEditingPrice = false;
  late TextEditingController _priceController;
  late FocusNode _priceFocus;

  // ── Quantity editing ─────────────────────────────────────────────────────
  bool _isEditingQty = false;
  late TextEditingController _qtyController;
  late FocusNode _qtyFocus;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  void initState() {
    super.initState();
    _priceController =
        TextEditingController(text: '${widget.item.appliedUnitPrice}');
    _qtyController =
        TextEditingController(text: '${widget.item.quantity}');
    _priceFocus = FocusNode()..addListener(_onPriceFocusChange);
    _qtyFocus = FocusNode()..addListener(_onQtyFocusChange);
  }

  @override
  void didUpdateWidget(covariant _CartItemTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_isEditingPrice &&
        oldWidget.item.appliedUnitPrice != widget.item.appliedUnitPrice) {
      _priceController.text = '${widget.item.appliedUnitPrice}';
    }
    if (!_isEditingQty && oldWidget.item.quantity != widget.item.quantity) {
      _qtyController.text = '${widget.item.quantity}';
    }
  }

  @override
  void dispose() {
    _priceFocus
      ..removeListener(_onPriceFocusChange)
      ..dispose();
    _qtyFocus
      ..removeListener(_onQtyFocusChange)
      ..dispose();
    _priceController.dispose();
    _qtyController.dispose();
    super.dispose();
  }

  void _onPriceFocusChange() {
    if (!_priceFocus.hasFocus) _confirmPrice();
  }

  void _onQtyFocusChange() {
    if (!_qtyFocus.hasFocus) _confirmQty();
  }

  void _confirmPrice() {
    if (!_isEditingPrice) return;
    final parsed = int.tryParse(_priceController.text.trim());
    if (parsed != null && parsed >= 0) widget.onPriceChanged(parsed);
    setState(() => _isEditingPrice = false);
  }

  void _confirmQty() {
    if (!_isEditingQty) return;
    final parsed = int.tryParse(_qtyController.text.trim());
    if (parsed != null && parsed > 0) widget.onQuantityChanged(parsed);
    setState(() => _isEditingQty = false);
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
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Ligne 1 : nom produit + total ligne ──────────────────────
            Row(
              children: [
                Expanded(
                  child: Text(
                    item.productName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  _currencyFormat.format(item.subtotal),
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            // ── Ligne 2 : prix unitaire (éditable) + stepper quantité ────
            Row(
              children: [
                // Prix — inline editable
                _isEditingPrice
                    ? SizedBox(
                        width: 130,
                        child: TextField(
                          controller: _priceController,
                          focusNode: _priceFocus,
                          keyboardType: TextInputType.number,
                          inputFormatters: [
                            FilteringTextInputFormatter.digitsOnly,
                          ],
                          autofocus: true,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: AppTheme.primary,
                            fontWeight: FontWeight.w600,
                          ),
                          decoration: InputDecoration(
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 6),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                            suffix: const Text('FCFA',
                                style: TextStyle(fontSize: 11)),
                          ),
                          onTapOutside: (_) => _priceFocus.unfocus(),
                          onSubmitted: (_) => _confirmPrice(),
                        ),
                      )
                    : GestureDetector(
                        onTap: () {
                          _priceController.text = '${item.appliedUnitPrice}';
                          setState(() => _isEditingPrice = true);
                        },
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              _currencyFormat.format(item.appliedUnitPrice),
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: AppTheme.primary,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(width: 4),
                            Icon(Icons.edit_rounded,
                                size: 13, color: cs.onSurfaceVariant),
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
                const Spacer(),
                // Stepper quantité — inline editable
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
                          padding: const EdgeInsets.symmetric(
                              horizontal: 10, vertical: 10),
                          child: Icon(Icons.remove_rounded,
                              size: 18, color: cs.onSurfaceVariant),
                        ),
                      ),
                      _isEditingQty
                          ? SizedBox(
                              width: 52,
                              child: TextField(
                                controller: _qtyController,
                                focusNode: _qtyFocus,
                                keyboardType: TextInputType.number,
                                inputFormatters: [
                                  FilteringTextInputFormatter.digitsOnly,
                                ],
                                autofocus: true,
                                textAlign: TextAlign.center,
                                style: theme.textTheme.bodyLarge?.copyWith(
                                  fontWeight: FontWeight.w700,
                                  color: AppTheme.primary,
                                ),
                                decoration: const InputDecoration(
                                  isDense: true,
                                  contentPadding: EdgeInsets.symmetric(
                                      horizontal: 4, vertical: 6),
                                  border: InputBorder.none,
                                ),
                                onTapOutside: (_) => _qtyFocus.unfocus(),
                                onSubmitted: (_) => _confirmQty(),
                              ),
                            )
                          : GestureDetector(
                              onTap: () {
                                _qtyController.text = '${item.quantity}';
                                setState(() => _isEditingQty = true);
                              },
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 16, vertical: 10),
                                child: ConstrainedBox(
                                  constraints:
                                      const BoxConstraints(minWidth: 28),
                                  child: Text(
                                    '${item.quantity}',
                                    textAlign: TextAlign.center,
                                    style: theme.textTheme.bodyLarge?.copyWith(
                                      fontWeight: FontWeight.w700,
                                      color: AppTheme.primary,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                      InkWell(
                        onTap: widget.onIncrement,
                        borderRadius: const BorderRadius.horizontal(
                            right: Radius.circular(12)),
                        child: const Padding(
                          padding: EdgeInsets.symmetric(
                              horizontal: 10, vertical: 10),
                          child: Icon(Icons.add_rounded,
                              size: 18, color: AppTheme.primary),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Dialog for entering the initial stock quantity of a DRAFT product.
///
/// Uses a proper StatefulWidget so the TextEditingController's lifecycle is
/// tied to the widget — prevents "used after disposed" crashes that occur when
/// the dialog's close animation is still running after [showDialog] returns.
class _InitialStockDialog extends StatefulWidget {
  final String productName;
  const _InitialStockDialog({required this.productName});

  @override
  State<_InitialStockDialog> createState() => _InitialStockDialogState();
}

class _InitialStockDialogState extends State<_InitialStockDialog> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Stock initial'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Combien d\'unités de "${widget.productName}" avez-vous en stock ?',
            style: const TextStyle(fontSize: 14),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _controller,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            autofocus: true,
            decoration: const InputDecoration(
              labelText: 'Quantité en stock',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(null),
          child: const Text('Annuler'),
        ),
        ElevatedButton(
          onPressed: () {
            final qty = int.tryParse(_controller.text.trim());
            if (qty != null && qty >= 0) Navigator.of(context).pop(qty);
          },
          child: const Text('Confirmer'),
        ),
      ],
    );
  }
}
