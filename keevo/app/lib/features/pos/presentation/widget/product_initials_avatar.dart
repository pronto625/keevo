import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';

/// kAvatarColors — 8 Material colors safe for white text overlay.
const kAvatarColors = [
  AppTheme.primary, // Indigo
  Color(0xFF1098AD), // Cyan
  Color(0xFF37B24D), // Green
  Color(0xFFE8590C), // Orange
  Color(0xFFF03E3E), // Red
  Color(0xFF7048E8), // Violet
  Color(0xFFAE3EC9), // Grape
  Color(0xFF1C7ED6), // Blue
];

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

    final color = kAvatarColors[name.hashCode.abs() % kAvatarColors.length];

    return CircleAvatar(
      radius: radius,
      backgroundColor: color,
      child: Text(
        initials,
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}
