import '../../../../core/storage/search_fold.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../contact/domain/model/client_model.dart';
import '../../../contact/presentation/provider/contact_provider.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../provider/cart_provider.dart';
import '../provider/pos_providers.dart';
import '../provider/record_sale_notifier.dart';
import '../../../sync_indicator/presentation/widget/sync_required_modal.dart';

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
  final _montantRecuController = TextEditingController();
  int? _montantRecu;
  ClientModel? _selectedClient;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0);

  @override
  void dispose() {
    _montantRecuController.dispose();
    super.dispose();
  }

  void _goBack() {
    if (context.canPop()) {
      context.pop(true); // true = reopen cart on POS
    } else {
      context.go('/pos');
    }
  }

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final hasDraftItems = cart.any((c) => c.isDraft);
    final cartNotifier = ref.read(cartProvider.notifier);
    final discountAmount = cartNotifier.discountAmount;
    final finalTotal = cartNotifier.finalTotal;
    final saleState = ref.watch(recordSaleNotifierProvider);
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    ref.listen<RecordSaleState>(recordSaleNotifierProvider, (_, state) {
      if (state is RecordSaleSuccess) {
        context.go('/pos/success?total=${state.sale.totalAmount}&status=${state.sale.status}');
      } else if (state is RecordSaleBlockedByGate) {
        SyncRequiredModal.show(context);
      } else if (state is RecordSaleError) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(state.message == 'INSUFFICIENT_STOCK'
                ? 'Stock insuffisant pour un ou plusieurs articles'
                : 'Erreur: ${state.message}'),
            backgroundColor: AppTheme.errorColor,
          ),
        );
      }
    });

    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) {
        if (!didPop) _goBack();
      },
      child: Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded,
              color: cs.onSurface, size: 20),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: _goBack,
        ),
        title: Text(
          'Encaissement',
          style: theme.textTheme.titleLarge?.copyWith(
            fontSize: 18,
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        centerTitle: false,
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Total hero section — carte blanche + ombre douce, comme la
              // hero card du dashboard.
              Container(
                padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 20),
                decoration: AppTheme.softCardDecoration(cs, radius: 20),
                child: Column(
                  children: [
                    Text(
                      '${cart.length} article${cart.length > 1 ? 's' : ''}',
                      style: TextStyle(
                        color: cs.onSurfaceVariant,
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _currencyFormat.format(finalTotal),
                      style: TextStyle(
                        color: cs.onSurface,
                        fontSize: 32,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.5,
                      ),
                    ),
                    if (discountAmount > 0) ...[
                      const SizedBox(height: 6),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: AppTheme.softTint(theme.brightness),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Text(
                          'Réduction: −${_currencyFormat.format(discountAmount)}',
                          style: TextStyle(
                            color: cs.primary,
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // Client selector (AC5 — optional autocomplete from clients)
              _ClientAutocomplete(
                selectedClient: _selectedClient,
                onSelected: (client) => setState(() => _selectedClient = client),
                onCleared: () => setState(() => _selectedClient = null),
              ),
              const SizedBox(height: 16),

              // Payment mode selection
              Text('Mode de paiement',
                  style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      )),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: _PaymentModeCard(
                      icon: Icons.payments_outlined,
                      label: 'Espèces',
                      selected: _selectedMode == PaymentModeEnum.cash,
                      onTap: () =>
                          setState(() => _selectedMode = PaymentModeEnum.cash),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _PaymentModeCard(
                      icon: Icons.phone_android_rounded,
                      label: 'Mobile Money',
                      selected: _selectedMode == PaymentModeEnum.mobileMoney,
                      onTap: () => setState(
                          () => _selectedMode = PaymentModeEnum.mobileMoney),
                    ),
                  ),
                ],
              ),

              // Espèces: Montant reçu + monnaie rendue (AC4)
              if (_selectedMode == PaymentModeEnum.cash) ...[
                const SizedBox(height: 16),
                TextField(
                  controller: _montantRecuController,
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                  ],
                  decoration: InputDecoration(
                    labelText: 'Montant reçu (optionnel)',
                    hintText: 'Ex: $finalTotal',
                    suffixText: 'FCFA',
                    helperText: _montantRecu != null && _montantRecu! < finalTotal
                        ? 'Montant inférieur au total (${_currencyFormat.format(finalTotal)})'
                        : null,
                  ),
                  onChanged: (v) {
                    setState(() => _montantRecu = int.tryParse(v));
                  },
                ),
                if (_montantRecu != null && _montantRecu! >= finalTotal) ...[  
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    decoration: BoxDecoration(
                      color: AppTheme.success.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.currency_exchange_rounded,
                            color: _changeColor(cs), size: 20),
                        const SizedBox(width: 8),
                        Text(
                          'Monnaie à rendre: ${_currencyFormat.format(_montantRecu! - finalTotal)}',
                          style: TextStyle(
                            fontWeight: FontWeight.w600,
                            color: _changeColor(cs),
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],

              // Mobile money ref field
              if (_selectedMode == PaymentModeEnum.mobileMoney) ...[
                const SizedBox(height: 16),
                TextField(
                  decoration: const InputDecoration(
                    labelText: 'Référence Mobile Money',
                    hintText: 'Ex: REF-MTN-12345',
                  ),
                  onChanged: (v) => _mobileMoneyRef = v,
                ),
              ],

              const Spacer(),

              // Submit button
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: SizedBox(
                  height: 56,
                  // Couleur pleine plutôt qu'un dégradé ; indigo de marque
                  // dans les deux thèmes pour garder le contraste du libellé.
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: _selectedMode == null ||
                              _isSubmitting ||
                              saleState is RecordSaleLoading
                          ? cs.outlineVariant
                          : AppTheme.primary,
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: _selectedMode != null
                          ? [
                              BoxShadow(
                                color: AppTheme.primary.withValues(alpha: 0.25),
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
                        onTap: _selectedMode == null ||
                                _isSubmitting ||
                                saleState is RecordSaleLoading
                            ? null
                            : _submit,
                        borderRadius: BorderRadius.circular(16),
                        child: Center(
                          child: saleState is RecordSaleLoading
                              ? const SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(
                                      strokeWidth: 2.5, color: AppTheme.onBrand),
                                )
                              : Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    if (hasDraftItems) ...[
                                      const Icon(Icons.edit_note_rounded,
                                          color: AppTheme.onBrand, size: 22),
                                      const SizedBox(width: 8),
                                    ],
                                    Text(
                                      hasDraftItems
                                          ? 'Vente brouillon'
                                          : 'Valider la vente',
                                      style: const TextStyle(
                                        color: AppTheme.onBrand,
                                        fontSize: 17,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                  ],
                                ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
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
          clientId: _selectedClient?.id,
          // A reference typed before switching to Espèces must not leak.
          mobileRef: _selectedMode == PaymentModeEnum.mobileMoney
              ? _mobileMoneyRef
              : null,
        );

    if (mounted) setState(() => _isSubmitting = false);
  }

  /// Vert foncé lisible sur la pastille pâle en clair ; vert vif en sombre.
  Color _changeColor(ColorScheme cs) => cs.successStrong;
}

class _PaymentModeCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _PaymentModeCard({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    // Sélection : teinte pâle + bordure primaire, sans ombre colorée (même
    // langage que les tuiles de sélection de l'onboarding).
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOutCubic,
        padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 12),
        decoration: BoxDecoration(
          color: selected ? AppTheme.softTint(theme.brightness) : cs.surface,
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          border: Border.all(
            color: selected ? cs.primary : cs.outlineVariant.withAlpha(90),
            width: selected ? 2 : 1,
          ),
        ),
        child: Column(
          children: [
            Icon(
              icon,
              size: 28,
              color: selected ? cs.primary : cs.onSurfaceVariant,
            ),
            const SizedBox(height: 8),
            Text(
              label,
              style: TextStyle(
                fontSize: 14,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                color: selected ? cs.primary : cs.onSurface,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Client autocomplete field (AC5 — optional, from local clients Drift table).
class _ClientAutocomplete extends ConsumerWidget {
  final ClientModel? selectedClient;
  final ValueChanged<ClientModel> onSelected;
  final VoidCallback onCleared;

  const _ClientAutocomplete({
    required this.selectedClient,
    required this.onSelected,
    required this.onCleared,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final clientsAsync = ref.watch(clientListNotifierProvider);

    return clientsAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
      data: (clients) {
        if (clients.isEmpty) return const SizedBox.shrink();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (selectedClient != null)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                decoration: BoxDecoration(
                  color: cs.surface,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(
                    color: cs.primary.withValues(alpha: 0.35),
                  ),
                ),
                child: Row(
                  children: [
                    const SoftIconBadge(icon: Icons.person_rounded, size: 36),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            selectedClient!.name,
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 14,
                            ),
                          ),
                          Text(
                            selectedClient!.phone,
                            style: TextStyle(
                              color: cs.onSurfaceVariant,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      icon: Icon(Icons.close_rounded,
                          size: 18, color: cs.onSurfaceVariant),
                      onPressed: onCleared,
                    ),
                  ],
                ),
              )
            else
              Autocomplete<ClientModel>(
                displayStringForOption: (c) => '${c.name} — ${c.phone}',
                optionsBuilder: (textEditingValue) {
                  if (textEditingValue.text.isEmpty) return clients;
                  final q = foldForSearch(textEditingValue.text);
                  return clients.where((c) =>
                      foldForSearch(c.name).contains(q) ||
                      foldForSearch(c.phone).contains(q));
                },
                onSelected: onSelected,
                fieldViewBuilder:
                    (context, controller, focusNode, onFieldSubmitted) {
                  return TextField(
                    controller: controller,
                    focusNode: focusNode,
                    decoration: const InputDecoration(
                      labelText: 'Client (optionnel)',
                      hintText: 'Rechercher un client…',
                      prefixIcon: Icon(Icons.person_outline_rounded),
                    ),
                  );
                },
              ),
          ],
        );
      },
    );
  }
}
