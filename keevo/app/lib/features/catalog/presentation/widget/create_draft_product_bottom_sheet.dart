import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/exception/product_exception.dart';
import '../provider/category_provider.dart';
import '../provider/product_provider.dart';

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
  final _priceController = TextEditingController();
  final _quantityController = TextEditingController(text: '1');
  String? _selectedCategoryId;
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void dispose() {
    _priceController.dispose();
    _quantityController.dispose();
    super.dispose();
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
        name: widget.prefillName,
        priceVente: int.parse(_priceController.text.trim()),
        categoryId: _selectedCategoryId!,
        stockQuantity: qty,
      );

      // Refresh product list so the DRAFT badge appears in catalogue.
      ref.invalidate(productListProvider);
      ref.invalidate(pendingDraftsCountProvider);

      if (mounted) Navigator.of(context).pop(product);
    } on ProductException catch (e) {
      setState(() => _errorMessage = e.message);
    } catch (e) {
      setState(() => _errorMessage = 'Erreur inattendue : $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final categoriesAsync = ref.watch(categoriesProvider);

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '🔶 Créer un brouillon',
                    style: theme.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
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

              // Product name (read-only — pre-filled from POS search)
              TextFormField(
                initialValue: widget.prefillName,
                readOnly: true,
                decoration: InputDecoration(
                  labelText: 'Nom du produit',
                  filled: true,
                  fillColor:
                      theme.colorScheme.surfaceVariant.withOpacity(0.5),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide.none,
                  ),
                  suffixIcon: const Icon(Icons.lock_outline, size: 16),
                ),
              ),
              const SizedBox(height: 16),

              // Price de vente (required)
              TextFormField(
                controller: _priceController,
                keyboardType: TextInputType.number,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: 'Prix de vente (XAF) *',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
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
                  decoration: InputDecoration(
                    labelText: 'Catégorie *',
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
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
                decoration: InputDecoration(
                  labelText: 'Quantité pour cette vente',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return null;
                  final n = int.tryParse(v.trim());
                  if (n == null || n < 0) return 'Entrez une quantité valide';
                  return null;
                },
              ),

              if (_errorMessage != null) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(8),
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
    );
  }
}
