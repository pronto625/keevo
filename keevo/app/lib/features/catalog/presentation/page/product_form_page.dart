import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;

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
  File? _selectedImage;
  bool _isSaving = false;

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
      builder: (context) => Container(
        margin: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          borderRadius: BorderRadius.circular(28),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.1),
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
              
              // Camera option
              _PhotoOptionTile(
                icon: Icons.camera_alt_rounded,
                title: 'Prendre une photo',
                subtitle: 'Utiliser l\'appareil photo',
                gradient: LinearGradient(
                  colors: [
                    Colors.purple.shade400,
                    Colors.purple.shade700,
                  ],
                ),
                onTap: () => _pickImage(ImageSource.camera),
              ),
              
              const SizedBox(height: 16),
              
              // Gallery option
              _PhotoOptionTile(
                icon: Icons.photo_library_rounded,
                title: 'Choisir depuis la galerie',
                subtitle: 'Sélectionner une image existante',
                gradient: LinearGradient(
                  colors: [
                    Colors.blue.shade400,
                    Colors.blue.shade700,
                  ],
                ),
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
    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: source, 
      maxWidth: 1024,
      maxHeight: 1024,
      imageQuality: 80,
    );
    if (image != null) {
      setState(() => _selectedImage = File(image.path));
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

  /// Copies the selected image to the app's persistent documents directory.
  /// Returns the local file path, or null if no image selected.
  Future<String?> _persistImage(String productId) async {
    if (_selectedImage == null) return null;
    final dir = await getApplicationDocumentsDirectory();
    final imgDir = Directory(p.join(dir.path, 'product_images'));
    if (!imgDir.existsSync()) imgDir.createSync(recursive: true);
    final ext = p.extension(_selectedImage!.path).isNotEmpty
        ? p.extension(_selectedImage!.path)
        : '.jpg';
    final dest = File(p.join(imgDir.path, '$productId$ext'));
    await _selectedImage!.copy(dest.path);
    return dest.path;
  }

  Future<void> _onSave() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      final actions = ref.read(productActionsProvider);
      
      if (widget.isEditing && widget.product != null) {
        final photoUrl = await _persistImage(widget.product!.id);
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
          photoUrl: photoUrl,
        );
      } else {
        // Generate a temporary ID for the image filename; after create, the
        // real product ID is returned and the image path is already persisted.
        final tempId = DateTime.now().millisecondsSinceEpoch.toString();
        final photoUrl = await _persistImage(tempId);
        await actions.create(
          name: _nameController.text.trim(),
          description: _descriptionController.text.trim().isEmpty 
              ? null 
              : _descriptionController.text.trim(),
          sku: _skuController.text.trim(),
          categoryId: _selectedCategoryId,
          price: int.tryParse(_priceController.text) ?? 0,
          buyPrice: int.tryParse(_buyPriceController.text) ?? 0,
          transportCost: int.tryParse(_transportCostController.text) ?? 0,
          photoUrl: photoUrl,
        );
      }

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                Icon(
                  Icons.check_circle_rounded, 
                  color: Colors.white,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Text(
                  widget.isEditing ? 'Produit modifié avec succès !' : 'Produit créé avec succès !',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ],
            ),
            backgroundColor: Colors.green.shade600,
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
                  color: Colors.white,
                  size: 20,
                ),
                const SizedBox(width: 12),
                Expanded(child: Text(e.message)),
              ],
            ),
            backgroundColor: Colors.red.shade600,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                const Icon(Icons.error_rounded, color: Colors.white, size: 20),
                const SizedBox(width: 12),
                Expanded(child: Text('Erreur inattendue : $e')),
              ],
            ),
            backgroundColor: Colors.red.shade900,
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

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // Modern App Bar with hero effect
          SliverAppBar(
            expandedHeight: 160,
            pinned: true,
            elevation: 0,
            backgroundColor: theme.colorScheme.primary,
            flexibleSpace: FlexibleSpaceBar(
              title: Text(
                widget.isEditing ? 'Modifier' : 'Nouveau produit',
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.5,
                ),
              ),
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      theme.colorScheme.primary,
                      theme.colorScheme.primary.withOpacity(0.8),
                      theme.colorScheme.secondary.withOpacity(0.6),
                    ],
                  ),
                ),
                child: Stack(
                  children: [
                    // Decorative circles
                    Positioned(
                      top: -50,
                      right: -30,
                      child: Container(
                        width: 120,
                        height: 120,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: Colors.white.withOpacity(0.1),
                        ),
                      ),
                    ),
                    Positioned(
                      top: 20,
                      left: -20,
                      child: Container(
                        width: 80,
                        height: 80,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: Colors.white.withOpacity(0.05),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            leading: IconButton(
              icon: Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.white.withOpacity(0.2),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(
                  Icons.arrow_back_ios_new_rounded, 
                  color: Colors.white,
                  size: 18,
                ),
              ),
              onPressed: () => context.pop(),
            ),
            actions: [
              IconButton(
                icon: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.white.withOpacity(0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.info_outline_rounded,
                    color: Colors.white,
                    size: 20,
                  ),
                ),
                onPressed: () {
                  showDialog(
                    context: context,
                    builder: (context) => AlertDialog(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(20),
                      ),
                      title: const Text('💡 Conseils'),
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
              const SizedBox(width: 8),
            ],
          ),
          
          // Form content
          SliverToBoxAdapter(
            child: FadeTransition(
              opacity: _fadeAnimation,
              child: SlideTransition(
                position: _slideAnimation,
                child: Container(
                  margin: const EdgeInsets.all(20),
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
                    padding: const EdgeInsets.all(24),
                    child: Form(
                      key: _formKey,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // Section: Basic Information
                          _SectionHeader(
                            icon: Icons.edit_rounded,
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
                                  label: 'Prix de vente',
                                  hint: '0',
                                  icon: Icons.sell_rounded,
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
                                  icon: Icons.shopping_cart_rounded,
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
                                color: Colors.teal,
                              ),
                              const SizedBox(height: 12),
                              if (widget.product?.status == ProductStatus.draft)
                                Container(
                                  padding: const EdgeInsets.all(12),
                                  decoration: BoxDecoration(
                                    color: Colors.orange.withOpacity(0.1),
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                        color: Colors.orange.withOpacity(0.3)),
                                  ),
                                  child: const Row(
                                    children: [
                                      Icon(Icons.info_outline,
                                          color: Colors.orange, size: 20),
                                      SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          'Validez ce brouillon pour gérer son stock.',
                                          style: TextStyle(
                                              color: Colors.orange,
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
                              color: Colors.orange,
                            ),
                            const SizedBox(height: 12),
                            _SupplierTile(productId: widget.product!.id),
                            const SizedBox(height: 24),
                          ],

                          // Photo section
                          _PhotoSection(
                            selectedImage: _selectedImage,
                            existingPhotoUrl: widget.product?.photoUrl,
                            onTap: _showPhotoOptions,
                            onRemove: () => setState(() => _selectedImage = null),
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
    
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      validator: validator,
      inputFormatters: inputFormatters,
      maxLines: maxLines ?? 1,
      style: const TextStyle(
        fontSize: 16, 
        fontWeight: FontWeight.w500,
        letterSpacing: 0.2,
      ),
      decoration: InputDecoration(
        labelText: required ? '$label *' : label,
        hintText: hint,
        suffixText: suffix,
        prefixIcon: icon != null 
            ? Padding(
                padding: const EdgeInsets.all(12),
                child: Icon(icon, size: 22),
              ) 
            : null,
        filled: true,
        fillColor: theme.colorScheme.surfaceVariant.withOpacity(0.3),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(
            color: theme.colorScheme.outline.withOpacity(0.2),
          ),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(
            color: theme.colorScheme.primary,
            width: 2,
          ),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: theme.colorScheme.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(
            color: theme.colorScheme.error,
            width: 2,
          ),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
        labelStyle: TextStyle(
          color: required 
              ? theme.colorScheme.primary 
              : theme.colorScheme.onSurfaceVariant,
          fontWeight: required ? FontWeight.w600 : FontWeight.w500,
        ),
        hintStyle: TextStyle(
          color: theme.colorScheme.onSurfaceVariant.withOpacity(0.6),
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
    
    return DropdownButtonFormField<String>(
      value: selectedId,
      isExpanded: true,
      decoration: InputDecoration(
        labelText: 'Catégorie',
        prefixIcon: const Padding(
          padding: EdgeInsets.all(8),
          child: Icon(Icons.category_rounded, size: 20),
        ),
        filled: true,
        fillColor: theme.colorScheme.surfaceVariant.withOpacity(0.3),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(
            color: theme.colorScheme.outline.withOpacity(0.2),
          ),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(
            color: theme.colorScheme.primary,
            width: 2,
          ),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 16),
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
        color: theme.colorScheme.surfaceVariant.withOpacity(0.3),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: theme.colorScheme.outline.withOpacity(0.2),
        ),
      ),
      child: Row(
        children: [
          const Icon(Icons.category_rounded, size: 22),
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
  final File? selectedImage;
  final String? existingPhotoUrl;
  final VoidCallback onTap;
  final VoidCallback onRemove;

  const _PhotoSection({
    this.selectedImage,
    this.existingPhotoUrl,
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
                    ? Image.file(selectedImage!, fit: BoxFit.cover)
                    : _buildExistingImage(),
              ),
            ),
          ListTile(
            contentPadding: const EdgeInsets.all(20),
            leading: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [
                    Colors.amber.shade400,
                    Colors.orange.shade600,
                  ],
                ),
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(
                Icons.photo_camera_rounded,
                color: Colors.white,
                size: 24,
              ),
            ),
            title: Text(
              _hasPhoto ? 'Photo sélectionnée ✓' : 'Ajouter une photo',
              style: const TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 16,
              ),
            ),
            subtitle: selectedImage != null 
                ? Text(
                    'Taille: ${(selectedImage!.lengthSync() / 1024).toStringAsFixed(1)} KB',
                    style: TextStyle(
                      color: Colors.green.shade600,
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

  Widget _buildExistingImage() {
    final url = existingPhotoUrl!;
    if (url.startsWith('/')) {
      final file = File(url);
      if (file.existsSync()) return Image.file(file, fit: BoxFit.cover);
    }
    return Image.network(url, fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => const Center(
          child: Icon(Icons.broken_image_rounded, size: 40, color: Colors.grey),
        ));
  }
}

class _PhotoOptionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Gradient gradient;
  final VoidCallback onTap;

  const _PhotoOptionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.gradient,
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
              gradient: gradient,
              borderRadius: BorderRadius.circular(16),
              boxShadow: [
                BoxShadow(
                  color: gradient.colors.first.withOpacity(0.3),
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
                    color: Colors.white.withOpacity(0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    icon,
                    color: Colors.white,
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
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w600,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: Colors.white.withOpacity(0.9),
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.arrow_forward_ios_rounded,
                  color: Colors.white.withOpacity(0.8),
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
    
    return Container(
      height: 56,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: isSaving 
              ? [Colors.grey.shade400, Colors.grey.shade600]
              : [
                  theme.colorScheme.primary,
                  theme.colorScheme.primary.withOpacity(0.8),
                ],
        ),
        borderRadius: BorderRadius.circular(16),
        boxShadow: isSaving ? [] : [
          BoxShadow(
            color: theme.colorScheme.primary.withOpacity(0.3),
            blurRadius: 12,
            offset: const Offset(0, 6),
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
                          color: Colors.white,
                          strokeWidth: 2,
                        ),
                      ),
                      const SizedBox(width: 16),
                      Flexible(
                        child: Text(
                          'Sauvegarde en cours...',
                          style: TextStyle(
                            color: Colors.white,
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
                        color: Colors.white,
                        size: 24,
                      ),
                      const SizedBox(width: 12),
                      Flexible(
                        child: Text(
                          isEditing 
                              ? 'Enregistrer les modifications'
                              : '🚀 Créer le produit',
                          style: const TextStyle(
                            color: Colors.white,
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
          color: theme.colorScheme.surfaceVariant.withOpacity(0.3),
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
          : _SupplierCard(supplier: supplier, theme: theme),
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
        color: theme.colorScheme.surfaceVariant.withOpacity(0.3),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: theme.colorScheme.outline.withOpacity(0.2)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.orange.withOpacity(0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(
              Icons.local_shipping_outlined,
              color: Colors.orange,
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

class _SupplierCard extends StatelessWidget {
  final SupplierModel supplier;
  final ThemeData theme;
  const _SupplierCard({required this.supplier, required this.theme});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: () => context.push('/suppliers/${supplier.id}', extra: supplier),
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.orange.withOpacity(0.05),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.orange.withOpacity(0.3)),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [Colors.orange.shade400, Colors.orange.shade700],
                ),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(
                Icons.local_shipping_rounded,
                color: Colors.white,
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
