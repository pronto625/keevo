// DEPRECATED: replaced by PosPage in Story 4.1 — kept for rollback only
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../features/sync_indicator/presentation/widget/sync_indicator.dart';
import '../../../onboarding/domain/model/sector_type.dart';

/// PosPlaceholderPage — Temporary POS landing page.
///
/// Displayed after the onboarding wizard completes (or on subsequent logins).
/// First visit: reads [kSectorTypeKey] from SharedPreferences to show the correct
/// sector emoji in the empty state (AC6), and shows a tutorial [SnackBar] once (AC7).
///
/// Story 1.5 (AC3): [SyncIndicator] added to AppBar actions for always-visible
/// connectivity status.
class PosPlaceholderPage extends ConsumerStatefulWidget {
  const PosPlaceholderPage({super.key});

  @override
  ConsumerState<PosPlaceholderPage> createState() => _PosPlaceholderPageState();
}

class _PosPlaceholderPageState extends ConsumerState<PosPlaceholderPage> {
  SectorType? _sectorType;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    final code = prefs.getString(kSectorTypeKey);
    if (code != null && mounted) {
      setState(() {
        _sectorType = SectorType.values.firstWhere(
          (s) => s.apiCode == code,
          orElse: () => SectorType.other,
        );
      });
    }
    await _maybeShowTutorial(prefs);
  }

  Future<void> _maybeShowTutorial(SharedPreferences prefs) async {
    final shown = prefs.getBool(kPosTutorialShownKey) ?? false;
    if (!shown && mounted) {
      // Mark shown BEFORE the frame callback so a quick unmount doesn't re-show
      await prefs.setBool(kPosTutorialShownKey, true);
      // Show tutorial hint after the first frame is built (AC7)
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            key: Key('tutorialSnackBar'),
            content: Text(
              '💡 Tutoriel Keevo POS disponible — '
              'Ajouter votre premier produit pour commencer !',
            ),
            duration: Duration(seconds: 5),
          ),
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final sector = _sectorType;
    final role = ref.watch(currentUserRoleProvider);
    final isEmployee = role == 'EMPLOYEE';
    return Scaffold(
      appBar: AppBar(
        title: const Text('Point de Vente'),
        // Story 1.5 AC3: SyncIndicator always visible in AppBar trailing position
        actions: const [SyncIndicator()],
      ),
      body: Center(
        // AC6: Empty state with sector emoji/illustration + CTA
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                sector?.emoji ?? '📊',
                style: const TextStyle(fontSize: 72),
              ),
              const SizedBox(height: 24),
              Text(
                'Votre boutique est prête !',
                style: Theme.of(context).textTheme.titleLarge,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                isEmployee
                    ? 'Aucun produit configuré. Contactez votre responsable.'
                    : 'Commencez par ajouter vos produits pour activer la caisse.',
                style: Theme.of(context).textTheme.bodyMedium,
                textAlign: TextAlign.center,
              ),
              if (!isEmployee) ...[
                const SizedBox(height: 32),
                FilledButton.icon(
                  key: const Key('addFirstProductCta'),
                  onPressed: () => context.go('/products'),
                  icon: const Icon(Icons.add),
                  label: const Text('Ajouter votre premier produit'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
