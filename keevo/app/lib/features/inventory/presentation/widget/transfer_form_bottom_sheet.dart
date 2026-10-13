import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../catalog/domain/model/product_model.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../../catalog/presentation/provider/stock_provider.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../../sync_indicator/presentation/widget/sync_required_modal.dart';
import '../../../../core/sync/sync_gate_provider.dart';
import '../provider/global_stock_provider.dart';
import '../provider/stock_transfer_provider.dart';

/// Shows the transfer form as a modal bottom sheet.
///
/// When [destinationStores] is empty (e.g. from the history FAB), the widget
/// self-loads stores from [storeListNotifierProvider].
/// Story v1s-12-11 (FR36): in self-load mode an EMPLOYEE's source store is
/// locked to their own store ([activeStoreIdProvider], kept equal to the JWT
/// store by SessionClaimsApplier). OWNER may pick any source store. The
/// backend enforces the same rule (403 / sync REJECTED).
/// Returns the id of the created transfer on success, or null if dismissed.
///
/// Story 3.3.
Future<String?> showTransferFormBottomSheet({
  required BuildContext context,
  required String sourceStoreId,
  required String sourceStoreName,
  required String productId,
  required String productName,
  required List<StoreModel> destinationStores,
}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (_) => _TransferFormBottomSheet(
      sourceStoreId: sourceStoreId,
      sourceStoreName: sourceStoreName,
      productId: productId,
      productName: productName,
      destinationStores: destinationStores,
    ),
  );
}

class _TransferFormBottomSheet extends ConsumerStatefulWidget {
  final String sourceStoreId;
  final String sourceStoreName;
  final String productId;
  final String productName;
  final List<StoreModel> destinationStores;

  const _TransferFormBottomSheet({
    required this.sourceStoreId,
    required this.sourceStoreName,
    required this.productId,
    required this.productName,
    required this.destinationStores,
  });

  @override
  ConsumerState<_TransferFormBottomSheet> createState() =>
      _TransferFormBottomSheetState();
}

