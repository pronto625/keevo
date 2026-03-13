import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../domain/model/csv_import_result.dart';
import '../provider/csv_import_provider.dart';

/// CsvImportPage — CSV bulk import wizard (AC1, AC2, AC3, AC4, AC5).
///
/// 3-step flow:
///   Step 0 — Choisir un fichier (file picker + template download)
///   Step 1 — Correspondance des colonnes (mapping dropdowns + preview)
///   Step 2 — Résultat (import stats + errors + CTA)
class CsvImportPage extends ConsumerStatefulWidget {
  const CsvImportPage({super.key});

  @override
  ConsumerState<CsvImportPage> createState() => _CsvImportPageState();
}

class _CsvImportPageState extends ConsumerState<CsvImportPage> {
  final _pageController = PageController();

  // Column mapping: field key → selected header (null = Ignorer)
  final Map<String, String?> _mapping = {
    'nameColumn': null,
    'priceColumn': null,
    'buyPriceColumn': null,
    'transportCostColumn': null,
    'categoryColumn': null,
    'skuColumn': null,
    'quantityColumn': null,
    'thresholdColumn': null,
  };

  static const _fieldLabels = {
    'nameColumn': 'Nom du produit *',
    'priceColumn': 'Prix de vente *',
    'buyPriceColumn': "Prix d'achat",
    'transportCostColumn': 'Coût de transport',
    'categoryColumn': 'Catégorie',
    'skuColumn': 'SKU',
    'quantityColumn': 'Quantité initiale',
    'thresholdColumn': 'Seuil minimum',
  };

  static const _requiredFields = {'nameColumn', 'priceColumn'};

  bool get _canLaunchImport =>
      _mapping['nameColumn'] != null && _mapping['priceColumn'] != null;

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _goToStep(int step) {
    _pageController.animateToPage(
      step,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeInOut,
    );
  }

  Future<void> _launchImport() async {
    // Build columnMapping: only non-null mappings
    final columnMapping = <String, String>{};
    _mapping.forEach((field, header) {
      if (header != null) columnMapping[field] = header;
    });

    await ref
        .read(csvImportNotifierProvider.notifier)
        .runImport(mapping: columnMapping);

    // On success, transition to step 2
    final s = ref.read(csvImportNotifierProvider);
    if (s is CsvImportSuccess) {
      _goToStep(2);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final importState = ref.watch(csvImportNotifierProvider);

    // Listen for errors → show SnackBar
    ref.listen<CsvImportState>(csvImportNotifierProvider, (prev, next) {
      if (next is CsvImportError) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.message),
            backgroundColor: theme.colorScheme.error,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
      // Transition to step 1 when file is picked
      if (next is CsvImportFilePicked) {
        _goToStep(1);
      }
    });

    return Scaffold(
      appBar: AppBar(
        title: const Text('Importer un catalogue CSV'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () {
            ref.read(csvImportNotifierProvider.notifier).reset();
            context.pop();
          },
        ),
      ),
      body: PageView(
        controller: _pageController,
        physics: const NeverScrollableScrollPhysics(),
        children: [
          _StepPickFile(
            onPickFile: () =>
                ref.read(csvImportNotifierProvider.notifier).pickFile(),
            onDownloadTemplate: () => _downloadTemplate(context),
          ),
          if (importState is CsvImportFilePicked)
            _StepMapColumns(
              headers: importState.detectedHeaders,
              bytes: importState.bytes,
              mapping: _mapping,
              fieldLabels: _fieldLabels,
              requiredFields: _requiredFields,
              canLaunchImport: _canLaunchImport,
              isLoading: importState is CsvImportLoading,
              onMappingChanged: (field, header) =>
                  setState(() => _mapping[field] = header),
              onLaunchImport: _launchImport,
              onCancel: () {
                ref.read(csvImportNotifierProvider.notifier).reset();
                _goToStep(0);
              },
            )
          else
            const SizedBox.shrink(),
          if (importState is CsvImportSuccess)
            _StepResult(
              result: importState.result,
              onViewCatalog: () {
                ref.read(csvImportNotifierProvider.notifier).reset();
                context.go('/products');
              },
              onImportAnother: () {
                ref.read(csvImportNotifierProvider.notifier).reset();
                setState(() {
                  _mapping.updateAll((_, __) => null);
                });
                _goToStep(0);
              },
            )
          else
            const SizedBox.shrink(),
        ],
      ),
    );
  }

