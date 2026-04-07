import 'dart:developer' as dev;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/widget/app_error_widget.dart';

import '../../../catalog/presentation/provider/category_provider.dart';
import '../../data/datasource/remote_quick_add_datasource.dart';
import '../provider/quick_add_product_provider.dart';

/// Shows the quick-add product sheet and returns the [QuickAddResult] on
/// success, the string `'COUNT_EXISTING'` on dedup, or `null` if dismissed.
Future<Object?> showQuickAddProductSheet({
  required BuildContext context,
  required String sessionId,
}) async {
  Object? sheetResult;
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => _QuickAddProductSheet(
      sessionId: sessionId,
      onResult: (result) => sheetResult = result,
    ),
  );
  return sheetResult;
}

class _QuickAddProductSheet extends ConsumerStatefulWidget {
  final String sessionId;
  final ValueChanged<Object?> onResult;

  const _QuickAddProductSheet({
    required this.sessionId,
    required this.onResult,
  });

  @override
  ConsumerState<_QuickAddProductSheet> createState() =>
      _QuickAddProductSheetState();
}

class _QuickAddProductSheetState
    extends ConsumerState<_QuickAddProductSheet> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _qtyController = TextEditingController(text: '1');
  final _priceController = TextEditingController();
  final _nameFocus = FocusNode();
  String? _selectedCategoryId;
  bool _isSubmitting = false;

  @override
  void initState() {
    super.initState();
    // Auto-focus the name field after the sheet opens.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _nameFocus.requestFocus();
    });
  }

  @override
  void dispose() {
    _nameController.dispose();
    _qtyController.dispose();
    _priceController.dispose();
    _nameFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final categoriesAsync = ref.watch(categoriesProvider);

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Drag handle ──────────────────────────────────────
          Container(
            margin: const EdgeInsets.only(top: 12, bottom: 4),
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: theme.colorScheme.outlineVariant,
              borderRadius: BorderRadius.circular(2),
            ),
          ),

          // ── Header ───────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 12, 0),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    Icons.add_shopping_cart_rounded,
                    size: 20,
                    color: theme.colorScheme.onPrimaryContainer,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Nouveau produit',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        'Ajout rapide pendant l\'inventaire',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                  style: IconButton.styleFrom(
                    foregroundColor: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 16),
          const Divider(height: 1),
          const SizedBox(height: 20),

          // ── Form ─────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 1. Product name
                  TextFormField(
                    controller: _nameController,
                    focusNode: _nameFocus,
                    textCapitalization: TextCapitalization.sentences,
                    decoration: InputDecoration(
                      labelText: 'Nom du produit',
                      hintText: 'Ex : Robe Wax L',
                      prefixIcon: const Icon(Icons.inventory_2_outlined),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      filled: true,
                      fillColor:
                          theme.colorScheme.surfaceContainerLowest,
                    ),
                    maxLength: 200,
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) {
                        return 'Le nom est requis';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),

                  // 2. Category
                  categoriesAsync.when(
                    loading: () => const LinearProgressIndicator(),
                    error: (_, __) =>
                        const Text('Erreur chargement catégories'),
                    data: (categories) {
                      return DropdownButtonFormField<String>(
                        initialValue: _selectedCategoryId,
                        decoration: InputDecoration(
                          labelText: 'Catégorie',
                          prefixIcon:
                              const Icon(Icons.category_outlined),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          filled: true,
                          fillColor:
                              theme.colorScheme.surfaceContainerLowest,
                        ),
                        hint: const Text('Choisir une catégorie…'),
                        items: categories
                            .map((c) => DropdownMenuItem<String>(
                                  value: c.id,
                                  child: Text(c.name),
                                ))
                            .toList(),
                        onChanged: (v) =>
                            setState(() => _selectedCategoryId = v),
                        validator: (v) {
                          if (v == null) {
                            return 'La catégorie est requise';
                          }
                          return null;
                        },
                      );
                    },
                  ),
                  const SizedBox(height: 16),

                  // 3. Physical quantity
                  TextFormField(
                    controller: _qtyController,
                    decoration: InputDecoration(
                      labelText: 'Quantité physique',
                      prefixIcon: const Icon(Icons.numbers),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      filled: true,
                      fillColor:
                          theme.colorScheme.surfaceContainerLowest,
                    ),
                    keyboardType: TextInputType.number,
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                    ],
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) {
                        return 'La quantité est requise';
                      }
                      final n = int.tryParse(v);
                      if (n == null || n < 0) {
                        return 'Quantité invalide';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),

                  // 4. Selling price (optional)
                  TextFormField(
                    controller: _priceController,
                    decoration: InputDecoration(
                      labelText: 'Prix de vente (optionnel)',
                      hintText: 'Ex : 5000',
                      prefixIcon: const Icon(Icons.payments_outlined),
                      suffixText: 'XAF',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      filled: true,
                      fillColor:
                          theme.colorScheme.surfaceContainerLowest,
                    ),
                    keyboardType: TextInputType.number,
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                    ],
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) return null;
                      final n = int.tryParse(v);
                      if (n == null || n < 0) {
                        return 'Prix invalide';
                      }
                      return null;
                    },
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 24),

          // ── Actions ──────────────────────────────────────────
          Padding(
            padding: EdgeInsets.fromLTRB(
              20, 0, 20, 20 + MediaQuery.of(context).viewPadding.bottom,
            ),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed:
                        _isSubmitting ? null : () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: const Text('Annuler'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  flex: 2,
                  child: FilledButton.icon(
                    onPressed: _isSubmitting ? null : _submit,
                    icon: _isSubmitting
                        ? SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: theme.colorScheme.onPrimary,
                            ),
                          )
                        : const Icon(Icons.add_circle_outline, size: 18),
                    label: const Text('Créer le produit'),
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSubmitting = true);

    // Capture navigator/messenger before the async gap to avoid
    // using a deactivated BuildContext after state changes.
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);

    try {
      final result = await ref
          .read(quickAddProductNotifierProvider.notifier)
          .quickAdd(
            sessionId: widget.sessionId,
            name: _nameController.text.trim(),
            categoryId: _selectedCategoryId!,
            physicalQty: int.parse(_qtyController.text.trim()),
            sellingPrice: _priceController.text.trim().isNotEmpty
                ? int.parse(_priceController.text.trim())
                : null,
          );

      if (result != null) {
        // Pass result via callback (bypasses Navigator return value issues)
        widget.onResult(result);
        if (mounted) {
          navigator.pop();
        }
        return;
      }

      // result == null → dedup (409)
      if (!mounted) return;
      _showDedupDialog(_nameController.text.trim());
    } on DioException catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Erreur réseau : ${e.message ?? 'connexion perdue'}'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (e, st) {
      dev.log('QuickAddSheet error: $e', name: 'QuickAddSheet', error: e, stackTrace: st);
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text(appErrorMessage(e)),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  void _showDedupDialog(String name) {
    final theme = Theme.of(context);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.warning_amber_rounded,
            color: theme.colorScheme.error, size: 32),
        title: const Text('Produit existant'),
        content:
            Text('Un produit « $name » existe déjà dans votre catalogue.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Modifier le nom'),
          ),
          FilledButton.icon(
            onPressed: () {
              Navigator.pop(ctx);
              widget.onResult('COUNT_EXISTING');
              Navigator.pop(context);
            },
            icon: const Icon(Icons.search, size: 18),
            label: const Text("Compter l'existant"),
          ),
        ],
      ),
    );
  }
}

