import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'product_initials_avatar.dart';

/// ProductCard — displays a product in the POS grid.
/// Modern card design: image fills the top zone, info bar at bottom.
class ProductCard extends StatefulWidget {
  final String name;
  final int price;
  final int stockQuantity;
  final String? photoUrl;
  final String? categoryName;
  final VoidCallback onTap;
  final VoidCallback? onVerifierStock;

  const ProductCard({
    super.key,
    required this.name,
    required this.price,
    required this.stockQuantity,
    this.photoUrl,
    this.categoryName,
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
    final avatarColor =
        kAvatarColors[widget.name.hashCode.abs() % kAvatarColors.length];

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
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.06),
              blurRadius: 10,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(16),
          child: InkWell(
            onTap: isOutOfStock ? null : _handleTap,
            borderRadius: BorderRadius.circular(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ── Zone 1: Image / Initials — fills the top ──
                Expanded(
                  flex: 3,
                  child: ClipRRect(
                    borderRadius: const BorderRadius.vertical(
                      top: Radius.circular(16),
                    ),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        // Background: image or gradient with initials
                        if (widget.photoUrl != null &&
                            widget.photoUrl!.isNotEmpty)
                          _buildImage(widget.photoUrl!)
                        else
                          _buildInitialsBackground(avatarColor),

                        // Dim overlay when out of stock
                        if (isOutOfStock)
                          Container(
                            color: Colors.black.withValues(alpha: 0.35),
                          ),

                        // Out-of-stock cross icon
                        if (isOutOfStock)
                          Positioned(
                            top: 6,
                            right: 6,
                            child: Container(
                              padding: const EdgeInsets.all(3),
                              decoration: BoxDecoration(
                                color: const Color(0xFFFA5252),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Icon(Icons.close_rounded,
                                  size: 12, color: Colors.white),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),

                // ── Zone 2: Info bar — compact bottom section ──
                Expanded(
                  flex: 2,
                  child: Padding(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Category label
                        if (widget.categoryName != null)
                          Text(
                            widget.categoryName!.toUpperCase(),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: Colors.grey.shade500,
                              fontSize: 7,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 0.3,
                            ),
                          ),
                        // Product name
                        Text(
                          widget.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            fontWeight: FontWeight.w600,
                            fontSize: 10,
                            height: 1.2,
                            letterSpacing: -0.1,
                          ),
                        ),
                        const SizedBox(height: 1),
                        // Price
                        Text(
                          _currencyFormat.format(widget.price),
                          style: theme.textTheme.bodySmall?.copyWith(
                            fontWeight: FontWeight.w800,
                            color: const Color(0xFF3B5BDB),
                            fontSize: 11,
                            height: 1.1,
                          ),
                        ),
                        const SizedBox(height: 2),
                        // Stock badge
                        _buildStockBadge(),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInitialsBackground(Color color) {
    final initials = widget.name
        .trim()
        .split(' ')
        .take(2)
        .where((w) => w.isNotEmpty)
        .map((w) => w[0].toUpperCase())
        .join();

    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            color,
            color.withValues(alpha: 0.7),
          ],
        ),
      ),
      child: Center(
        child: Text(
          initials,
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
            fontSize: 20,
            letterSpacing: 1,
          ),
        ),
      ),
    );
  }

  Widget _buildStockBadge() {
    final isOutOfStock = widget.stockQuantity <= 0;
    if (isOutOfStock) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
        decoration: BoxDecoration(
          color: const Color(0xFFFA5252).withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(6),
        ),
        child: const Text(
          'Rupture',
          style: TextStyle(
            color: Color(0xFFFA5252),
            fontSize: 9,
            fontWeight: FontWeight.w600,
          ),
        ),
      );
    }
    final isLow = widget.stockQuantity <= 5;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: isLow
            ? const Color(0xFFFCC419).withValues(alpha: 0.15)
            : const Color(0xFF51CF66).withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        '${widget.stockQuantity} en stock',
        style: TextStyle(
          color: isLow ? const Color(0xFFE67700) : const Color(0xFF2B8A3E),
          fontSize: 9,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _buildImage(String url) {
    if (url.startsWith('/')) {
      final file = File(url);
      if (file.existsSync()) {
        return Image.file(file, fit: BoxFit.cover);
      }
    }
    return Image.network(
      url,
      fit: BoxFit.cover,
      errorBuilder: (_, __, ___) {
        final color =
            kAvatarColors[widget.name.hashCode.abs() % kAvatarColors.length];
        return _buildInitialsBackground(color);
      },
    );
  }
}
