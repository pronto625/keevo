import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:keevo_core/photo/product_photo_compressor.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/discard_changes_dialog.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';

import '../../../../core/photo/product_photo_providers.dart';
import '../../../../core/router/module_routes.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../../../core/widget/product_photo.dart';
import '../../../contact/domain/model/supplier_model.dart';
import '../../../contact/presentation/provider/contact_provider.dart';
import '../../domain/exception/product_exception.dart';
import '../../domain/model/product_model.dart';
import '../../domain/model/product_status.dart';
import '../provider/category_provider.dart';
import '../provider/product_provider.dart';
import '../widget/pricing_calculator_widget.dart';
import '../widget/stock_level_widget.dart';
import 'stock_history_page.dart';

/// ProductFormPage — Modern Material 3 design for creating/editing products
class ProductFormPage extends ConsumerStatefulWidget {
  final ProductModel? product;

  const ProductFormPage({
    super.key,
    this.product,
  });

  bool get isEditing => product != null;

  @override
  ConsumerState<ProductFormPage> createState() => _ProductFormPageState();
}

class _ProductFormPageState extends ConsumerState<ProductFormPage>
    with SingleTickerProviderStateMixin {
  late AnimationController _animationController;
  late Animation<double> _fadeAnimation;
  late Animation<Offset> _slideAnimation;

  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _descriptionController = TextEditingController();
  final _skuController = TextEditingController();
  final _priceController = TextEditingController();
  final _buyPriceController = TextEditingController();
  final _transportCostController = TextEditingController();

  String? _selectedCategoryId;
  /// Thumbnail of the photo picked in this form (already compressed).
  Uint8List? _selectedImage;
  /// « Supprimer la photo » on the product's existing photo.
  bool _removeExistingPhoto = false;
  bool _compressingPhoto = false;
  bool _isSaving = false;

  /// Field values when the form opened; leaving with different values asks
  /// for confirmation instead of silently discarding them.
  late final String _initialSnapshot;

  @override
  void initState() {
    super.initState();
    _animationController = AnimationController(
      duration: const Duration(milliseconds: 1200),
      vsync: this,
    );
    
    _fadeAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(
      parent: _animationController,
      curve: const Interval(0.0, 0.6, curve: Curves.easeOut),
    ));

    _slideAnimation = Tween<Offset>(
      begin: const Offset(0, 0.3),
      end: Offset.zero,
    ).animate(CurvedAnimation(
      parent: _animationController,
      curve: const Interval(0.2, 1.0, curve: Curves.elasticOut),
    ));

    _animationController.forward();

    if (widget.product != null) {
      _initializeFields(widget.product!);
    }
    _initialSnapshot = _snapshot();
  }

  String _snapshot() => [
        _nameController.text,
        _descriptionController.text,
        _skuController.text,
        _priceController.text,
        _buyPriceController.text,
        _transportCostController.text,
        _selectedCategoryId ?? '',
        '${_selectedImage?.length ?? 0}',
        '$_removeExistingPhoto',
      ].join('\u0000');

  bool get _hasUnsavedChanges => _snapshot() != _initialSnapshot;

  /// Back (app bar or system): asks before discarding unsaved edits. A
  /// successful save pops through the router, which bypasses this.
  Future<void> _onPopRequested() async {
    if (_isSaving) return;
    if (_hasUnsavedChanges && !await showDiscardChangesDialog(context)) {
      return;
    }
    if (mounted) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    _animationController.dispose();
    _nameController.dispose();
    _descriptionController.dispose();
    _skuController.dispose();
    _priceController.dispose();
    _buyPriceController.dispose();
    _transportCostController.dispose();
    super.dispose();
  }

  void _initializeFields(ProductModel product) {
    _nameController.text = product.name;
    _descriptionController.text = product.description ?? '';
    _skuController.text = product.sku;
    _priceController.text = product.price.toString();
    _buyPriceController.text = product.buyPrice.toString();
    _transportCostController.text = product.transportCost.toString();
    _selectedCategoryId = product.categoryId;
  }

  Future<void> _showPhotoOptions() async {
    final theme = Theme.of(context);
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      // La feuille dessine sa propre poignée ; sans ceci celle du thème
      // flotterait au-dessus, sur le fond transparent.
      showDragHandle: false,
      builder: (context) => Container(
        margin: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          borderRadius: BorderRadius.circular(28),
          boxShadow: [
            BoxShadow(
              color: theme.colorScheme.shadow.withOpacity(0.1),
              blurRadius: 20,
              offset: const Offset(0, 10),
            ),
          ],
        ),
        child: SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Handle indicator
              Container(
                margin: const EdgeInsets.only(top: 12),
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: theme.dividerColor,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(height: 20),
              
              // Title
              Text(
                'Ajouter une photo',
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Choisissez comment ajouter une photo à votre produit',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              
              // Camera option — couleurs de marque (plus de violet hors
              // charte), couleur pleine plutôt qu'un dégradé.
              _PhotoOptionTile(
                icon: Icons.camera_alt_outlined,
                title: 'Prendre une photo',
                subtitle: 'Utiliser l\'appareil photo',
                color: theme.colorScheme.secondary,
                onColor: theme.colorScheme.onSecondary,
                onTap: () => _pickImage(ImageSource.camera),
              ),

              const SizedBox(height: 16),

              // Gallery option
              _PhotoOptionTile(
                icon: Icons.photo_library_outlined,
                title: 'Choisir depuis la galerie',
                subtitle: 'Sélectionner une image existante',
                color: theme.colorScheme.primary,
                onColor: theme.colorScheme.onPrimary,
                onTap: () => _pickImage(ImageSource.gallery),
              ),
              
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickImage(ImageSource source) async {
    Navigator.pop(context);
    // Native downscale first (cheap on a 12 MP photo), then the thumbnail
    // re-encode: ~400 px JPEG, < 40 KB, no metadata (FR21).
    final image = await ref.read(productPhotoPickerProvider).pickImage(
      source: source,
      maxWidth: ProductPhotoCompressor.pickerMaxDimension,
      maxHeight: ProductPhotoCompressor.pickerMaxDimension,
      imageQuality: ProductPhotoCompressor.pickerQuality,
    );
    if (image == null || !mounted) return;
    setState(() => _compressingPhoto = true);
    try {
      final thumb = await ref
          .read(productPhotoCompressorProvider)
          .compress(await image.readAsBytes());
      if (!mounted) return;
      setState(() {
        _selectedImage = thumb;
        _removeExistingPhoto = false;
      });
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Cette image ne peut pas être utilisée, choisissez-en une autre'),
      ));
    } finally {
      if (mounted) setState(() => _compressingPhoto = false);
    }
  }

  String? _validateRequired(String? value) {
    if (value == null || value.trim().isEmpty) {
      return 'Ce champ est obligatoire';
    }
    return null;
  }

  String? _validatePrice(String? value) {
    if (value == null || value.isEmpty) return null;
    final price = int.tryParse(value);
    if (price == null || price < 0) {
      return 'Prix invalide';
    }
    return null;
  }

  /// Applies the photo choice to [productId]: the new thumbnail is kept on
  /// this device and queued for upload, or the photo deletion is queued —
  /// both sent with the next sync (offline-first).
  Future<void> _savePhoto(String productId) async {
    final outbox = ref.read(productPhotoOutboxProvider);
    final picked = _selectedImage;
    if (picked != null) {
      final path =
          await ref.read(productPhotoFilesProvider).savePending(productId, picked);
      await outbox.enqueueUpload(productId, path);
    } else if (_removeExistingPhoto) {
      await outbox.enqueueDelete(productId);
    } else {
      return;
    }
    ref.read(syncTriggerDispatcherProvider).triggerPushIfIdle();
  }

  Future<void> _onSave() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      final actions = ref.read(productActionsProvider);
      
      if (widget.isEditing && widget.product != null) {
        await actions.update(
          id: widget.product!.id,
          name: _nameController.text.trim(),
          description: _descriptionController.text.trim().isEmpty 
              ? null 
              : _descriptionController.text.trim(),
          sku: _skuController.text.trim(),
          categoryId: _selectedCategoryId,
          price: int.tryParse(_priceController.text) ?? 0,
          buyPrice: int.tryParse(_buyPriceController.text) ?? 0,
          transportCost: int.tryParse(_transportCostController.text) ?? 0,
        );
        await _savePhoto(widget.product!.id);
      } else {
        final created = await actions.create(
          name: _nameController.text.trim(),
          description: _descriptionController.text.trim().isEmpty 
              ? null 
              : _descriptionController.text.trim(),
          sku: _skuController.text.trim(),
          categoryId: _selectedCategoryId,
          price: int.tryParse(_priceController.text) ?? 0,
          buyPrice: int.tryParse(_buyPriceController.text) ?? 0,
          transportCost: int.tryParse(_transportCostController.text) ?? 0,
        );
        await _savePhoto(created.id);
      }

      if (mounted) {
        // Invalidate all product list providers so the catalog page refreshes
        // immediately when we pop back, regardless of GoRouter's shell lifecycle.
        ref.invalidate(productListProvider);
        ref.invalidate(archivedProductListProvider);
        ref.invalidate(outOfStockProductListProvider);
        ref.invalidate(lowStockProductListProvider);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                Icon(
                  Icons.check_circle_rounded, 
                  color: AppTheme.onBrand,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Text(
                  widget.isEditing ? 'Produit modifié avec succès !' : 'Produit créé avec succès !',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            backgroundColor: AppTheme.successStrong,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
        context.pop();
      }
    } on ProductException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                Icon(
                  Icons.error_rounded, 
                  color: AppTheme.onBrand,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Expanded(child: Text(e.message)),
              ],
            ),
            backgroundColor: AppTheme.errorStrong,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } catch (e, st) {
      debugPrint('TMPDIAG product save failed: $e\n$st');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                const Icon(Icons.error_rounded, color: AppTheme.onBrand, size: 20),
                const SizedBox(width: 12),
                Expanded(child: Text(appErrorMessage(e))),
              ],
            ),
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
    final categories = ref.watch(categoriesProvider);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onPopRequested();
      },
      child: Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // AppBar épuré — fond neutre, sans bandeau dégradé ni cercles
          // décoratifs (cf. rebrand CatalogPage, même logique appliquée ici).
          SliverAppBar(
            expandedHeight: 96,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            flexibleSpace: FlexibleSpaceBar(
              titlePadding: const EdgeInsets.fromLTRB(56, 0, 16, 16),
              centerTitle: false,
              title: Text(
                widget.isEditing ? 'Modifier le produit' : 'Nouveau produit',
                style: TextStyle(
                  color: theme.colorScheme.onSurface,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                ),
              ),
              background: ColoredBox(color: theme.colorScheme.surface),
            ),
            leading: IconButton(
              icon: Icon(Icons.arrow_back_ios_new_rounded,
                  color: theme.colorScheme.onSurface, size: 20),
              onPressed: () => Navigator.maybePop(context),
            ),
            actions: [
              SoftIconButton(
                icon: Icons.lightbulb_outline_rounded,
                iconSize: 20,
                tooltip: 'Conseils',
                onPressed: () {
                  showDialog(
                    context: context,
                    builder: (context) => AlertDialog(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(20),
                      ),
                      title: const Text('Conseils'),
                      content: const Text(
                        'Remplissez au minimum le nom du produit. '
                        'Les autres champs sont optionnels mais recommandés '
                        'pour une meilleure organisation de votre catalogue.',
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(context),
                          child: const Text('Compris'),
                        ),
                      ],
                    ),
                  );
                },
              ),
              const SizedBox(width: 12),
            ],
          ),
          
          // Form content
          SliverToBoxAdapter(
            child: FadeTransition(
              opacity: _fadeAnimation,
              child: SlideTransition(
                position: _slideAnimation,
                child: Container(
                  margin: const EdgeInsets.symmetric(vertical: 16),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surface,
                    borderRadius: BorderRadius.circular(24),
                    boxShadow: [
                      BoxShadow(
                        color: theme.shadowColor.withOpacity(0.1),
                        blurRadius: 20,
                        offset: const Offset(0, 8),
                      ),
                    ],
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
                    child: Form(
                      key: _formKey,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // Section: Basic Information
                          _SectionHeader(
                            icon: Icons.info_outline_rounded,
                            title: 'Informations de base',
                            color: theme.colorScheme.primary,
                          ),
                          const SizedBox(height: 20),
                          
                          _ModernTextField(
                            controller: _nameController,
                            label: 'Nom du produit',
                            hint: 'Ex: iPhone 14 Pro, T-shirt Nike...',
                            icon: Icons.inventory_2_outlined,
                            validator: _validateRequired,
                            required: true,
                          ),
                          
                          const SizedBox(height: 20),
                          
                          _ModernTextField(
                            controller: _descriptionController,
                            label: 'Description',
                            hint: 'Décrivez votre produit en quelques mots...',
                            icon: Icons.description_outlined,
                            maxLines: 3,
                          ),
                          
                          const SizedBox(height: 20),
                          
                          // Categories dropdown
                          categories.when(
                            data: (categoryList) => _CategoryDropdown(
                              selectedId: _selectedCategoryId,
                              categories: categoryList,
                              onChanged: (value) => setState(() => _selectedCategoryId = value),
                            ),
                            loading: () => _LoadingDropdown(label: 'Catégorie'),
                            error: (_, __) => _ErrorDropdown(label: 'Catégorie'),
                          ),
                          
                          const SizedBox(height: 32),
                          
                          // Section: Product Details
                          _SectionHeader(
                            icon: Icons.local_offer_rounded,
                            title: 'Détails du produit',
                            color: theme.colorScheme.secondary,
                          ),
                          const SizedBox(height: 20),
                          
                          _ModernTextField(
                            controller: _skuController,
                            label: 'Référence SKU',
                            hint: 'Ex: KEV-001, AUTO-123...',
                            icon: Icons.qr_code_rounded,
                            keyboardType: TextInputType.text,
                            inputFormatters: [
                              LengthLimitingTextInputFormatter(20),
                              UpperCaseTextFormatter(),
                            ],
                          ),
                          
                          const SizedBox(height: 20),
                          
                          // Price fields
                          Row(
                            children: [
                              Expanded(
                                child: _ModernTextField(
                                  controller: _priceController,
                                  // No prefix icon on this pair: the two
                                  // fields share the row, and the icon left
                                  // too little room for the label on phones.
                                  label: 'Prix de vente',
                                  hint: '0',
                                  keyboardType: TextInputType.number,
                                  validator: _validatePrice,
                                  suffix: 'XAF',
                                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                                ),
                              ),
                              const SizedBox(width: 16),
                              Expanded(
                                child: _ModernTextField(
                                  controller: _buyPriceController,
                                  label: "Prix d'achat",
                                  hint: '0',
                                  keyboardType: TextInputType.number,
                                  validator: _validatePrice,
                                  suffix: 'XAF',
                                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                                ),
                              ),
                            ],
                          ),

                          const SizedBox(height: 16),

                          // Transport cost field
                          _ModernTextField(
                            controller: _transportCostController,
                            label: 'Coût transport',
                            hint: '0',
                            icon: Icons.local_shipping_rounded,
                            keyboardType: TextInputType.number,
                            validator: _validatePrice,
                            suffix: 'XAF',
                            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                          ),

                          const SizedBox(height: 16),

                          // Pricing calculator widget (live margin calculation)
                          ValueListenableBuilder<TextEditingValue>(
                            valueListenable: _priceController,
                            builder: (_, __, ___) => ValueListenableBuilder<TextEditingValue>(
                              valueListenable: _buyPriceController,
                              builder: (_, __, ___) => ValueListenableBuilder<TextEditingValue>(
                                valueListenable: _transportCostController,
                                builder: (_, __, ___) => PricingCalculatorWidget(
                                  sellingPrice: int.tryParse(_priceController.text) ?? 0,
                                  buyPrice: int.tryParse(_buyPriceController.text) ?? 0,
                                  transportCost: int.tryParse(_transportCostController.text) ?? 0,
                                ),
                              ),
                            ),
                          ),
                          
                          const SizedBox(height: 32),
                          
                          // Stock section — only visible when editing an existing product
                          if (widget.isEditing) ...
                            [
                              _SectionHeader(
                                icon: Icons.inventory_2_outlined,
                                title: 'Gestion du stock',
                                color: AppTheme.iconTeal,
                              ),
                              const SizedBox(height: 12),
                              if (widget.product?.status == ProductStatus.draft)
                                Container(
                                  padding: const EdgeInsets.all(12),
                                  decoration: BoxDecoration(
                                    color: AppTheme.warning.withOpacity(0.1),
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                        color: AppTheme.warning.withOpacity(0.3)),
                                  ),
                                  child: const Row(
                                    children: [
                                      Icon(Icons.info_outline,
                                          color: AppTheme.warning, size: 20),
                                      SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          'Validez ce brouillon pour gérer son stock.',
                                          style: TextStyle(
                                              color: AppTheme.warning,
                                              fontWeight: FontWeight.w500),
                                        ),
                                      ),
                                    ],
                                  ),
                                )
                              else
                                StockLevelWidget(
                                  productId: widget.product!.id,
                                  onViewHistory: () => Navigator.push(
                                    context,
                                    MaterialPageRoute(
                                      builder: (_) => StockHistoryPage(
                                        productId: widget.product!.id,
                                        productName: widget.product!.name,
                                      ),
                                    ),
                                  ),
                                ),
                              const SizedBox(height: 24),
                            ],

                          // Supplier section — only visible when editing an existing product
                          if (widget.isEditing) ...[
                            _SectionHeader(
                              icon: Icons.local_shipping_rounded,
                              title: 'Fournisseur',
                              color: AppTheme.warning,
                            ),
                            const SizedBox(height: 12),
                            _SupplierTile(productId: widget.product!.id),
                            const SizedBox(height: 24),
                          ],

                          // Photo section
                          _PhotoSection(
                            selectedImage: _selectedImage,
                            existingPhotoUrl: _removeExistingPhoto
                                ? null
                                : widget.product?.photoUrl,
                            compressing: _compressingPhoto,
                            onTap: _showPhotoOptions,
                            onRemove: () => setState(() {
                              if (_selectedImage != null) {
                                _selectedImage = null;
                              } else {
                                _removeExistingPhoto = true;
                              }
                            }),
                          ),
                          
                          const SizedBox(height: 40),
                          
                          // Save button
                          _SaveButton(
                            isSaving: _isSaving,
                            isEditing: widget.isEditing,
                            onPressed: _onSave,
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
    );
  }
}

