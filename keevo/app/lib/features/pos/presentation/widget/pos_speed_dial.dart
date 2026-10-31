import 'package:keevo_core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../provider/day_closure_providers.dart';
import 'day_close_success_overlay.dart';
import 'day_summary_bottom_sheet.dart';

/// PosSpeedDial — Speed dial FAB menu for POS actions.
///
/// Groups two main actions:
/// 1. Day closure button (moon icon)
/// 2. Create draft product (+ icon)
///
/// Uses a Column layout so all children are within widget bounds
/// and properly receive hit tests (unlike Stack + overflow).
/// Story 4.4 — Improved UX for multiple floating actions.
class PosSpeedDial extends StatefulWidget {
  final String? storeId;
  final VoidCallback onCreateDraft;

  const PosSpeedDial({
    super.key,
    required this.storeId,
    required this.onCreateDraft,
  });

  @override
  State<PosSpeedDial> createState() => _PosSpeedDialState();
}

class _PosSpeedDialState extends State<PosSpeedDial>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _rotationAnimation;
  bool _isOpen = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 250),
      vsync: this,
    );
    _rotationAnimation = Tween<double>(begin: 0.0, end: 0.125).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _toggle() {
    setState(() {
      _isOpen = !_isOpen;
      if (_isOpen) {
        _controller.forward();
      } else {
        _controller.reverse();
      }
    });
  }

  void _close() {
    if (_isOpen) {
      setState(() {
        _isOpen = false;
        _controller.reverse();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        // Speed dial options — wrapped in AnimatedSize for smooth expand/collapse
        AnimatedSize(
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOutBack,
          alignment: Alignment.bottomCenter,
          child: _isOpen
              ? Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      // Day Close option
                      Consumer(
                        builder: (context, ref, _) {
                          final userIdAsync =
                              ref.watch(currentUserIdProvider);
                          final effectiveStoreId =
                              widget.storeId ?? 'default';
                          final closureState = ref.watch(
                              dayClosureStateProvider(effectiveStoreId));
                          return userIdAsync.when(
                            data: (userId) {
                              if (userId == null) return const SizedBox.shrink();
                              final isClosed = closureState.value ==
                                  DayClosureState.closed;
                              return _SpeedDialOption(
                                icon: isClosed
                                    ? Icons.check_circle_rounded
                                    : Icons.nightlight_round,
                                label: isClosed
                                    ? 'Clôturée'
                                    : 'Clôturer',
                                backgroundColor: isClosed
                                    ? Theme.of(context).colorScheme.onSurfaceVariant
                                    : AppTheme.violetDeep,
                                // v1s-16-17 — foreground per fill (AA).
                                foregroundColor: isClosed
                                    ? Theme.of(context).colorScheme.surface
                                    : AppTheme.onBrand,
                                onTap: isClosed
                                    ? () {
                                        _close();
                                      }
                                    : () {
                                        _close();
                                        _showDayCloseSummary(
                                          context,
                                          ref,
                                          effectiveStoreId,
                                          userId,
                                        );
                                      },
                              );
                            },
                            loading: () => const SizedBox.shrink(),
                            error: (_, __) => const SizedBox.shrink(),
                          );
                        },
                      ),
                      const SizedBox(height: 16),
                      // Create Draft option
                      _SpeedDialOption(
                        icon: Icons.add_rounded,
                        label: 'Produit',
                        backgroundColor: AppTheme.warning,
                        foregroundColor: AppTheme.onWarning,
                        onTap: () {
                          _close();
                          widget.onCreateDraft();
                        },
                      ),
                    ],
                  ),
                )
              : const SizedBox.shrink(),
        ),

        // Main FAB (always visible, always at bottom)
        FloatingActionButton(
          heroTag: 'pos_speed_dial_main',
          backgroundColor:
              _isOpen ? Theme.of(context).colorScheme.onSurface : AppTheme.primary,
          onPressed: _toggle,
          child: AnimatedBuilder(
            animation: _rotationAnimation,
            builder: (context, child) {
              return Transform.rotate(
                angle: _rotationAnimation.value * 3.14159 * 2,
                // Ouvert, le fond passe en onSurface (clair en mode sombre) :
                // l'icône prend la couleur de surface pour rester visible.
                child: Icon(
                  _isOpen ? Icons.close_rounded : Icons.more_vert_rounded,
                  color: _isOpen
                      ? Theme.of(context).colorScheme.surface
                      : AppTheme.onBrand,
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Future<void> _showDayCloseSummary(
    BuildContext context,
    WidgetRef ref,
    String storeId,
    String actorId,
  ) async {
    final closed = await DaySummaryBottomSheet.show(
      context: context,
      ref: ref,
      storeId: storeId,
      actorId: actorId,
    );

    if (!mounted) return;

    // Show success overlay using the SpeedDial's own context (still valid)
    if (closed) {
      await DayCloseSuccessOverlay.show(this.context);
    }
  }
}

/// _SpeedDialOption — Individual action button in the speed dial menu.
class _SpeedDialOption extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color backgroundColor;

  /// Icon colour matching [backgroundColor] (AA ≥ 3:1, v1s-16-17).
  final Color foregroundColor;
  final VoidCallback onTap;

  const _SpeedDialOption({
    required this.icon,
    required this.label,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(28),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Label
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: cs.surface,
                borderRadius: BorderRadius.circular(AppTheme.radiusS),
                boxShadow: AppTheme.softShadow(cs),
              ),
              child: Text(
                label,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: cs.onSurface,
                ),
              ),
            ),
            const SizedBox(width: 12),
            // Circular icon button — ombre neutre douce, pas d'ombre colorée.
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: backgroundColor,
                shape: BoxShape.circle,
                boxShadow: AppTheme.softShadow(cs),
              ),
              child: Icon(
                icon,
                color: foregroundColor,
                size: 24,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
