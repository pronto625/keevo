import '../../../../core/theme/app_theme.dart';
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
      NumberFormat.currency(locale: 'fr_CM', symbol: 'XAF', decimalDigits: 0);

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

  int get _enteredValue => int.tryParse(_controller.text.trim()) ?? 0;

  /// Inline error for an out-of-range entry. The strategies clamp silently
  /// (150 % would bring the total to 0), so the sheet refuses it up front;
  /// 100 % / the exact subtotal stays allowed, as on the backend.
  String? get _validationError {
    final value = _enteredValue;
    if (_isPercentage && value > 100) {
      return 'Le pourcentage ne peut pas dépasser 100 %';
    }
    if (!_isPercentage && value > _subtotal) {
      return 'La réduction ne peut pas dépasser le sous-total';
    }
    return null;
  }

  int _computeDiscount() {
    final value = _enteredValue;
    if (_isPercentage) {
      return PercentageDiscountStrategy(value).compute(_subtotal);
    } else {
      return FixedAmountDiscountStrategy(value).compute(_subtotal);
    }
  }

  void _apply() {
    if (_validationError != null) return;
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
    final invalid = _validationError;

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
          Text('Réduction',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  )),
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
              suffixText: _isPercentage ? '%' : 'XAF',
              errorText: invalid ?? _error,
              errorMaxLines: 2,
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
            onPressed: invalid == null ? _apply : null,
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.primary,
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
                      style: TextStyle(color: AppTheme.errorColor)),
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
