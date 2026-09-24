import 'package:flutter/material.dart';

/// Minimal FR/EN segmented pill toggle.
///
/// In-memory only (no persisted locale preference — see onboarding story
/// UI-1). Shared between the onboarding Welcome screen and [AuthPage] so
/// the two first-launch screens that expose a language switch stay
/// visually identical.
class LangToggle extends StatelessWidget {
  final bool isFrench;
  final ValueChanged<bool> onChanged;

  const LangToggle({super.key, required this.isFrench, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest.withAlpha(120),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _LangSegment(label: 'FR', selected: isFrench, onTap: () => onChanged(true)),
          _LangSegment(label: 'EN', selected: !isFrench, onTap: () => onChanged(false)),
        ],
      ),
    );
  }
}

class _LangSegment extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _LangSegment({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: selected ? cs.primary : Colors.transparent,
          borderRadius: BorderRadius.circular(17),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : cs.onSurfaceVariant,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            fontSize: 12,
          ),
        ),
      ),
    );
  }
}