// Component widgets
class _SectionHeader extends StatelessWidget {
  final IconData icon;
  final String title;
  final Color color;

  const _SectionHeader({
    required this.icon,
    required this.title,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: color.withOpacity(0.1),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(icon, color: color, size: 20),
        ),
        const SizedBox(width: 12),
        Flexible(
          child: Text(
            title,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.bold,
              color: color,
            ),
            overflow: TextOverflow.ellipsis,
            maxLines: 1,
          ),
        ),
      ],
    );
  }
}

class _ModernTextField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final String? hint;
  final IconData? icon;
  final TextInputType? keyboardType;
  final String? Function(String?)? validator;
  final String? suffix;
  final List<TextInputFormatter>? inputFormatters;
  final int? maxLines;
  final bool required;

  const _ModernTextField({
    required this.controller,
    required this.label,
    this.hint,
    this.icon,
    this.keyboardType,
    this.validator,
    this.suffix,
    this.inputFormatters,
    this.maxLines,
    this.required = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // Hérite du style de champ global de l'app (fond doux, bordure arrondie
    // — déjà défini une seule fois dans AppTheme._inputDecoration) plutôt
    // que de redéfinir sa propre bordure/remplissage en local : cohérence
    // garantie avec le reste de l'app, moins de code à maintenir ici.
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      validator: validator,
      inputFormatters: inputFormatters,
      maxLines: maxLines ?? 1,
      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
      decoration: InputDecoration(
        labelText: required ? '$label *' : label,
        hintText: hint,
        suffixText: suffix,
        prefixIcon: icon != null ? Icon(icon, size: 20) : null,
        labelStyle: TextStyle(
          color: required
              ? theme.colorScheme.primary
              : theme.colorScheme.onSurfaceVariant,
          fontWeight: required ? FontWeight.w600 : FontWeight.w500,
        ),
      ),
    );
  }
}

