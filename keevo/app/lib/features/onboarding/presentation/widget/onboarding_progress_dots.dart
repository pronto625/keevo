import 'package:flutter/material.dart';

/// Minimal step indicator shared across the 4 first-launch screens
/// (Welcome → Terms → Sector → Shop name).
///
/// Purely decorative — ties the soft/modern rebrand (2026-09-12) together
/// across screens owned by different pages/providers, so it takes a plain
/// [step]/[total] pair instead of reading any shared state.
class OnboardingProgressDots extends StatelessWidget {
  final int step;
  final int total;

  const OnboardingProgressDots({
    super.key,
    required this.step,
    required this.total,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(total, (i) {
        final active = i == step - 1;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
          margin: const EdgeInsets.symmetric(horizontal: 3),
          width: active ? 20 : 6,
          height: 6,
          decoration: BoxDecoration(
            color: active ? cs.primary : cs.primary.withAlpha(40),
            borderRadius: BorderRadius.circular(3),
          ),
        );
      }),
    );
  }
}
