import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'product_initials_avatar.dart';

/// ProductCard — displays a product in the POS grid.
class ProductCard extends StatefulWidget {
  final String name;
  final int price;
  final int stockQuantity;
  final String? photoUrl;
  final VoidCallback onTap;
  final VoidCallback? onVerifierStock;

  const ProductCard({
    super.key,
    required this.name,
    required this.price,
    required this.stockQuantity,
    this.photoUrl,
    required this.onTap,
    this.onVerifierStock,
  });

  @override
  State<ProductCard> createState() => _ProductCardState();
}

class _ProductCardState extends State<ProductCard>
    with SingleTickerProviderStateMixin {
  late final AnimationController _scaleCtrl;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  @override
  void initState() {
    super.initState();
    _scaleCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 100),
      lowerBound: 0.0,
      upperBound: 0.04,
    );
  }

  @override
  void dispose() {
    _scaleCtrl.dispose();
    super.dispose();
  }

  void _handleTap() {
    widget.onTap();
    _scaleCtrl.forward().then((_) => _scaleCtrl.reverse());
  }

  @override
  Widget build(BuildContext context) {
    final isOutOfStock = widget.stockQuantity <= 0;
    final theme = Theme.of(context);

    return AnimatedBuilder(
      animation: _scaleCtrl,
      builder: (context, child) => Transform.scale(
        scale: 1.0 - _scaleCtrl.value,
        child: child,
      ),
      child: Container(
        decoration: BoxDecoration(
          color: theme.colorScheme.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: theme.colorScheme.outlineVariant.withValues(alpha: 0.5),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.04),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(16),
          child: InkWell(
            onTap: isOutOfStock ? null : _handleTap,
            borderRadius: BorderRadius.circular(16),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  // Product image or initials
                  Stack(
                    children: [
                      if (widget.photoUrl != null)
                        ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: _buildImage(
                            widget.photoUrl!,
                            width: 52,
                            height: 52,
                          ),
                        )
                      else
                        ProductInitialsAvatar(name: widget.name, radius: 26),
                      if (isOutOfStock)
                        Positioned(
                          right: -2,
                          top: -2,
                          child: Container(
                            padding: const EdgeInsets.all(2),
                            decoration: const BoxDecoration(
                              color: Color(0xFFFA5252),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(Icons.close, size: 10, color: Colors.white),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  // Product name
                  Text(
                    widget.name,
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontWeight: FontWeight.w500,
                      height: 1.2,
                    ),
                  ),
                  const SizedBox(height: 4),
                  // Price
                  Text(
                    _currencyFormat.format(widget.price),
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: const Color(0xFF3B5BDB),
                    ),
                  ),
                  const SizedBox(height: 8),
                  // Stock indicator
                  if (isOutOfStock)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFA5252).withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Text(
                        'Rupture',
                        style: TextStyle(
                          color: Color(0xFFFA5252),
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    )
                  else
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: widget.stockQuantity <= 5
                            ? const Color(0xFFFCC419).withValues(alpha: 0.15)
                            : const Color(0xFF51CF66).withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        '${widget.stockQuantity} en stock',
                        style: TextStyle(
                          color: widget.stockQuantity <= 5
                              ? const Color(0xFFE67700)
                              : const Color(0xFF2B8A3E),
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildImage(String url, {required double width, required double height}) {
    if (url.startsWith('/')) {
      final file = File(url);
      if (file.existsSync()) {
        return Image.file(file, width: width, height: height, fit: BoxFit.cover);
      }
    }
    return Image.network(
      url, width: width, height: height, fit: BoxFit.cover,
      errorBuilder: (_, __, ___) =>
          ProductInitialsAvatar(name: widget.name, radius: width / 2),
    );
  }
}