  Future<void> _downloadTemplate(BuildContext context) async {
    final path = await ref
        .read(csvImportNotifierProvider.notifier)
        .downloadTemplate();
    if (!mounted) return;
    if (path != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: const Text('Modèle enregistré dans Téléchargements'),
          behavior: SnackBarBehavior.floating,
          action: SnackBarAction(
            label: 'OK',
            onPressed: () {},
          ),
        ),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Impossible de télécharger le modèle — connexion requise'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }
}

// ── Step 0 — Choisir un fichier ───────────────────────────────────────────────

class _StepPickFile extends StatelessWidget {
  final VoidCallback onPickFile;
  final VoidCallback onDownloadTemplate;

  const _StepPickFile({
    required this.onPickFile,
    required this.onDownloadTemplate,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.upload_file_rounded,
            size: 80,
            color: theme.colorScheme.primary.withOpacity(0.6),
          ),
          const SizedBox(height: 24),
          Text(
            'Importez votre catalogue',
            style: theme.textTheme.headlineSmall
                ?.copyWith(fontWeight: FontWeight.bold),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          Text(
            'Sélectionnez un fichier CSV contenant vos produits.\nLes colonnes seront détectées automatiquement.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 40),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: onPickFile,
              icon: const Icon(Icons.folder_open_rounded),
              label: const Text('Choisir un fichier .csv'),
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: onDownloadTemplate,
              icon: const Icon(Icons.download_rounded),
              label: const Text('Télécharger le modèle CSV'),
            ),
          ),
          const SizedBox(height: 24),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceVariant.withOpacity(0.5),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Colonnes reconnues :',
                    style: theme.textTheme.labelMedium?.copyWith(
                        fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Text(
                  'nom, prix_vente, prix_achat, cout_transport,\ncategorie, sku, quantite_initiale, seuil_min',
                  style: theme.textTheme.bodySmall?.copyWith(
                    fontFamily: 'monospace',
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ── Step 1 — Correspondance des colonnes ──────────────────────────────────────

class _StepMapColumns extends StatelessWidget {
  final List<String> headers;
  final List<int> bytes;
  final Map<String, String?> mapping;
  final Map<String, String> fieldLabels;
  final Set<String> requiredFields;
  final bool canLaunchImport;
  final bool isLoading;
  final void Function(String field, String? header) onMappingChanged;
  final VoidCallback onLaunchImport;
  final VoidCallback onCancel;

  const _StepMapColumns({
    required this.headers,
    required this.bytes,
    required this.mapping,
    required this.fieldLabels,
    required this.requiredFields,
    required this.canLaunchImport,
    required this.isLoading,
    required this.onMappingChanged,
    required this.onLaunchImport,
    required this.onCancel,
  });

  List<List<String>> _previewRows() {
    final text = String.fromCharCodes(
        bytes.sublist(0, bytes.length.clamp(0, 8192)));
    final lines = text.split(RegExp(r'\r?\n')).where((l) => l.isNotEmpty).toList();
    // Skip header line, take up to 5 data rows
    return lines
        .skip(1)
        .take(5)
        .map((line) => line.split(',').map((v) => v.trim().replaceAll('"', '')).toList())
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final previewRows = _previewRows();
    final dropdownItems = [
      const DropdownMenuItem<String>(
        value: null,
        child: Text('— Ignorer —', style: TextStyle(color: Colors.grey)),
      ),
      ...headers.map(
        (h) => DropdownMenuItem<String>(value: h, child: Text(h)),
      ),
    ];

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Field mapping section
          Text('Correspondance des colonnes',
              style: theme.textTheme.titleLarge
                  ?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text(
            'Indiquez quelle colonne de votre fichier correspond à chaque champ Keevo.',
            style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 20),
          ...fieldLabels.entries.map((entry) {
            final isRequired = requiredFields.contains(entry.key);
            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: DropdownButtonFormField<String>(
                value: mapping[entry.key],
                decoration: InputDecoration(
                  labelText: entry.value,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  suffixIcon: isRequired
                      ? Icon(Icons.star, size: 12,
                          color: theme.colorScheme.error)
                      : null,
                ),
                items: dropdownItems,
                onChanged: (v) => onMappingChanged(entry.key, v),
              ),
            );
          }),

          // Preview table
          if (previewRows.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text('Aperçu (5 premières lignes)',
                style: theme.textTheme.titleMedium
                    ?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                headingRowColor: WidgetStateProperty.all(
                    theme.colorScheme.surfaceVariant.withOpacity(0.5)),
                columns: headers
                    .map((h) => DataColumn(label: Text(h)))
                    .toList(),
                rows: previewRows
                    .map((row) => DataRow(
                          cells: List.generate(
                            headers.length,
                            (i) => DataCell(
                              Text(i < row.length ? row[i] : ''),
                            ),
                          ),
                        ))
                    .toList(),
              ),
            ),
          ],

          const SizedBox(height: 24),

          if (isLoading) const LinearProgressIndicator(),

          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: canLaunchImport && !isLoading ? onLaunchImport : null,
              icon: isLoading
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.upload_rounded),
              label: Text(isLoading ? 'Import en cours…' : 'Lancer l\'import'),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: TextButton(
              onPressed: onCancel,
              child: const Text('Annuler'),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Step 2 — Résultat ─────────────────────────────────────────────────────────

class _StepResult extends StatelessWidget {
  final CsvImportResult result;
  final VoidCallback onViewCatalog;
  final VoidCallback onImportAnother;

  const _StepResult({
    required this.result,
    required this.onViewCatalog,
    required this.onImportAnother,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Success banner
          Center(
            child: Column(
              children: [
                Icon(
                  result.imported > 0
                      ? Icons.check_circle_rounded
                      : Icons.info_rounded,
                  size: 64,
                  color: result.imported > 0
                      ? Colors.green
                      : theme.colorScheme.tertiary,
                ),
                const SizedBox(height: 12),
                Text(
                  result.imported > 0
                      ? '🎉 ${result.imported} produit${result.imported > 1 ? 's' : ''} importé${result.imported > 1 ? 's' : ''}'
                      : 'Aucun produit importé',
                  style: theme.textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: result.imported > 0 ? Colors.green : null,
                  ),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Stats row
          if (result.skipped > 0)
            _StatCard(
              icon: Icons.warning_amber_rounded,
              color: theme.colorScheme.tertiary,
              label:
                  '⚠ ${result.skipped} ligne${result.skipped > 1 ? 's ignorées' : ' ignorée'}',
            ),

          // Limit reached: upgrade CTA
          if (result.limitReached) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.errorContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '🔒 Limite du plan gratuit atteinte',
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: theme.colorScheme.error,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    result.message ??
                        'Passez au plan Premium pour importer plus de produits.',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],

          // Errors list
          if (result.errors.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(
              'Lignes ignorées (${result.errors.length})',
              style: theme.textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            ...result.errors.map(
              (e) => Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  'Ligne ${e.line} — ${e.column}: ${e.message}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
              ),
            ),
          ],

          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: onViewCatalog,
              icon: const Icon(Icons.inventory_2_rounded),
              label: const Text('Voir le catalogue'),
            ),
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: TextButton(
              onPressed: onImportAnother,
              child: const Text('Importer un autre fichier'),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;

  const _StatCard({
    required this.icon,
    required this.color,
    required this.label,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 8),
          Text(label,
              style: TextStyle(color: color, fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }
}
