import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/discount_strategy.dart';
import '../provider/cart_provider.dart';

/// _DiscountSheet — modal bottom sheet for applying order-level discounts.
class DiscountSheet extends ConsumerStatefulWidget {
  const DiscountSheet({super.key});

  static void show(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => const DiscountSheet(),
    );
  }

  @override
  ConsumerState<DiscountSheet> createState() => _DiscountSheetState();
}

class _DiscountSheetState extends ConsumerState<DiscountSheet> {
  bool _isPercentage = true;
  final _controller = TextEditingController();
  String? _error;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  void initState() {
    super.initState();
    final existing = ref.read(cartProvider.notifier).discountAmount;
    if (existing > 0) {
      _isPercentage = false;
      _controller.text = '$existing';
    }
  }

  int get _subtotal => ref.read(cartProvider.notifier).totalAmount;

  int _computeDiscount() {
    final value = int.tryParse(_controller.text.trim()) ?? 0;
    if (_isPercentage) {
      return PercentageDiscountStrategy(value).compute(_subtotal);
    } else {
      return FixedAmountDiscountStrategy(value).compute(_subtotal);
    }
  }

  void _apply() {
    final discount = _computeDiscount();
    final ok = ref.read(cartProvider.notifier).setDiscount(discount);
    if (!ok) {
      setState(
          () => _error = 'La réduction ne peut pas dépasser le sous-total');
      return;
    }
    Navigator.pop(context);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(cartProvider); // rebuild on cart changes
    final subtotal = _subtotal;
    final discount = _computeDiscount();
    final preview = subtotal - discount;

    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: MediaQuery.of(context).viewInsets.bottom + 16,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Réduction', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          Row(
            children: [
              ChoiceChip(
                label: const Text('% sur le total'),
                selected: _isPercentage,
                onSelected: (v) => setState(() {
                  _isPercentage = true;
                  _error = null;
                }),
              ),
              const SizedBox(width: 8),
              ChoiceChip(
                label: const Text('Montant fixe'),
                selected: !_isPercentage,
                onSelected: (v) => setState(() {
                  _isPercentage = false;
                  _error = null;
                }),
              ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _controller,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: InputDecoration(
              hintText: _isPercentage ? 'Ex: 10' : 'Ex: 1000',
              suffixText: _isPercentage ? '%' : 'FCFA',
              border: const OutlineInputBorder(),
              errorText: _error,
            ),
            onChanged: (_) => setState(() => _error = null),
          ),
          const SizedBox(height: 8),
          Text(
            'Total après réduction: ${_currencyFormat.format(preview)}',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                ),
          ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _apply,
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF3B5BDB),
              padding: const EdgeInsets.symmetric(vertical: 14),
            ),
            child: const Text('Appliquer'),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              if (ref.read(cartProvider.notifier).discountAmount > 0)
                TextButton(
                  onPressed: () {
                    ref.read(cartProvider.notifier).setDiscount(0);
                    Navigator.pop(context);
                  },
                  child: const Text('Supprimer la réduction',
                      style: TextStyle(color: Colors.red)),
                ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Annuler'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
