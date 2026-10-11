import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Action button drawn as a pale brand pastille — the soft/modern
/// replacement for white "glass" icon buttons, which only read on top of a
/// saturated gradient header.
///
/// With [loading], the icon is replaced by a progress indicator and the
/// button is disabled; the tooltip is still announced to screen readers.
class SoftIconButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final double? iconSize;
  final bool loading;

  const SoftIconButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.iconSize,
    this.loading = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        borderRadius: const BorderRadius.all(Radius.circular(14)),
      ),
      child: IconButton(
        icon: loading
            ? SizedBox(
                width: iconSize ?? 18,
                height: iconSize ?? 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: theme.colorScheme.primary,
                ),
              )
            : Icon(icon, color: theme.colorScheme.primary, size: iconSize),
        tooltip: tooltip,
        onPressed: loading ? null : onPressed,
      ),
    );
  }
}
