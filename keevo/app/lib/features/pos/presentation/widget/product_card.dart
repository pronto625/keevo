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

class _ProductCardState extends State<ProductCard> {
  bool _flashing = false;

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  void _handleTap() {
    widget.onTap();
    setState(() => _flashing = true);
    Future.delayed(const Duration(milliseconds: 150), () {
      if (mounted) setState(() => _flashing = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final isOutOfStock = widget.stockQuantity <= 0;

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: isOutOfStock ? null : _handleTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          color: _flashing
              ? const Color(0xFFD0EBFF)
              : Theme.of(context).cardColor,
          padding: const EdgeInsets.all(8),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (widget.photoUrl != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.network(
                    widget.photoUrl!,
                    width: 48,
                    height: 48,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) =>
                        ProductInitialsAvatar(name: widget.name),
                  ),
                )
              else
                ProductInitialsAvatar(name: widget.name),
              const SizedBox(height: 4),
              Text(
                widget.name,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 2),
              Text(
                _currencyFormat.format(widget.price),
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
              ),
              const SizedBox(height: 4),
              if (isOutOfStock)
                ActionChip(
                  label: const Text('⚠️ Rupture'),
                  backgroundColor: Colors.red.shade100,
                  labelStyle: TextStyle(
                    color: Colors.red.shade800,
                    fontSize: 11,
                  ),
                  onPressed: widget.onVerifierStock,
                )
              else
                Chip(
                  label: Text('Stock: ${widget.stockQuantity}'),
                  visualDensity: VisualDensity.compact,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