class _CategoryDropdown extends StatelessWidget {
  final String? selectedId;
  final List<dynamic> categories;
  final ValueChanged<String?> onChanged;

  const _CategoryDropdown({
    this.selectedId,
    required this.categories,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    // Même logique que _ModernTextField : hérite du style de champ global.
    return DropdownButtonFormField<String>(
      value: selectedId,
      isExpanded: true,
      decoration: const InputDecoration(
        labelText: 'Catégorie',
        prefixIcon: Icon(Icons.category_outlined, size: 20),
      ),
      hint: Text(
        'Choisir...',
        style: TextStyle(
          color: theme.colorScheme.onSurfaceVariant.withOpacity(0.6),
        ),
        overflow: TextOverflow.ellipsis,
      ),
      items: [
        DropdownMenuItem<String>(
          value: null,
          child: Text(
            'Aucune catégorie',
            style: TextStyle(
              color: theme.colorScheme.onSurfaceVariant.withOpacity(0.7),
              fontStyle: FontStyle.italic,
            ),
            overflow: TextOverflow.ellipsis,
          ),
        ),
        ...categories.map((cat) => DropdownMenuItem<String>(
          value: cat.id,
          child: Text(
            cat.name,
            style: const TextStyle(fontWeight: FontWeight.w500),
            overflow: TextOverflow.ellipsis,
            maxLines: 1,
          ),
        )),
      ],
      onChanged: onChanged,
      dropdownColor: theme.colorScheme.surface,
      borderRadius: BorderRadius.circular(16),
    );
  }
}

class _LoadingDropdown extends StatelessWidget {
  final String label;

