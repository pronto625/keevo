import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../domain/model/sector_type.dart';
import '../widget/sector_tile.dart';

/// SectorSelectionPage — Step 1 of the onboarding wizard.
///
/// Presents all [SectorType] values as a 2-column grid.
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Votre type de boutique'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Choisissez votre secteur d\'activité',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 16),
              Expanded(
                child: GridView.count(
                  key: const Key('sectorGrid'),
                  crossAxisCount: 2,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  children: SectorType.values
                      .map(
                        (s) => SectorTile(
                          sector: s,
                          isSelected: _selected == s,
                          onTap: () => _onSectorTap(s),
                        ),
                      )
                      .toList(),
                ),
              ),
              const SizedBox(height: 16),
              FilledButton(
                key: const Key('continueButton'),
                onPressed: _selected != null ? _onContinue : null,
                child: const Text('Continuer'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
