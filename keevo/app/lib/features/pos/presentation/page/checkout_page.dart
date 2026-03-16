import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../domain/model/payment_mode_enum.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../provider/cart_provider.dart';
import '../provider/pos_providers.dart';
import '../provider/record_sale_notifier.dart';

/// CheckoutPage — payment mode selection + sale confirmation.
class CheckoutPage extends ConsumerStatefulWidget {
  const CheckoutPage({super.key});

  @override
  ConsumerState<CheckoutPage> createState() => _CheckoutPageState();
}

class _CheckoutPageState extends ConsumerState<CheckoutPage> {
  PaymentModeEnum? _selectedMode;
  String? _mobileMoneyRef;
  bool _isSubmitting = false;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final cartNotifier = ref.read(cartProvider.notifier);
    final total = cartNotifier.totalAmount;
    final saleState = ref.watch(recordSaleNotifierProvider);

    ref.listen<RecordSaleState>(recordSaleNotifierProvider, (_, state) {
      if (state is RecordSaleSuccess) {
        context.go('/pos/success?total=${state.sale.totalAmount}');
      } else if (state is RecordSaleError) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(state.message == 'INSUFFICIENT_STOCK'
                ? 'Stock insuffisant pour un ou plusieurs articles'
                : 'Erreur: ${state.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    });

    return Scaffold(
      appBar: AppBar(title: const Text('Encaissement')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Cart summary
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('${cart.length} article${cart.length > 1 ? 's' : ''}',
                        style: Theme.of(context).textTheme.titleMedium),
                    Text(
                      _currencyFormat.format(total),
                      style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // Payment mode selection
            Text('Mode de paiement',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _PaymentModeCard(
                    label: '💵 Espèces',
                    selected: _selectedMode == PaymentModeEnum.cash,
                    onTap: () =>
                        setState(() => _selectedMode = PaymentModeEnum.cash),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _PaymentModeCard(
                    label: '📱 Mobile Money',
                    selected: _selectedMode == PaymentModeEnum.mobileMoney,
                    onTap: () => setState(
                        () => _selectedMode = PaymentModeEnum.mobileMoney),
                  ),
                ),
              ],
            ),

            // Mobile money ref field
            if (_selectedMode == PaymentModeEnum.mobileMoney) ...[
              const SizedBox(height: 16),
              TextField(
                decoration: const InputDecoration(
                  labelText: 'Référence Mobile Money',
                  hintText: 'Ex: REF-MTN-12345',
                  border: OutlineInputBorder(),
                ),
                onChanged: (v) => _mobileMoneyRef = v,
              ),
            ],

            const Spacer(),

            // Submit button
            SizedBox(
              height: 52,
              child: FilledButton(
                onPressed: _selectedMode == null ||
                        _isSubmitting ||
                        saleState is RecordSaleLoading
                    ? null
                    : _submit,
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFF3B5BDB),
                ),
                child: saleState is RecordSaleLoading
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Colors.white),
                      )
                    : const Text('Valider la vente',
                        style: TextStyle(fontSize: 16)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (_isSubmitting) return;
    setState(() => _isSubmitting = true);

    final storeId = ref.read(activeStoreIdProvider);
    final employeeId = await ref.read(activeEmployeeIdProvider.future);

    if (storeId == null || employeeId == null) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Identifiant boutique/employé manquant')),
        );
        setState(() => _isSubmitting = false);
      }
      return;
    }

    final cart = ref.read(cartProvider);
    await ref.read(recordSaleNotifierProvider.notifier).submit(
          cart: cart,
          mode: _selectedMode!,
          storeId: storeId,
          employeeId: employeeId,
          mobileRef: _mobileMoneyRef,
        );

    if (mounted) setState(() => _isSubmitting = false);
  }
}

class _PaymentModeCard extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _PaymentModeCard({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return FilledButton.tonal(
      onPressed: onTap,
      style: FilledButton.styleFrom(
        backgroundColor: selected
            ? const Color(0xFF3B5BDB).withValues(alpha: 0.15)
            : null,
        side: selected
            ? const BorderSide(color: Color(0xFF3B5BDB), width: 2)
            : null,
        padding: const EdgeInsets.symmetric(vertical: 20),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      child: Text(label, style: const TextStyle(fontSize: 16)),
    );
  }
}