  const _LoadingDropdown({required this.label});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    return Container(
      height: 60,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: theme.colorScheme.outline.withOpacity(0.2),
        ),
      ),
      child: Row(
        children: [
          const Icon(Icons.category_outlined, size: 22),
          const SizedBox(width: 16),
          Expanded(
            child: Text(
              'Chargement des catégories...',
              style: TextStyle(
                color: theme.colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w500,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              valueColor: AlwaysStoppedAnimation(theme.colorScheme.primary),
            ),
          ),
        ],
      ),
    );
  }
}

class _ErrorDropdown extends StatelessWidget {
  final String label;

  const _ErrorDropdown({required this.label});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    return Container(
      height: 60,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: theme.colorScheme.errorContainer.withOpacity(0.1),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: theme.colorScheme.error.withOpacity(0.3),
        ),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline_rounded, size: 22, color: theme.colorScheme.error),
          const SizedBox(width: 16),
          Expanded(
            child: Text(
              'Erreur de chargement des catégories',
              style: TextStyle(
                color: theme.colorScheme.error,
                fontWeight: FontWeight.w500,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

class _PhotoSection extends StatelessWidget {
  final Uint8List? selectedImage;
  final String? existingPhotoUrl;
  final bool compressing;
  final VoidCallback onTap;
  final VoidCallback onRemove;

  const _PhotoSection({
    this.selectedImage,
    this.existingPhotoUrl,
    this.compressing = false,
    required this.onTap,
    required this.onRemove,
  });

  bool get _hasPhoto => selectedImage != null || 
      (existingPhotoUrl != null && existingPhotoUrl!.isNotEmpty);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    return Container(
      decoration: BoxDecoration(
        border: Border.all(
          color: theme.colorScheme.outline.withOpacity(0.2),
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          if (_hasPhoto)
            ClipRRect(
              borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
              child: SizedBox(
                width: double.infinity,
                height: 160,
                child: selectedImage != null
                    ? Image.memory(selectedImage!, fit: BoxFit.cover)
                    : _buildExistingImage(context),
              ),
            ),
          ListTile(
            contentPadding: const EdgeInsets.all(20),
            leading: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppTheme.softTint(Theme.of(context).brightness),
                borderRadius: const BorderRadius.all(Radius.circular(12)),
              ),
              child: Icon(
                Icons.photo_camera_outlined,
                color: theme.colorScheme.primary,
                size: 24,
              ),
            ),
            title: Text(
              _hasPhoto ? 'Photo sélectionnée' : 'Ajouter une photo',
              style: const TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 16,
              ),
            ),
            subtitle: compressing
                ? const Text('Préparation de la photo…')
                : selectedImage != null
                ? Text(
                    'Taille : ${(selectedImage!.length / 1024).toStringAsFixed(1)} Ko',
                    style: TextStyle(
                      color: AppTheme.success,
                      fontWeight: FontWeight.w500,
                    ),
                    overflow: TextOverflow.ellipsis,
                    maxLines: 1,
                  ) 
                : Text(
                    _hasPhoto
                        ? 'Appuyez pour changer la photo'
                        : 'Appuyez pour choisir une photo de votre produit',
                    style: TextStyle(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                    overflow: TextOverflow.ellipsis,
                    maxLines: 2,
                  ),
            trailing: _hasPhoto
                ? IconButton(
                    icon: Icon(
                      Icons.clear_rounded,
                      color: theme.colorScheme.error,
                    ),
                    onPressed: onRemove,
                    tooltip: 'Supprimer la photo',
                  )
                : Icon(
                    Icons.add_photo_alternate_rounded,
                    color: theme.colorScheme.primary,
                  ),
            onTap: onTap,
          ),
        ],
      ),
    );
  }

  Widget _buildExistingImage(BuildContext context) {
    return ProductPhoto(
      photoUrl: existingPhotoUrl,
      placeholder: Center(
        child: Icon(Icons.broken_image_rounded,
            size: 40, color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    );
  }
}

class _PhotoOptionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  /// Texte/icône sur [color] (`onPrimary` / `onSecondary`).
  final Color onColor;
  final VoidCallback onTap;

  const _PhotoOptionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.color,
    required this.onColor,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: color,
              borderRadius: BorderRadius.circular(16),
              boxShadow: [
                BoxShadow(
                  color: color.withOpacity(0.25),
                  blurRadius: 8,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: onColor.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    icon,
                    color: onColor,
                    size: 24,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: onColor,
                          fontWeight: FontWeight.w600,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: onColor.withValues(alpha: 0.9),
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.arrow_forward_ios_rounded,
                  color: onColor.withValues(alpha: 0.8),
                  size: 18,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SaveButton extends StatelessWidget {
  final bool isSaving;
  final bool isEditing;
  final VoidCallback onPressed;

  const _SaveButton({
    required this.isSaving,
    required this.isEditing,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    // Couleur pleine + ombre douce plutôt qu'un dégradé — cohérent avec le
    // reste du rebrand (CTA de l'onboarding, FAB du catalogue, etc.).
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: isSaving ? theme.colorScheme.outline : theme.colorScheme.primary,
        borderRadius: BorderRadius.circular(16),
        boxShadow: isSaving ? [] : [
          BoxShadow(
            color: theme.colorScheme.primary.withOpacity(0.25),
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
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          color: theme.colorScheme.onPrimary,
                          strokeWidth: 2,
                        ),
                      ),
                      const SizedBox(width: 16),
                      Flexible(
                        child: Text(
                          'Sauvegarde en cours...',
                          style: TextStyle(
                            color: theme.colorScheme.onPrimary,
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  )
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isEditing 
                            ? Icons.save_rounded 
                            : Icons.add_circle_rounded,
                        color: theme.colorScheme.onPrimary,
                        size: 24,
                      ),
                      const SizedBox(width: 12),
                      Flexible(
                        child: Text(
                          isEditing
                              ? 'Enregistrer les modifications'
                              : 'Créer le produit',
                          style: TextStyle(
                            color: theme.colorScheme.onPrimary,
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 0.5,
                          ),
                          overflow: TextOverflow.ellipsis,
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

/// Upper Case Text Formatter for SKU field
class UpperCaseTextFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    return TextEditingValue(
      text: newValue.text.toUpperCase(),
      selection: newValue.selection,
    );
  }
}

/// Displays the supplier linked to a product (edit mode only).
///
/// Uses [productSupplierProvider] to fetch the supplier via
/// GET /api/v1/products/{productId}/supplier. Shows a tap-to-navigate card
/// when a supplier is found, or a neutral chip when none is linked.
class _SupplierTile extends ConsumerWidget {
  final String productId;
  const _SupplierTile({required this.productId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final supplierAsync = ref.watch(productSupplierProvider(productId));
    final theme = Theme.of(context);

    return supplierAsync.when(
      loading: () => Container(
        height: 72,
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: theme.colorScheme.outline.withOpacity(0.2)),
        ),
        child: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, __) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: theme.colorScheme.errorContainer.withOpacity(0.15),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: theme.colorScheme.error.withOpacity(0.3)),
        ),
        child: Row(
          children: [
            Icon(Icons.error_outline_rounded, color: theme.colorScheme.error),
            const SizedBox(width: 12),
            Text(
              'Impossible de charger le fournisseur',
              style: TextStyle(color: theme.colorScheme.error),
            ),
          ],
        ),
      ),
      data: (supplier) => supplier == null
          ? _NoSupplierCard(theme: theme)
          : ProductSupplierCard(supplier: supplier, theme: theme),
    );
  }
}

class _NoSupplierCard extends StatelessWidget {
  final ThemeData theme;
  const _NoSupplierCard({required this.theme});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: theme.colorScheme.outline.withOpacity(0.2)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: AppTheme.warning.withOpacity(0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(
              Icons.local_shipping_outlined,
              color: AppTheme.warning,
              size: 20,
            ),
          ),
          const SizedBox(width: 12),
          Text(
            'Aucun fournisseur lié',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
              fontStyle: FontStyle.italic,
            ),
          ),
        ],
      ),
    );
  }
}

