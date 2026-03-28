import 'dart:async';

import 'package:flutter/material.dart';

/// InventoryValidationSuccessOverlay — full-screen animated success overlay.
///
/// Shows a green checkmark with ScaleTransition, "Inventaire validé" title,
/// "N ajustements appliqués" subtitle. Auto-dismisses after 1.5 seconds.
/// Story 6.4 — AC6.
class InventoryValidationSuccessOverlay extends StatefulWidget {
  final int adjustmentsApplied;
  final VoidCallback onComplete;

  const InventoryValidationSuccessOverlay({
    super.key,
    required this.adjustmentsApplied,
    required this.onComplete,
  });

  /// Show the overlay as a full-screen dialog.
  static void show(
    BuildContext context, {
    required int adjustmentsApplied,
    required VoidCallback onComplete,
  }) {
    showDialog(
      context: context,
      barrierDismissible: false,
      barrierColor: Colors.black54,
      builder: (_) => InventoryValidationSuccessOverlay(
        adjustmentsApplied: adjustmentsApplied,
        onComplete: onComplete,
      ),
    );
  }

  @override
  State<InventoryValidationSuccessOverlay> createState() =>
      _InventoryValidationSuccessOverlayState();
}

class _InventoryValidationSuccessOverlayState
    extends State<InventoryValidationSuccessOverlay>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _scaleAnimation;
  Timer? _autoDismissTimer;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 600),
      vsync: this,
    );
    _scaleAnimation = CurvedAnimation(
      parent: _controller,
      curve: Curves.elasticOut,
    );
    _controller.forward();

    _autoDismissTimer = Timer(const Duration(milliseconds: 1500), () {
      if (mounted) {
        Navigator.of(context).pop();
        widget.onComplete();
      }
    });
  }

  @override
  void dispose() {
    _autoDismissTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Center(
      child: Material(
        color: Colors.transparent,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ScaleTransition(
              scale: _scaleAnimation,
              child: Container(
                width: 100,
                height: 100,
                decoration: const BoxDecoration(
                  color: Color(0xFF40C057),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.check,
                  color: Colors.white,
                  size: 56,
                ),
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'Inventaire validé',
              style: theme.textTheme.headlineSmall?.copyWith(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '${widget.adjustmentsApplied} ajustement${widget.adjustmentsApplied > 1 ? 's' : ''} appliqué${widget.adjustmentsApplied > 1 ? 's' : ''}',
              style: theme.textTheme.bodyLarge?.copyWith(
                color: Colors.white70,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
