import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Round icon badge on a diluted background — the soft/modern replacement
/// for saturated or gradient-filled icon squares.
///
/// Without [color] it uses the brand tint and primary icon. With a semantic
/// [color] (success, warning, error…) the background is that color diluted,
/// so the badge stays legible in both light and dark mode.
class SoftIconBadge extends StatelessWidget {
  final IconData icon;
  final Color? color;
  final double size;

  const SoftIconBadge({
    super.key,
    required this.icon,
    this.color,
    this.size = 40,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = color;
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: accent == null
            ? AppTheme.softTint(theme.brightness)
            : accent.withValues(alpha: 0.12),
        shape: BoxShape.circle,
      ),
      child: Icon(
        icon,
        size: size * 0.5,
        color: accent ?? theme.colorScheme.primary,
      ),
    );
  }
}
