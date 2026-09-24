import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/sector_type.dart';
import '../widget/onboarding_progress_dots.dart';
import '../widget/sector_tile.dart';

/// SectorSelectionPage — Step 3 of the onboarding wizard.
///
/// Presents all [SectorType] values as a single-column selectable list
/// (icon + title/subtitle + checkbox), soft/modern rebrand (2026-09-15).
/// "Continuer" FilledButton is disabled until the user selects a sector.
/// On tap it pushes `/onboarding/shop-name` with the chosen sector as extra.
class SectorSelectionPage extends ConsumerStatefulWidget {
  const SectorSelectionPage({super.key});

  @override
  ConsumerState<SectorSelectionPage> createState() =>
      _SectorSelectionPageState();
}

class _SectorSelectionPageState extends ConsumerState<SectorSelectionPage> {
  SectorType? _selected;

  void _onSectorTap(SectorType sector) {
    setState(() => _selected = sector);
  }

  void _onContinue() {
    if (_selected == null) return;
    context.push('/onboarding/shop-name', extra: _selected);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Center(child: OnboardingProgressDots(step: 3, total: 4)),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 20, 24, 4),
                child: Text(
                  'Votre secteur\nd\'activité',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: cs.onSurface,
                        height: 1.15,
                        letterSpacing: -0.3,
                      ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
                child: Text(
                  'Choisissez ce qui décrit le mieux votre commerce',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: cs.onSurfaceVariant,
                      ),
                ),
              ),
              Expanded(
                child: ListView.separated(
                  key: const Key('sectorGrid'),
                  padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
                  itemCount: SectorType.values.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, i) {
                    final s = SectorType.values[i];
                    return SectorTile(
                      sector: s,
                      isSelected: _selected == s,
                      onTap: () => _onSectorTap(s),
                    );
                  },
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 20),
                child: SizedBox(
                  height: 56,
                  child: FilledButton(
                    key: const Key('continueButton'),
                    onPressed: _selected != null ? _onContinue : null,
                    style: FilledButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(28),
                      ),
                    ),
                    child: const Text(
                      'Continuer',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
