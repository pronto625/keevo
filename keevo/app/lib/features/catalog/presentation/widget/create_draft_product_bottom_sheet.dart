import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/exception/product_exception.dart';
import '../../domain/model/product_model.dart';
import '../provider/category_provider.dart';
import '../provider/product_provider.dart';

/// Result returned by the bottom sheet: either a new draft product or an
/// existing product the user chose, plus the quantity for the sale.
class DraftCreationResult {
  final ProductModel product;
  final int quantity;
  const DraftCreationResult({required this.product, required this.quantity});
}

/// CreateDraftProductBottomSheet — on-the-fly DRAFT product creation (AC6).
///
/// Shown when a POS search returns no matching product. Pre-fills the product
/// name from the POS search query. The user enters the required price and
/// category, then taps "Créer & Ajouter".
///
/// Usage (from POS in Epic 4):
/// ```dart
/// final product = await showModalBottomSheet<ProductModel>(
///   context: context,
///   isScrollControlled: true,
///   builder: (_) => CreateDraftProductBottomSheet(prefillName: searchQuery),
/// );
/// ```
class CreateDraftProductBottomSheet extends ConsumerStatefulWidget {
  /// The product name pre-filled from the POS search query (read-only).
  final String prefillName;

  const CreateDraftProductBottomSheet({
    super.key,
    required this.prefillName,
  });

  @override
  ConsumerState<CreateDraftProductBottomSheet> createState() =>
      _CreateDraftProductBottomSheetState();
}

