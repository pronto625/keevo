import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../catalog/presentation/provider/category_provider.dart';
import '../../../identity/presentation/provider/tenant_preferences_provider.dart';

/// Page de test pour vérifier le chargement des catégories par secteur.
/// 
/// Cette page affiche:
/// - Les préférences du tenant (secteur)
/// - La liste des catégories correspondantes
/// - Un bouton de rafraîchissement
class CategoryDebugPage extends ConsumerWidget {
  const CategoryDebugPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantPrefsAsync = ref.watch(tenantPreferencesProvider);
    final categoriesAsync = ref.watch(categoriesProvider);
    final cs = Theme.of(context).colorScheme;
    final sectionTitleStyle = Theme.of(context)
        .textTheme
        .titleMedium
        ?.copyWith(fontWeight: FontWeight.w700);

    return Scaffold(
      appBar: AppBar(
        backgroundColor: cs.surface,
        title: Text(
          'Debug Catégories',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        actions: [
          SoftIconButton(
            icon: Icons.refresh_rounded,
            tooltip: 'Rafraîchir',
            onPressed: () {
              ref.invalidate(tenantPreferencesProvider);
              ref.invalidate(categoriesProvider);
            },
          ),
          const SizedBox(width: 12),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Section préférences tenant
            Container(
              width: double.infinity,
              decoration: AppTheme.softCardDecoration(cs),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Préférences Tenant',
                      style: sectionTitleStyle,
                    ),
                    const SizedBox(height: 8),
                    tenantPrefsAsync.when(
                      loading: () => const CircularProgressIndicator(),
                      error: (error, stack) => Text(
                        'Erreur: $error',
                        style: const TextStyle(color: AppTheme.errorColor),
                      ),
                      data: (prefs) {
                        if (prefs == null) {
                          return const Text('Aucune préférence (onboarding pas terminé)');
                        }
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Secteur: ${prefs.sectorType?.label ?? 'N/A'}'),
                            Text('API Code: ${prefs.sectorType?.apiCode ?? 'N/A'}'),
                            Text('Heure rapport: ${prefs.eodReportTime}'),
                            Text('Alertes stock: ${prefs.stockAlertEnabled}'),
                          ],
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
            
            const SizedBox(height: 16),
            
            // Section catégories
            Container(
              width: double.infinity,
              decoration: AppTheme.softCardDecoration(cs),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Catégories',
                      style: sectionTitleStyle,
                    ),
                    const SizedBox(height: 8),
                    categoriesAsync.when(
                      loading: () => const CircularProgressIndicator(),
                      error: (error, stack) => Text(
                        'Erreur catégories: $error',
                        style: const TextStyle(color: AppTheme.errorColor),
                      ),
                      data: (categories) {
                        if (categories.isEmpty) {
                          return const Text('Aucune catégorie disponible');
                        }
                        
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Nombre: ${categories.length}'),
                            const SizedBox(height: 8),
                            ...categories.take(10).map((cat) => Padding(
                              padding: const EdgeInsets.symmetric(vertical: 2),
                              child: Row(
                                children: [
                                  Icon(
                                    cat.isCustom ? Icons.star_rounded : Icons.category_outlined,
                                    size: 16,
                                    color: cat.isCustom ? AppTheme.warning : Theme.of(context).colorScheme.primary,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(child: Text(cat.name)),
                                  if (cat.parentId != null)
                                    const Icon(Icons.subdirectory_arrow_right_rounded, size: 16),
                                ],
                              ),
                            )),
                            if (categories.length > 10)
                              Text('... et ${categories.length - 10} autres'),
                          ],
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
            
            const SizedBox(height: 16),
            
            // Actions de test 
            Container(
              width: double.infinity,
              decoration: AppTheme.softCardDecoration(cs),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Actions de test',
                      style: sectionTitleStyle,
                    ),
                    const SizedBox(height: 8),
                    FilledButton(
                      onPressed: () async {
                        try {
                          await refreshCategories(ref);
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Synchronisation terminée')),
                          );
                        } catch (e) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(appErrorMessage(e))),
                          );
                        }
                      },
                      child: const Text('Synchroniser catégories'),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}