/// The product's linked supplier. Story 15.2 / 15.8 — the supplier sheet
/// (`/suppliers/:id`) belongs to CRM: when the module cannot be read the card
/// still shows the supplier but is not a link (no tap, no chevron).
@visibleForTesting
class ProductSupplierCard extends ConsumerWidget {
  final SupplierModel supplier;
  final ThemeData theme;
  const ProductSupplierCard(
      {super.key, required this.supplier, required this.theme});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final path = '/suppliers/${supplier.id}';
    final linked = isRouteAvailable(ref, path);
    return InkWell(
      onTap: linked ? () => context.push(path, extra: supplier) : null,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppTheme.warning.withOpacity(0.05),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppTheme.warning.withOpacity(0.3)),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: const BoxDecoration(
                color: AppTheme.warning,
                borderRadius: BorderRadius.all(Radius.circular(10)),
              ),
              child: const Icon(
                Icons.local_shipping_outlined,
                color: AppTheme.onWarning,
                size: 20,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    supplier.name,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 16,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (supplier.phone.isNotEmpty)
                    Text(
                      supplier.phone,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
            if (linked)
              Icon(
                Icons.arrow_forward_ios_rounded,
                size: 16,
                color: theme.colorScheme.onSurfaceVariant,
              ),
          ],
        ),
      ),
    );
  }
}
