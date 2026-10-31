import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/storage/search_fold.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/contact_validation.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import '../../../catalog/domain/model/product_model.dart';
import '../../../catalog/presentation/provider/product_provider.dart';
import '../../domain/model/supplier_model.dart';
import '../provider/contact_provider.dart';

/// SupplierFormPage — Modern Material 3 create/edit form (Story 2.5).
class SupplierFormPage extends ConsumerStatefulWidget {
  final SupplierModel? supplier;
  const SupplierFormPage({super.key, this.supplier});

  bool get isEditing => supplier != null;

  @override
  ConsumerState<SupplierFormPage> createState() => _SupplierFormPageState();
}

class _SupplierFormPageState extends ConsumerState<SupplierFormPage>
    with SingleTickerProviderStateMixin {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _phoneCtrl;
  late final TextEditingController _emailCtrl;
  List<String> _selectedProductIds = [];
  bool _isSaving = false;

  late AnimationController _animCtrl;
  late Animation<double> _fadeAnim;
  late Animation<Offset> _slideAnim;

  @override
  void initState() {
    super.initState();
    _nameCtrl  = TextEditingController(text: widget.supplier?.name);
    _phoneCtrl = TextEditingController(text: widget.supplier?.phone);
    _emailCtrl = TextEditingController(text: widget.supplier?.email);
    _selectedProductIds = List<String>.from(widget.supplier?.productIds ?? []);

    _animCtrl = AnimationController(
      duration: const Duration(milliseconds: 1000),
      vsync: this,
    );
    _fadeAnim = Tween<double>(begin: 0, end: 1).animate(
      CurvedAnimation(parent: _animCtrl, curve: const Interval(0, 0.6, curve: Curves.easeOut)),
    );
    _slideAnim = Tween<Offset>(begin: const Offset(0, 0.3), end: Offset.zero).animate(
      CurvedAnimation(parent: _animCtrl, curve: const Interval(0.2, 1, curve: Curves.elasticOut)),
    );
    _animCtrl.forward();
  }

  @override
  void dispose() {
    _animCtrl.dispose();
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    _emailCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isSaving = true);
    try {
      if (widget.isEditing) {
        await ref.read(supplierListNotifierProvider.notifier).patchSupplier(
              id: widget.supplier!.id,
              name: _nameCtrl.text.trim(),
              phone: normalizeContactPhone(_phoneCtrl.text)!,
              email: _emailCtrl.text.trim().isEmpty ? null : _emailCtrl.text.trim(),
              productIds: _selectedProductIds,
            );
      } else {
        await ref.read(supplierListNotifierProvider.notifier).create(
              name: _nameCtrl.text.trim(),
              phone: normalizeContactPhone(_phoneCtrl.text)!,
              email: _emailCtrl.text.trim().isEmpty ? null : _emailCtrl.text.trim(),
              productIds: _selectedProductIds,
            );
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(children: [
              const Icon(Icons.check_circle_rounded, color: AppTheme.onBrand, size: 20),
              const SizedBox(width: 12),
              Text(
                widget.isEditing
                    ? 'Fournisseur modifié avec succès !'
                    : 'Fournisseur créé avec succès !',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ]),
            backgroundColor: AppTheme.primary,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(children: [
              const Icon(Icons.error_rounded, color: AppTheme.onBrand, size: 20),
              const SizedBox(width: 12),
              Expanded(child: Text(appErrorMessage(e))),
            ]),
            backgroundColor: AppTheme.errorStrong,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // AppBar épuré — fond neutre, sans bandeau dégradé ni cercles
          // décoratifs (même traitement que le formulaire produit).
          SliverAppBar(
            expandedHeight: 96,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            leading: IconButton(
              icon: Icon(Icons.arrow_back_ios_new_rounded,
                  color: theme.colorScheme.onSurface, size: 20),
              onPressed: () => context.pop(),
            ),
            flexibleSpace: FlexibleSpaceBar(
              titlePadding: const EdgeInsets.fromLTRB(56, 0, 16, 16),
              centerTitle: false,
              title: Text(
                widget.isEditing ? 'Modifier le fournisseur' : 'Nouveau fournisseur',
                style: TextStyle(
                  color: theme.colorScheme.onSurface,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                ),
              ),
              background: ColoredBox(color: theme.colorScheme.surface),
            ),
          ),

          // ── Form ─────────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: FadeTransition(
              opacity: _fadeAnim,
              child: SlideTransition(
                position: _slideAnim,
                child: Container(
                  margin: const EdgeInsets.all(20),
                  decoration: AppTheme.softCardDecoration(
                    theme.colorScheme,
                    radius: AppTheme.radiusXL,
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Form(
                      key: _formKey,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _SectionHeader(
                            icon: Icons.local_shipping_outlined,
                            title: 'Informations du fournisseur',
                            color: primary,
                          ),
                          const SizedBox(height: 20),

                          _ModernField(
                            controller: _nameCtrl,
                            label: 'Nom / Raison sociale',
                            hint: 'Ex: Import Export Sarl, Grossiste ABC…',
                            icon: Icons.business_outlined,
                            required: true,
                            validator: (v) =>
                                (v == null || v.trim().isEmpty) ? 'Le nom est requis' : null,
                          ),
                          const SizedBox(height: 20),

                          _ModernField(
                            controller: _phoneCtrl,
                            label: 'Téléphone',
                            hint: '6 99 11 22 33',
                            icon: Icons.phone_outlined,
                            required: true,
                            keyboardType: TextInputType.phone,
                            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[+\d\s]'))],
                            validator: contactPhoneError,
                          ),
                          const SizedBox(height: 20),

                          _ModernField(
                            controller: _emailCtrl,
                            label: 'Email (optionnel)',
                            hint: 'fournisseur@example.com',
                            icon: Icons.email_outlined,
                            keyboardType: TextInputType.emailAddress,
                            validator: contactEmailError,
                          ),

                          const SizedBox(height: 32),

                          // ── Produits associés ──────────────────────
                          _SectionHeader(
                            icon: Icons.inventory_2_outlined,
                            title: 'Produits associés',
                            color: primary,
                          ),
                          const SizedBox(height: 12),
                          _ProductSelector(
                            selectedIds: _selectedProductIds,
                            currentSupplierId: widget.supplier?.id,
                            onChanged: (ids) => setState(() => _selectedProductIds = ids),
                          ),

                          const SizedBox(height: 32),

                          _SaveButton(
                            isSaving: _isSaving,
                            isEditing: widget.isEditing,
                            onPressed: _submit,
                            color: primary,
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
    );
  }
}

// ── Shared form widgets ────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final IconData icon;
  final String title;
  final Color color;
  const _SectionHeader({required this.icon, required this.title, required this.color});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SoftIconBadge(icon: icon, size: 36),
        const SizedBox(width: 12),
        Flexible(
          child: Text(
            title,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w700,
              color: color,
            ),
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}

class _ModernField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final String? hint;
  final IconData? icon;
  final TextInputType? keyboardType;
  final String? Function(String?)? validator;
  final List<TextInputFormatter>? inputFormatters;
  final bool required;

  const _ModernField({
    required this.controller,
    required this.label,
    this.hint,
    this.icon,
    this.keyboardType,
    this.validator,
    this.inputFormatters,
    this.required = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // Hérite du style de champ global (AppTheme) plutôt que de redéfinir
    // bordures et remplissage en local.
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      validator: validator,
      inputFormatters: inputFormatters,
      maxLines: 1,
      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
      decoration: InputDecoration(
        labelText: required ? '$label *' : label,
        hintText: hint,
        prefixIcon: icon != null ? Icon(icon, size: 20) : null,
        labelStyle: TextStyle(
          color: required ? theme.colorScheme.primary : theme.colorScheme.onSurfaceVariant,
          fontWeight: required ? FontWeight.w600 : FontWeight.w500,
        ),
      ),
    );
  }
}

class _SaveButton extends StatelessWidget {
  final bool isSaving;
  final bool isEditing;
  final VoidCallback onPressed;
  final Color color;
  const _SaveButton({
    required this.isSaving,
    required this.isEditing,
    required this.onPressed,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: isSaving ? Theme.of(context).colorScheme.outline : color,
        borderRadius: BorderRadius.circular(16),
        boxShadow: isSaving
            ? []
            : [
                BoxShadow(
                  color: color.withValues(alpha: 0.25),
                  blurRadius: 10,
                  offset: const Offset(0, 4),
                ),
              ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: isSaving ? null : onPressed,
          borderRadius: BorderRadius.circular(16),
          child: Center(
            child: isSaving
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 20, height: 20,
                        child: CircularProgressIndicator(color: Theme.of(context).colorScheme.onPrimary, strokeWidth: 2),
                      ),
                      SizedBox(width: 16),
                      Text(
                        'Sauvegarde en cours…',
                        style: TextStyle(color: Theme.of(context).colorScheme.onPrimary, fontWeight: FontWeight.w600),
                      ),
                    ],
                  )
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isEditing ? Icons.save_outlined : Icons.add_business_outlined,
                        color: Theme.of(context).colorScheme.onPrimary, size: 24,
                      ),
                      const SizedBox(width: 12),
                      Text(
                        isEditing
                            ? 'Enregistrer les modifications'
                            : 'Créer le fournisseur',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.onPrimary, fontSize: 16,
                          fontWeight: FontWeight.w700, letterSpacing: 0.5,
                        ),
                      ),
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}

// ── Product multi-selector ─────────────────────────────────────────────────────

/// Shows a summary of selected products and opens a bottom sheet to pick more.
class _ProductSelector extends ConsumerWidget {
  final List<String> selectedIds;
  final String? currentSupplierId;
  final ValueChanged<List<String>> onChanged;

  const _ProductSelector({
    required this.selectedIds,
    required this.onChanged,
    this.currentSupplierId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;
    final productsAsync = ref.watch(productListForPickerProvider);
    final suppliersAsync = ref.watch(supplierListNotifierProvider);

    return productsAsync.when(
      loading: () => Container(
        height: 60,
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
          borderRadius: BorderRadius.circular(AppTheme.radiusM),
        ),
        child: const Center(child: CircularProgressIndicator()),
      ),
      // Sans ce message, le sélecteur disparaissait sans explication.
      error: (_, __) => Row(
        children: [
          Icon(Icons.error_outline_rounded,
              size: 18, color: theme.colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Impossible de charger les produits',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.error),
            ),
          ),
          TextButton(
            onPressed: () => ref.invalidate(productListForPickerProvider),
            child: const Text('Réessayer'),
          ),
        ],
      ),
      data: (products) {
        final active = products.where((p) => !p.archived).toList();
        final selected = active.where((p) => selectedIds.contains(p.id)).toList();

        // Compute product IDs already taken by OTHER suppliers
        final takenByOthers = <String>{};
        suppliersAsync.whenData((suppliers) {
          for (final s in suppliers) {
            if (s.id != currentSupplierId) {
              takenByOthers.addAll(s.productIds);
            }
          }
        });

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Summary chip row
            if (selected.isNotEmpty) ...[
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children: selected.map((p) => Chip(
                  label: Text(
                    p.name,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: primary,
                    ),
                  ),
                  backgroundColor: AppTheme.softTint(theme.brightness),
                  side: BorderSide(color: primary.withValues(alpha: 0.3)),
                  deleteIcon: Icon(Icons.close_rounded, size: 16, color: primary),
                  onDeleted: () {
                    final updated = List<String>.from(selectedIds)..remove(p.id);
                    onChanged(updated);
                  },
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                )).toList(),
              ),
              const SizedBox(height: 10),
            ],

            // Add button
            OutlinedButton.icon(
              onPressed: () => _openPicker(context, active, takenByOthers),
              icon: Icon(
                selected.isEmpty ? Icons.add_rounded : Icons.edit_outlined,
                size: 18,
                color: primary,
              ),
              label: Text(
                selected.isEmpty
                    ? 'Associer des produits'
                    : '${selected.length} produit${selected.length > 1 ? 's' : ''} — modifier',
                style: TextStyle(color: primary, fontWeight: FontWeight.w600),
              ),
              style: OutlinedButton.styleFrom(
                side: BorderSide(color: primary.withValues(alpha: 0.5)),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _openPicker(BuildContext context, List<ProductModel> products, Set<String> takenByOthers) async {
    final result = await showModalBottomSheet<List<String>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _ProductPickerSheet(
        products: products,
        initialSelected: selectedIds,
        takenProductIds: takenByOthers,
      ),
    );
    if (result != null) {
      onChanged(result);
    }
  }
}

/// Bottom sheet listing all active products with checkboxes.
class _ProductPickerSheet extends StatefulWidget {
  final List<ProductModel> products;
  final List<String> initialSelected;
  final Set<String> takenProductIds;

  const _ProductPickerSheet({
    required this.products,
    required this.initialSelected,
    this.takenProductIds = const {},
  });

  @override
  State<_ProductPickerSheet> createState() => _ProductPickerSheetState();
}

class _ProductPickerSheetState extends State<_ProductPickerSheet> {
  late List<String> _selected;
  String _search = '';

  @override
  void initState() {
    super.initState();
    _selected = List<String>.from(widget.initialSelected);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;

    final q = foldForSearch(_search);
    final filtered = widget.products
        .where((p) => foldForSearch(p.name).contains(q))
        .toList();

    return DraggableScrollableSheet(
      initialChildSize: 0.75,
      minChildSize: 0.4,
      maxChildSize: 0.92,
      expand: false,
      builder: (_, scrollCtrl) => Container(
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(
          children: [
            // Handle
            Container(
              margin: const EdgeInsets.only(top: 12, bottom: 8),
              width: 40, height: 4,
              decoration: BoxDecoration(
                color: theme.colorScheme.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            // Header
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      'Sélectionner les produits',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(context, _selected),
                    child: Text(
                      'Valider (${_selected.length})',
                      style: TextStyle(
                        color: primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            // Search
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: TextField(
                decoration: InputDecoration(
                  hintText: 'Rechercher un produit…',
                  prefixIcon: Icon(Icons.search_rounded,
                      color: theme.colorScheme.onSurfaceVariant),
                  isDense: true,
                ),
                onChanged: (v) => setState(() => _search = v),
              ),
            ),
            const Divider(height: 1),
            // List
            Expanded(
              child: filtered.isEmpty
                  ? Center(
                      child: Text(
                        'Aucun produit trouvé',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    )
                  : ListView.builder(
                      controller: scrollCtrl,
                      itemCount: filtered.length,
                      itemBuilder: (_, i) {
                        final product = filtered[i];
                        final isSelected = _selected.contains(product.id);
                        final isTaken = widget.takenProductIds.contains(product.id);
                        return CheckboxListTile(
                          value: isSelected,
                          onChanged: isTaken ? null : (_) {
                            setState(() {
                              if (isSelected) {
                                _selected.remove(product.id);
                              } else {
                                _selected.add(product.id);
                              }
                            });
                          },
                          title: Text(
                            product.name,
                            style: TextStyle(
                              fontWeight: FontWeight.w500,
                              color: isTaken
                                  ? theme.colorScheme.onSurface.withValues(alpha: 0.38)
                                  : null,
                            ),
                          ),
                          subtitle: isTaken
                              ? Text(
                                  'Déjà associé à un autre fournisseur',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: theme.colorScheme.onSurface.withValues(alpha: 0.38),
                                  ),
                                )
                              : product.price > 0
                                  ? Text(
                                      NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0).format(product.price),
                                      style: TextStyle(
                                        color: primary,
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    )
                                  : null,
                          activeColor: primary,
                          checkboxShape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(6),
                          ),
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 4,
                          ),
                        );
                      },
                    ),
            ),
            // Bottom confirm button
            SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton(
                  onPressed: () => Navigator.pop(context, _selected),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(56),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                  ),
                  child: Text(
                    _selected.isEmpty
                        ? 'Aucun produit sélectionné'
                        : 'Confirmer ${_selected.length} produit${_selected.length > 1 ? 's' : ''}',
                    style: const TextStyle(
                      fontWeight: FontWeight.w700, fontSize: 16,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
