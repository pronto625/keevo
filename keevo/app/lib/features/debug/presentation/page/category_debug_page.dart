import '../../../../core/theme/app_theme.dart';
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('Debug Catégories'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              ref.invalidate(tenantPreferencesProvider);
              ref.invalidate(categoriesProvider);
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Section préférences tenant
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Préférences Tenant',
                      style: Theme.of(context).textTheme.titleMedium,
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
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Catégories',
                      style: Theme.of(context).textTheme.titleMedium,
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
                                    cat.isCustom ? Icons.star : Icons.category,
                                    size: 16,
                                    color: cat.isCustom ? AppTheme.warning : Theme.of(context).colorScheme.primary,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(child: Text(cat.name)),
                                  if (cat.parentId != null)
                                    const Icon(Icons.subdirectory_arrow_right, size: 16),
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
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Actions de test',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    ElevatedButton(
                      onPressed: () async {
                        try {
                          await refreshCategories(ref);
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Synchronisation terminée')),
                          );
                        } catch (e) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text('Erreur: $e')),
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