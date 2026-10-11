import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/sector_type.dart';

/// Static, presentation-only captions for each [SectorType] — purely
/// cosmetic (two-line list row, soft/modern rebrand 2026-09-15), not part
/// of the domain model.
const Map<SectorType, String> _sectorSubtitles = {
  SectorType.clothing: 'Prêt-à-porter, chaussures, accessoires',
  SectorType.electronics: 'Téléphones, ordinateurs, accessoires',
  SectorType.booksStationery: 'Livres, fournitures scolaires',
  SectorType.homeAppliances: 'Gros et petit électroménager',
  SectorType.foodGrocery: 'Alimentation générale, boissons',
  SectorType.pharmacy: 'Médicaments, parapharmacie',
  SectorType.hardware: 'Outillage, matériaux de construction',
  SectorType.other: 'Un autre secteur d\'activité',
};

/// SectorTile — A tappable list row representing a single [SectorType].
///
/// Soft/modern rebrand (2026-09-15) : rangée pleine largeur (icône +
/// titre/sous-titre + case à cocher), inspirée d'un pattern de sélection
/// de préférences moderne — remplace l'ancienne tuile de grille 2 colonnes.
class SectorTile extends StatelessWidget {
  final SectorType sector;
  final bool isSelected;
  final VoidCallback onTap;

  const SectorTile({
    super.key,
    required this.sector,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        border: Border.all(
          color: isSelected ? cs.primary : cs.outlineVariant.withAlpha(90),
          width: isSelected ? 1.5 : 1,
        ),
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
          key: Key('sectorTile_${sector.apiCode}'),
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: AppTheme.softTint(Theme.of(context).brightness),
                    shape: BoxShape.circle,
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    sector.emoji,
                    style: const TextStyle(fontSize: 22),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        sector.label,
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                              fontWeight: FontWeight.w600,
                              color: cs.onSurface,
                            ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _sectorSubtitles[sector] ?? '',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: cs.onSurfaceVariant,
                            ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                _SelectionIndicator(selected: isSelected),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SelectionIndicator extends StatelessWidget {
  final bool selected;

  const _SelectionIndicator({required this.selected});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      width: 24,
      height: 24,
      decoration: BoxDecoration(
        color: selected ? cs.primary : Colors.transparent,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(
          color: selected ? cs.primary : cs.outlineVariant,
          width: 1.5,
        ),
      ),
      child: selected
          ? const Icon(Icons.check_rounded, size: 16, color: Colors.white)
          : null,
    );
  }
}
