import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

/// ProductInitialsAvatar — CircleAvatar displaying product name initials.
class ProductInitialsAvatar extends StatelessWidget {
  final String name;
  final double radius;

  const ProductInitialsAvatar({
    super.key,
    required this.name,
    this.radius = 24,
  });

  @override
  Widget build(BuildContext context) {
    final initials = name
        .trim()
        .split(' ')
        .take(2)
        .where((w) => w.isNotEmpty)
        .map((w) => w[0].toUpperCase())
        .join();

    final color = AppTheme.avatarPalette[name.hashCode.abs() % AppTheme.avatarPalette.length];

    return CircleAvatar(
      radius: radius,
      backgroundColor: color,
      child: Text(
        initials,
        style: const TextStyle(
          color: AppTheme.onBrand,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}