class _TransferFormBottomSheetState
    extends ConsumerState<_TransferFormBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  final _quantityController = TextEditingController(text: '1');

  StoreModel? _selectedSource;
  StoreModel? _selectedDestination;
  ProductModel? _selectedProduct;
  bool _isSubmitting = false;
  String? _quantityError;
  String? _errorMessage;

  // True when the form needs to self-load data (called from FAB with empty params)
  bool get _selfLoadMode => widget.destinationStores.isEmpty;

  String get _effectiveSourceId =>
      _selfLoadMode ? (_selectedSource?.id ?? '') : widget.sourceStoreId;

  String get _effectiveProductId =>
      _selfLoadMode ? (_selectedProduct?.id ?? '') : widget.productId;

  String get _effectiveProductName =>
      _selfLoadMode ? (_selectedProduct?.name ?? '') : widget.productName;

  @override
  void dispose() {
    _quantityController.dispose();
    super.dispose();
  }

  /// AC3 — Extract a user-friendly message from a DioException response.
  static String _dioErrorMessage(DioException e) {
    final body = e.response?.data;
    if (body is Map) {
      final domainCode = body['domainCode'] as String?;
      if (domainCode == 'INSUFFICIENT_STOCK') {
        final details = body['details'] as Map?;
        final available = details?['available'] ?? 0;
        return 'Stock insuffisant — disponible : $available unité(s)';
      }
    }
    // v1s-16-20: never the raw server text, status code or ids.
    return appErrorMessage(e);
  }

  Future<void> _submit() async {
    setState(() {
      _quantityError = null;
      _errorMessage = null;
    });
    if (!_formKey.currentState!.validate()) return;
    if (_selectedDestination == null) return;
    if (_selfLoadMode && _selectedSource == null) return;
    if (_selfLoadMode && _selectedProduct == null) return;

    // Store context-dependent refs BEFORE the async gap.
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);

    setState(() => _isSubmitting = true);
    try {
      // Gate check — inline since WidgetRef != Ref
      if (ref.read(syncGateStateProvider).isWriteBlocked) {
        setState(() => _isSubmitting = false);
        if (mounted) SyncRequiredModal.show(context);
        return;
      }
      // Call repository directly — avoids "Bad state: Future already completed"
      // that occurs when executeTransferNotifierProvider (AutoDispose) gets
      // disposed mid-await after ref.invalidate() triggers a rebuild.
      final transfer = await ref
          .read(stockTransferRepositoryProvider)
          .executeTransfer(
            sourceStoreId: _effectiveSourceId,
            destinationStoreId: _selectedDestination!.id,
            productId: _effectiveProductId,
            quantity: int.parse(_quantityController.text.trim()),
          );

      ref.invalidate(transferHistoryProvider);
      ref.invalidate(globalStockOverviewProvider);
      ref.invalidate(storeStockDetailProvider);  // all store instances
      ref.invalidate(productListProvider);
      ref.invalidate(productListForPickerProvider);
      ref.invalidate(stockNotifierProvider);     // all product instances

      if (mounted) {
        navigator.pop(transfer.id);
        messenger.showSnackBar(
          SnackBar(
            content: Row(children: [
              const Icon(Icons.check_circle_outline,
                  color: AppTheme.onBrand, size: 18),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  '${transfer.quantity} × $_effectiveProductName → ${_selectedDestination!.name}',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ]),
            backgroundColor: AppTheme.successStrong,
            behavior: SnackBarBehavior.floating,
            duration: const Duration(seconds: 4),
          ),
        );
      }
    } on DioException catch (e) {
      if (!mounted) return;
      final msg = _dioErrorMessage(e);
      // INSUFFICIENT_STOCK: show inline on quantity field; others: error banner.
      if (msg.startsWith('Stock insuffisant')) {
        setState(() => _quantityError = msg);
      } else {
        setState(() => _errorMessage = msg);
      }
    } catch (e) {
      if (mounted) setState(() => _errorMessage = appErrorMessage(e));
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  void _increment() {
    final v = int.tryParse(_quantityController.text) ?? 0;
    _quantityController.text = '${v + 1}';
  }

  void _decrement() {
    final v = int.tryParse(_quantityController.text) ?? 0;
    if (v > 1) _quantityController.text = '${v - 1}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    // Only watch store/product providers in self-load mode (FAB path).
    // Tests pass destinationStores, so this branch is never reached in tests
    // and storeListNotifierProvider is not watched (no mock needed).
    List<StoreModel> allStores;
    List<StoreModel> sourceOptions = const [];
    bool sourceLocked = false;
    AsyncValue<List<ProductModel>>? productsAsync;
    if (_selfLoadMode) {
      final storesAsync = ref.watch(storeListNotifierProvider);
      allStores = storesAsync.value ?? [];
      productsAsync = ref.watch(productListForPickerProvider);
      // v1s-12-11 — EMPLOYEE transfers only out of their own store.
      sourceLocked = ref.watch(currentUserRoleProvider) == 'EMPLOYEE';
      if (sourceLocked) {
        final ownStoreId = ref.watch(activeStoreIdProvider);
        sourceOptions = allStores.where((s) => s.id == ownStoreId).toList();
        // Preselect (or clear, if their store is not in the list → no submit).
        _selectedSource = sourceOptions.isEmpty ? null : sourceOptions.first;
      } else {
        sourceOptions = allStores;
      }
    } else {
      allStores = widget.destinationStores;
    }

    final destinationStores =
        allStores.where((s) => s.id != _effectiveSourceId).toList();

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Header — fond neutre, badge rond plutôt que bandeau dégradé ──
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 12, 16),
            child: Row(
              children: [
                const SoftIconBadge(icon: Icons.swap_horiz_rounded),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Nouveau transfert',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                          color: cs.onSurface,
                        ),
                      ),
                      if (!_selfLoadMode && widget.productName.isNotEmpty)
                        Text(
                          widget.productName,
                          style: theme.textTheme.bodySmall
                              ?.copyWith(color: cs.onSurfaceVariant),
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                  ),
                ),
                IconButton(
                  icon: Icon(Icons.close, color: cs.onSurfaceVariant),
                  onPressed: () => Navigator.of(context).pop(),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ),

          // ── Form body ────────────────────────────────────────────────────
          Flexible(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // ── Product picker (self-load mode only) ─────────────
                    if (_selfLoadMode && productsAsync != null) ...[
                      productsAsync.when(
                        loading: () => LinearProgressIndicator(
                            color: cs.primary, backgroundColor: cs.primaryContainer),
                        error: (e, _) => AppErrorInline(error: e),
                        data: (products) =>
                            DropdownButtonFormField<ProductModel>(
                          value: _selectedProduct,
                          decoration: InputDecoration(
                            labelText: 'Produit',
                            prefixIcon: Icon(Icons.inventory_2_outlined,
                                color: cs.primary),
                          ),
                          hint: const Text('Sélectionner un produit'),
                          items: products
                              .map((p) => DropdownMenuItem(
                                    value: p,
                                    child: Text(p.name,
                                        overflow: TextOverflow.ellipsis),
                                  ))
                              .toList(),
                          onChanged: (p) =>
                              setState(() => _selectedProduct = p),
                          validator: (v) =>
                              v == null ? 'Sélectionner un produit' : null,
                        ),
                      ),
                      const SizedBox(height: 14),
                    ],

                    // ── Source store info or picker ───────────────────────
                    if (!_selfLoadMode) ...[
                      _InfoRow(
                        icon: Icons.store_rounded,
                        label: 'De',
                        value: widget.sourceStoreName,
                        color: AppTheme.softTint(theme.brightness),
                        iconColor: cs.primary,
                      ),
                      const SizedBox(height: 14),
                    ] else ...[
                      if (allStores.isEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 4),
                          child: LinearProgressIndicator(
                              color: cs.primary,
                              backgroundColor: cs.primaryContainer),
                        )
                      else
                        DropdownButtonFormField<StoreModel>(
                          key: const Key('transfer_source_dropdown'),
                          value: _selectedSource,
                          decoration: InputDecoration(
                            labelText: 'Boutique source',
                            prefixIcon: Icon(Icons.store_rounded,
                                color: cs.primary),
                          ),
                          hint: const Text('D\'où part le stock'),
                          // EMPLOYEE whose own store is not loaded: say why
                          // the (disabled, empty) source cannot be chosen.
                          disabledHint: sourceLocked && sourceOptions.isEmpty
                              ? const Text('Votre boutique est introuvable')
                              : null,
                          items: sourceOptions
                              .map((s) => DropdownMenuItem(
                                    value: s,
                                    child: Text(s.name),
                                  ))
                              .toList(),
                          // EMPLOYEE: locked to their own store (disabled).
                          onChanged: sourceLocked
                              ? null
                              : (s) => setState(() {
                                    _selectedSource = s;
                                    _selectedDestination = null;
                                  }),
                          validator: (v) => v == null
                              ? 'Sélectionner une boutique source'
                              : null,
                        ),
                      const SizedBox(height: 14),
                    ],

                    // ── Destination store dropdown ─────────────────────────
                    DropdownButtonFormField<StoreModel>(
                      key: const Key('transfer_destination_dropdown'),
                      value: _selectedDestination,
                      decoration: InputDecoration(
                        labelText: 'Boutique de destination',
                        prefixIcon: Icon(Icons.store_mall_directory_rounded,
                            color: cs.primary),
                      ),
                      hint: const Text('Vers quelle boutique'),
                      items: destinationStores
                          .map((s) => DropdownMenuItem(
                                value: s,
                                child: Text(s.name),
                              ))
                          .toList(),
                      onChanged: (s) =>
                          setState(() => _selectedDestination = s),
                      validator: (v) =>
                          v == null ? 'Sélectionner une destination' : null,
                    ),
                    const SizedBox(height: 18),

                    // ── Quantity stepper ──────────────────────────────────
                    Text('Quantité',
                        style: theme.textTheme.labelMedium
                            ?.copyWith(color: cs.onSurfaceVariant)),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        _StepperBtn(icon: Icons.remove, onTap: _decrement),
                        const SizedBox(width: 10),
                        Expanded(
                          child: TextFormField(
                            key: const Key('transfer_quantity_field'),
                            controller: _quantityController,
                            keyboardType: TextInputType.number,
                            textAlign: TextAlign.center,
                            style: theme.textTheme.titleLarge
                                ?.copyWith(fontWeight: FontWeight.w700),
                            decoration: InputDecoration(
                              errorText: _quantityError,
                            ),
                            validator: (v) {
                              if (v == null || v.trim().isEmpty) {
                                return 'Requis';
                              }
                              final qty = int.tryParse(v.trim());
                              if (qty == null || qty <= 0) {
                                return 'Quantité invalide';
                              }
                              return null;
                            },
                          ),
                        ),
                        const SizedBox(width: 10),
                        _StepperBtn(icon: Icons.add, onTap: _increment),
                      ],
                    ),
                    const SizedBox(height: 26),

                    // ── Error banner ──────────────────────────────────────
                    if (_errorMessage != null) ...[
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 14, vertical: 11),
                        decoration: BoxDecoration(
                          color: cs.errorContainer,
                          borderRadius:
                              BorderRadius.circular(AppTheme.radiusM),
                        ),
                        child: Row(
                          children: [
                            Icon(Icons.error_outline,
                                color: cs.onErrorContainer, size: 18),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                _errorMessage!,
                                style: theme.textTheme.bodySmall?.copyWith(
                                    color: cs.onErrorContainer),
                              ),
                            ),
                            IconButton(
                              icon: Icon(Icons.close,
                                  size: 16, color: cs.onErrorContainer),
                              visualDensity: VisualDensity.compact,
                              onPressed: () =>
                                  setState(() => _errorMessage = null),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                    ],

                    // ── Submit ─────────────────────────────────────────────
                    FilledButton.icon(
                      key: const Key('transfer_submit_button'),
                      onPressed: _isSubmitting ? null : _submit,
                      style: FilledButton.styleFrom(
                        backgroundColor: cs.primary,
                        foregroundColor: cs.onPrimary,
                        minimumSize: const Size.fromHeight(52),
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14)),
                      ),
                      icon: _isSubmitting
                          ? SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: cs.onSurfaceVariant),
                            )
                          : const Icon(Icons.swap_horiz_rounded),
                      label: const Text('Confirmer le transfert',
                          style: TextStyle(fontWeight: FontWeight.w600)),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final Color color;
  final Color iconColor;

  const _InfoRow({
    required this.icon,
    required this.label,
    required this.value,
    required this.color,
    required this.iconColor,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: iconColor),
          const SizedBox(width: 10),
          Text('$label : ',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          Expanded(
            child: Text(
              value,
              style: theme.textTheme.bodyMedium
                  ?.copyWith(fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

class _StepperBtn extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const _StepperBtn({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppTheme.radiusM),
      child: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: AppTheme.softTint(theme.brightness),
          borderRadius: BorderRadius.circular(AppTheme.radiusM),
        ),
        child: Icon(icon, size: 20, color: cs.primary),
      ),
    );
  }
}
