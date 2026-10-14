import '../../../../core/theme/app_theme.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/inventory_product_row_model.dart';
import '../provider/inventory_counting_provider.dart';
import '../../../../core/widget/product_photo.dart';

/// UX2 color tokens — exact hex from design spec.
const _colorSuccess = AppTheme.success;
const _colorWarning = AppTheme.warning;
const _colorError = AppTheme.errorColor;

/// InventoryRow — a single product row in the counting form (UX32).
///
/// Displays product info, theoretical stock, physical input field,
/// écart badge, and variant chip. Debounced save on change.
///
/// Story 6.2.
class InventoryRow extends ConsumerStatefulWidget {
  final InventoryProductRowModel product;
  final String sessionId;

  const InventoryRow({
    super.key,
    required this.product,
    required this.sessionId,
  });

  @override
  ConsumerState<InventoryRow> createState() => _InventoryRowState();
}

class _InventoryRowState extends ConsumerState<InventoryRow> {
  late final TextEditingController _controller;
  final _focusNode = FocusNode();
  Timer? _debounce;
  int? _localPhysical;

  /// Last count successfully saved. A saved count cannot be erased (the API
  /// requires a quantity), so an emptied field falls back to it.
  int? _savedPhysical;

  @override
  void initState() {
    super.initState();
    _localPhysical = widget.product.physicalQty;
    _savedPhysical = widget.product.physicalQty;
    _controller = TextEditingController(
      text: widget.product.physicalQty?.toString() ?? '',
    );
    _focusNode.addListener(_restoreSavedCountOnBlur);
  }

  void _restoreSavedCountOnBlur() {
    if (_focusNode.hasFocus || _savedPhysical == null) return;
    if (_controller.text.trim().isEmpty) {
      _controller.text = '$_savedPhysical';
    }
  }

  @override
  void didUpdateWidget(covariant InventoryRow oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.product.physicalQty != widget.product.physicalQty &&
        widget.product.physicalQty != _localPhysical) {
      _localPhysical = widget.product.physicalQty;
      _savedPhysical = widget.product.physicalQty;
      _controller.text = widget.product.physicalQty?.toString() ?? '';
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    final parsed = int.tryParse(value);
    // Empty field: nothing to save, keep showing what is recorded.
    setState(() => _localPhysical = parsed ?? _savedPhysical);

    _debounce?.cancel();
    if (parsed == null) return;

    _debounce = Timer(const Duration(milliseconds: 400), () async {
      try {
        await ref.read(saveCountNotifierProvider.notifier).save(
              sessionId: widget.sessionId,
              productId: widget.product.productId,
              variantId: widget.product.variantId,
              productName: widget.product.productName,
              variantLabel: widget.product.variantLabel,
              theoretical: widget.product.theoreticalQty,
              physical: parsed,
            );
        // Only a saved count becomes the fallback of an emptied field.
        _savedPhysical = parsed;
      } catch (_) {
        // The save notifier holds the error state; the field keeps the
        // previous saved count as its fallback.
      }
    });
  }

  int? get _ecart =>
      _localPhysical != null ? _localPhysical! - widget.product.theoreticalQty : null;

  Color _backgroundColor(ThemeData theme) {
    if (_localPhysical == null) return theme.colorScheme.surface;
    final diff = _localPhysical! - widget.product.theoreticalQty;
    // Teintes sémantiques diluées plutôt que des pastels opaques (illisibles
    // en mode sombre). Posées sur `surface` pour que l'animation de couleur
    // reste opaque.
    final Color tint;
    if (diff == 0) {
      tint = _colorSuccess;
    } else if (diff > 0) {
      tint = _colorWarning;
    } else {
      tint = _colorError;
    }
    return Color.alphaBlend(
        tint.withValues(alpha: 0.08), theme.colorScheme.surface);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final product = widget.product;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      color: _backgroundColor(theme),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            // Avatar (48dp = radius 24)
            _buildAvatar(product),
            const SizedBox(width: 12),

            // Product info (expanded)
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    product.productName,
                    style: theme.textTheme.bodyLarge
                        ?.copyWith(fontWeight: FontWeight.w600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Text(
                        'Keevo : ${product.theoreticalQty}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                      if (product.variantLabel != null) ...[
                        const SizedBox(width: 6),
                        Chip(
                          label: Text(product.variantLabel!),
                          visualDensity: VisualDensity.compact,
                          materialTapTargetSize:
                              MaterialTapTargetSize.shrinkWrap,
                          padding: EdgeInsets.zero,
                          labelPadding:
                              const EdgeInsets.symmetric(horizontal: 6),
                          labelStyle: theme.textTheme.labelSmall,
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(width: 8),

            // Physical input with Semantics (H4)
            Semantics(
              label: 'Quantité physique pour ${product.productName}',
              child: SizedBox(
                width: 72,
                child: TextFormField(
                  controller: _controller,
                  focusNode: _focusNode,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: false),
                  textAlign: TextAlign.center,
                  decoration: const InputDecoration(
                    hintText: '—',
                    isDense: true,
                    contentPadding:
                        EdgeInsets.symmetric(horizontal: 8, vertical: 10),
                  ),
                  onChanged: _onChanged,
                ),
              ),
            ),

            const SizedBox(width: 8),

            // Écart badge with Semantics (H4)
            _buildEcartBadge(theme),
          ],
        ),
      ),
    );
  }

  Widget _buildAvatar(InventoryProductRowModel product) {
    // Deterministic color from productId
    final colorIndex = product.productId.hashCode.abs() % AppTheme.avatarPalette.length;
    final accent = AppTheme.avatarPalette[colorIndex];
    final initials = _initials(product.productName);
    // Même teinte par produit, mais diluée (badge rond soft) plutôt qu'en
    // aplat saturé.
    final placeholder = CircleAvatar(
      radius: 24,
      backgroundColor: accent.withValues(alpha: 0.15),
      child: Text(
        initials,
        style: TextStyle(
            color: AppTheme.avatarInitials(accent, Theme.of(context).brightness),
            fontWeight: FontWeight.bold,
            fontSize: 14),
      ),
    );
    // Photo du produit (fichier local, sinon copie de la photo serveur),
    // les initiales sinon.
    return ClipOval(
      child: SizedBox(
        width: 48,
        height: 48,
        child: ProductPhoto(photoUrl: product.photoUrl, placeholder: placeholder),
      ),
    );
  }

  Widget _buildEcartBadge(ThemeData theme) {
    final ecart = _ecart;
    if (ecart == null) {
      return const SizedBox(width: 48);
    }

    final Color badgeColor;
    final String label;
    final String semanticLabel;
    if (ecart == 0) {
      badgeColor = _colorSuccess;
      label = '= 0';
      semanticLabel = 'Écart: concordant';
    } else if (ecart > 0) {
      badgeColor = _colorWarning;
      label = '+$ecart';
      semanticLabel = 'Écart: surplus de $ecart';
    } else {
      badgeColor = _colorError;
      label = '$ecart';
      semanticLabel = 'Écart: pénurie de ${ecart.abs()}';
    }

    return Semantics(
      label: semanticLabel,
      child: Container(
        width: 48,
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
        decoration: BoxDecoration(
          color: badgeColor.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          label,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: badgeColor,
            fontWeight: FontWeight.bold,
            fontSize: 13,
          ),
        ),
      ),
    );
  }

  static String _initials(String name) {
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.length >= 2) {
      return '${parts[0][0]}${parts[1][0]}'.toUpperCase();
    }
    return name.isNotEmpty ? name[0].toUpperCase() : '?';
  }
}
