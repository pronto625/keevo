import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/inventory_product_row_model.dart';
import '../provider/inventory_counting_provider.dart';

/// UX2 color tokens — exact hex from design spec.
const _colorSuccess = Color(0xFF51CF66);
const _colorWarning = Color(0xFFFCC419);
const _colorError = Color(0xFFFA5252);

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
  Timer? _debounce;
  int? _localPhysical;

  @override
  void initState() {
    super.initState();
    _localPhysical = widget.product.physicalQty;
    _controller = TextEditingController(
      text: widget.product.physicalQty?.toString() ?? '',
    );
  }

  @override
  void didUpdateWidget(covariant InventoryRow oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.product.physicalQty != widget.product.physicalQty &&
        widget.product.physicalQty != _localPhysical) {
      _localPhysical = widget.product.physicalQty;
      _controller.text = widget.product.physicalQty?.toString() ?? '';
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    final parsed = int.tryParse(value);
    setState(() => _localPhysical = parsed);

    _debounce?.cancel();
    if (parsed == null) return;

    _debounce = Timer(const Duration(milliseconds: 400), () {
      ref.read(saveCountNotifierProvider.notifier).save(
            sessionId: widget.sessionId,
            productId: widget.product.productId,
            variantId: widget.product.variantId,
            productName: widget.product.productName,
            variantLabel: widget.product.variantLabel,
            theoretical: widget.product.theoreticalQty,
            physical: parsed,
          );
    });
  }

  int? get _ecart =>
      _localPhysical != null ? _localPhysical! - widget.product.theoreticalQty : null;

  Color _backgroundColor(ThemeData theme) {
    if (_localPhysical == null) return theme.colorScheme.surface;
    final diff = _localPhysical! - widget.product.theoreticalQty;
    if (diff == 0) return const Color(0xFFE8F5E9); // green.shade50
    if (diff > 0) return const Color(0xFFFFFDE7); // yellow.shade50
    return const Color(0xFFFFEBEE); // red.shade50
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
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: false),
                  textAlign: TextAlign.center,
                  decoration: InputDecoration(
                    hintText: '—',
                    isDense: true,
                    contentPadding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
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
    if (product.photoUrl != null && product.photoUrl!.isNotEmpty) {
      return CircleAvatar(
        radius: 24,
        backgroundImage: NetworkImage(product.photoUrl!),
      );
    }
    // Deterministic color from productId
    final colorIndex = product.productId.hashCode.abs() % Colors.primaries.length;
    final bgColor = Colors.primaries[colorIndex];
    final initials = _initials(product.productName);
    return CircleAvatar(
      radius: 24,
      backgroundColor: bgColor,
      child: Text(
        initials,
        style: const TextStyle(
            color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
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