class _CreateDraftProductBottomSheetState
    extends ConsumerState<CreateDraftProductBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  final _priceController = TextEditingController();
  final _quantityController = TextEditingController(text: '1');
  String? _selectedCategoryId;
  bool _isLoading = false;
  String? _errorMessage;

  /// Existing products matching the typed name (debounced search).
  List<ProductModel> _nameSuggestions = [];
  Timer? _debounce;

  bool get _isNameEditable => widget.prefillName.isEmpty;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.prefillName);
    if (_isNameEditable) {
      _nameController.addListener(_onNameChanged);
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _nameController.dispose();
    _priceController.dispose();
    _quantityController.dispose();
    super.dispose();
  }

  void _onNameChanged() {
    _debounce?.cancel();
    final query = _nameController.text.trim();
    if (query.length < 2) {
      if (_nameSuggestions.isNotEmpty) {
        setState(() => _nameSuggestions = []);
      }
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 300), () async {
      final repo = ref.read(productRepositoryProvider);
      final results = await repo.search(query);
      if (mounted) {
        setState(() => _nameSuggestions = results.take(5).toList());
      }
    });
  }

  void _selectExistingProduct(ProductModel product) {
    final qty = int.tryParse(_quantityController.text.trim()) ?? 1;
    Navigator.of(context).pop(
      DraftCreationResult(product: product, quantity: qty < 1 ? 1 : qty),
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedCategoryId == null) {
      setState(() => _errorMessage = 'Veuillez sélectionner une catégorie');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final repo = ref.read(productRepositoryProvider);
      final qty = int.tryParse(_quantityController.text.trim()) ?? 1;

      final product = await repo.createDraft(
        name: _nameController.text.trim(),
        priceVente: int.parse(_priceController.text.trim()),
        categoryId: _selectedCategoryId!,
      );

      // Refresh product list so the DRAFT badge appears in catalogue.
      ref.invalidate(productListProvider);
      ref.invalidate(pendingDraftsCountProvider);

      if (mounted) {
        Navigator.of(context).pop(
          DraftCreationResult(product: product, quantity: qty < 1 ? 1 : qty),
        );
      }
    } on ProductException catch (e) {
      if (mounted) setState(() => _errorMessage = e.message);
    } catch (e) {
      if (mounted) setState(() => _errorMessage = 'Erreur inattendue : $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final categoriesAsync = ref.watch(categoriesProvider);
    // Ambre lisible en texte : le jaune pur manque de contraste sur fond clair.
    final warningText = theme.brightness == Brightness.dark
        ? cs.warning
        : AppTheme.onWarning;

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                children: [
                  const SoftIconBadge(
                    icon: Icons.edit_note_rounded,
                    color: AppTheme.warning,
                    size: 36,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Créer un brouillon',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: Icon(Icons.close_rounded, color: cs.onSurfaceVariant),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                'Ce produit sera créé en brouillon et devra être validé par le propriétaire.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 24),

              // Product name
              TextFormField(
                controller: _nameController,
                readOnly: !_isNameEditable,
                autofocus: _isNameEditable,
                decoration: InputDecoration(
                  labelText: 'Nom du produit',
                  // Nom figé (pré-rempli depuis la recherche POS) : fond plus
                  // marqué pour le distinguer d'un champ éditable.
                  fillColor: _isNameEditable
                      ? null
                      : cs.surfaceContainerHighest,
                  suffixIcon: _isNameEditable
                      ? null
                      : const Icon(Icons.lock_outline, size: 16),
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) {
                    return 'Le nom du produit est requis';
                  }
                  return null;
                },
              ),

              // Existing product suggestions
              if (_nameSuggestions.isNotEmpty) ...[
                const SizedBox(height: 8),
                Container(
                  decoration: BoxDecoration(
                    color: AppTheme.warning.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(AppTheme.radiusM),
                    border: Border.all(
                        color: AppTheme.warning.withValues(alpha: 0.3)),
                  ),
                  padding: const EdgeInsets.all(8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Produit(s) existant(s) :',
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: warningText,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 4),
                      ..._nameSuggestions.map((p) => InkWell(
                            onTap: () => _selectExistingProduct(p),
                            borderRadius: BorderRadius.circular(AppTheme.radiusS),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                  vertical: 6, horizontal: 4),
                              child: Row(
                                children: [
                                  Icon(Icons.check_circle_outline_rounded,
                                      size: 18, color: cs.success),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      '${p.name} — ${p.price} FCFA',
                                      style: theme.textTheme.bodyMedium,
                                    ),
                                  ),
                                  Text(
                                    'Sélectionner',
                                    style: theme.textTheme.labelSmall
                                        ?.copyWith(color: theme.colorScheme.primary),
                                  ),
                                ],
                              ),
                            ),
                          )),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 16),

              // Price de vente (required)
              TextFormField(
                controller: _priceController,
                keyboardType: TextInputType.number,
                autofocus: !_isNameEditable,
                decoration: const InputDecoration(
                  labelText: 'Prix de vente (XAF) *',
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) {
                    return 'Le prix de vente est requis';
                  }
                  final n = int.tryParse(v.trim());
                  if (n == null || n <= 0) {
                    return 'Entrez un prix valide (> 0)';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // Category (required)
              categoriesAsync.when(
                data: (cats) => DropdownButtonFormField<String>(
                  value: _selectedCategoryId,
                  decoration: const InputDecoration(
                    labelText: 'Catégorie *',
                  ),
                  items: cats.map((c) {
                    return DropdownMenuItem(
                      value: c.id,
                      child: Text(c.name),
                    );
                  }).toList(),
                  onChanged: (v) =>
                      setState(() => _selectedCategoryId = v),
                  validator: (_) => _selectedCategoryId == null
                      ? 'Veuillez sélectionner une catégorie'
                      : null,
                ),
                loading: () => const LinearProgressIndicator(),
                error: (_, __) => const Text('Impossible de charger les catégories'),
              ),
              const SizedBox(height: 16),

              // Quantity for this sale (optional, default 1)
              TextFormField(
                controller: _quantityController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Quantité pour cette vente',
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return null;
                  final n = int.tryParse(v.trim());
                  if (n == null || n < 1) {
                    return 'Entrez une quantité valide (au moins 1)';
                  }
                  return null;
                },
              ),

              if (_errorMessage != null) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(AppTheme.radiusM),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline,
                          color: theme.colorScheme.error, size: 16),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _errorMessage!,
                          style: TextStyle(color: theme.colorScheme.error),
                        ),
                      ),
                    ],
                  ),
                ),
              ],

              const SizedBox(height: 24),

              // Submit button
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(56),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusL),
                    ),
                  ),
                  onPressed: _isLoading ? null : _submit,
                  icon: _isLoading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.add_circle_outline),
                  label: Text(_isLoading ? 'Création…' : 'Créer & Ajouter'),
                ),
              ),
              const SizedBox(height: 8),
            ],
          ),
          ),
        ),
      ),
    );
  }
}
