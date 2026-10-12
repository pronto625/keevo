import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../data/local/local_export_reader.dart';
import '../../domain/exception/export_exception.dart';
import '../../domain/local/local_export_schema.dart';
import '../../domain/model/export_models.dart';
import '../provider/export_providers.dart';
import '../provider/local_export_providers.dart';
import '../widget/export_form_widgets.dart';

/// LocalExportPage — « Données de cet appareil » (v1s-18-5, OWNER only).
///
/// CSV built on this phone from the local Drift data, without network: same
/// columns, French labels and cell formats as the server CSV. Datasets with
/// no local row are shown disabled. The file is written to a temp folder
/// and handed to the share sheet.
/// Route: /exports/local (extra: [ExportPrefill] from the « Exporter »
/// screen).
class LocalExportPage extends ConsumerStatefulWidget {
  final ExportPrefill? prefill;

  const LocalExportPage({super.key, this.prefill});

  @override
  ConsumerState<LocalExportPage> createState() => _LocalExportPageState();
}

class _LocalExportPageState extends ConsumerState<LocalExportPage> {
  late Set<String> _selected;
  late String? _storeId;
  late DateTime? _from;
  late DateTime? _to;
  late ExportPeriod? _period;

  /// Audit log criteria of the prefill: not applied locally (note shown).
  late bool _hasCriteria;

  bool _busy = false;
  int _rows = 0;
  ExportException? _error;

  @override
  void initState() {
    super.initState();
    final p = widget.prefill;
    final known = {for (final d in localExportCatalog) d.key};
    _selected = {...?p?.datasets}..retainWhere(known.contains);
    _storeId = p?.storeId;
    _from = p?.from;
    _to = p?.to;
    _period = (_from != null || _to != null) ? ExportPeriod.custom : null;
    _hasCriteria = (p?.criteria ?? const <String, String>{})
        .values
        .any((v) => v.trim().isNotEmpty);
  }

  // ── Selection ──────────────────────────────────────────────────────────────

  /// Catalogue datasets that have rows on this device.
  List<LocalExportDataset> _available(Map<String, int> counts) => [
        for (final d in localExportCatalog)
          if ((counts[d.key] ?? 0) > 0) d,
      ];

  /// Selected datasets that can be exported (catalogue order).
  List<LocalExportDataset> _effective(Map<String, int> counts) => [
        for (final d in _available(counts))
          if (_selected.contains(d.key)) d,
      ];

  void _toggle(String key, bool on) => setState(() {
        on ? _selected.add(key) : _selected.remove(key);
        _error = null;
      });

  void _toggleAll(List<LocalExportDataset> available) => setState(() {
        final all = available.every((d) => _selected.contains(d.key));
        if (all) {
          _selected.removeAll(available.map((d) => d.key));
        } else {
          _selected.addAll(available.map((d) => d.key));
        }
        _error = null;
      });

  Future<void> _pickPeriod() async {
    final picked =
        await pickExportPeriod(context, current: _period, from: _from, to: _to);
    if (picked == null || !mounted) return;
    setState(() {
      _error = null;
      _period = picked.period;
      _from = picked.from;
      _to = picked.to;
    });
  }

  Future<void> _pickStore(List<ExportStoreOption> stores) async {
    final choice = await showExportOptionSheet<String>(
      context,
      title: 'Boutique',
      selected: _storeId,
      allLabel: 'Toutes les boutiques',
      options: [for (final s in stores) (s.id, s.label)],
    );
    if (choice != null && mounted) {
      setState(() {
        _storeId = choice.value;
        _error = null;
      });
    }
  }

  // ── Generation ─────────────────────────────────────────────────────────────

  Future<void> _generate(Map<String, int> counts) async {
    if (_busy) return;
    final datasets = _effective(counts);
    if (datasets.isEmpty) return;
    setState(() {
      _busy = true;
      _rows = 0;
      _error = null;
    });
    try {
      final dir = await ref.read(exportTempDirProvider.future);
      var written = 0;
      final file = await ref.read(localExportServiceProvider).export(
        datasets: {for (final d in datasets) d.key},
        filter: LocalExportFilter(storeId: _storeId, from: _from, to: _to),
        tempDir: dir,
        onProgress: (rows) {
          written = rows;
          if (mounted) setState(() => _rows = rows);
        },
      );
      // Left the page meanwhile: never leave the sensitive file behind.
      if (!mounted) {
        await _discard(file, dir);
        return;
      }
      if (written == 0) {
        // Counts are unfiltered totals: the filters may match nothing.
        await _discard(file, dir);
        if (!mounted) return;
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(const SnackBar(
            content: Text(
                'Aucune donnée ne correspond à ces filtres sur cet appareil')));
        return;
      }
      await ref.read(exportFileSharerProvider)(file);
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(const SnackBar(content: Text('Fichier prêt')));
    } on ExportException catch (e) {
      if (mounted) setState(() => _error = e);
    } catch (_) {
      if (mounted) setState(() => _error = ExportException.localFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Best effort: deletes the generated file's own folder
  /// `<temp>/exports/<µs>/` (never anything outside `<temp>/exports/`).
  static Future<void> _discard(File file, Directory tempDir) async {
    try {
      final folder = file.parent;
      final exportsDir = '${tempDir.path}${Platform.pathSeparator}exports';
      if (folder.parent.path != exportsDir) return;
      if (await folder.exists()) await folder.delete(recursive: true);
    } catch (_) {}
  }

  String _errorMessage(ExportException e) => e.isStorage
      ? 'Espace de stockage insuffisant ou inaccessible'
      : 'Impossible de générer le fichier';

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final countsAsync = ref.watch(localExportCountsProvider);
    final counts = countsAsync.valueOrNull ?? const <String, int>{};
    final stores =
        ref.watch(exportStoreOptionsProvider).valueOrNull ?? const [];
    final effective = _effective(counts);
    final canGenerate = !_busy && countsAsync.hasValue && effective.isNotEmpty;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: Navigator.of(context).canPop()
            ? IconButton(
                icon: Icon(Icons.arrow_back_ios_new_rounded,
                    color: cs.onSurface, size: 20),
                tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                onPressed: () => Navigator.maybePop(context),
              )
            : null,
        title: Text(
          'Données de cet appareil',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: FilledButton.icon(
            key: const Key('local-export-submit'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
              ),
            ),
            onPressed: canGenerate ? () => _generate(counts) : null,
            icon: _busy
                ? SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: cs.onPrimary),
                  )
                : const Icon(Icons.ios_share_rounded),
            label: Text(_busy ? 'Génération… $_rows lignes' : 'Générer le CSV'),
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          const ExportInfoBanner(
            key: Key('local-export-scope-note'),
            icon: Icons.phone_android_rounded,
            text: 'Export généré sur ce téléphone, sans connexion. Il reflète '
                'les données présentes sur cet appareil — y compris celles '
                'pas encore envoyées — et peut être incomplet (historique des '
                'ventes et mouvements limité aux 30 derniers jours, journal '
                'limité à ce qui a été synchronisé).',
          ),
          if (_error != null)
            _LocalErrorCard(
              message: _errorMessage(_error!),
              onRetry: _busy || !countsAsync.hasValue
                  ? null
                  : () => _generate(counts),
            ),
          if (_hasCriteria && _selected.contains('audit_log'))
            const ExportInfoBanner(
              key: Key('local-export-criteria-note'),
              icon: Icons.info_outline_rounded,
              text: 'Les critères du journal (employé, action, objet, '
                  'recherche) ne s\'appliquent pas à l\'export local : tout '
                  'le journal présent sur l\'appareil sera exporté',
            ),
          const ExportSectionTitle('Quoi'),
          ...countsAsync.when(
            skipLoadingOnRefresh: true,
            loading: () => const [
              Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              ),
            ],
            error: (err, _) => [
              AppErrorWidget(
                error: err,
                title: 'Données de l\'appareil illisibles',
                onRetry: () => ref.invalidate(localExportCountsProvider),
              ),
            ],
            data: _datasetTiles,
          ),
          const ExportSectionTitle('Filtres'),
          ..._filterTiles(effective, stores),
          const ExportSectionTitle('Format'),
          const _CsvFormatTile(),
        ],
      ),
    );
  }

  List<Widget> _datasetTiles(Map<String, int> counts) {
    final available = _available(counts);
    final allSelected = available.isNotEmpty &&
        available.every((d) => _selected.contains(d.key));
    final noneSelected = available.every((d) => !_selected.contains(d.key));
    return [
      CheckboxListTile(
        key: const Key('local-export-all'),
        tristate: true,
        value: allSelected ? true : (noneSelected ? false : null),
        onChanged:
            available.isEmpty || _busy ? null : (_) => _toggleAll(available),
        title:
            const Text('Tout', style: TextStyle(fontWeight: FontWeight.w600)),
        controlAffinity: ListTileControlAffinity.leading,
      ),
      for (final d in localExportCatalog) _datasetTile(d, counts[d.key] ?? 0),
    ];
  }

  Widget _datasetTile(LocalExportDataset d, int count) {
    final enabled = count > 0;
    return CheckboxListTile(
      key: Key('local-export-dataset-${d.key}'),
      value: enabled && _selected.contains(d.key),
      onChanged: enabled && !_busy ? (v) => _toggle(d.key, v ?? false) : null,
      enabled: enabled,
      title: Text(d.label),
      subtitle: Text(enabled
          ? (count == 1 ? '1 ligne' : '$count lignes')
          : 'Aucune donnée sur cet appareil'),
      controlAffinity: ListTileControlAffinity.leading,
      dense: true,
    );
  }

  List<Widget> _filterTiles(
      List<LocalExportDataset> selected, List<ExportStoreOption> stores) {
    final storeEnabled = !_busy && selected.any((d) => d.storeFilter);
    final periodEnabled = !_busy && selected.any((d) => d.periodFilter);
    String? storeLabel;
    for (final s in stores) {
      if (s.id == _storeId) storeLabel = s.label;
    }
    return [
      ListTile(
        key: const Key('local-export-store'),
        enabled: storeEnabled,
        leading: const SoftIconBadge(icon: Icons.storefront_outlined),
        title: const Text('Boutique'),
        subtitle: Text(selected.isEmpty || selected.any((d) => d.storeFilter)
            ? (_storeId == null
                ? 'Toutes les boutiques'
                : storeLabel ?? 'Boutique sélectionnée')
            : 'Ne s\'applique pas à ces données'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: () => _pickStore(stores),
      ),
      ListTile(
        key: const Key('local-export-period'),
        enabled: periodEnabled,
        leading: const SoftIconBadge(icon: Icons.calendar_today_rounded),
        title: const Text('Période'),
        subtitle: Text(selected.isEmpty || selected.any((d) => d.periodFilter)
            ? exportPeriodLabel(_period, _from, _to)
            : 'Ne s\'applique pas à ces données'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: _pickPeriod,
      ),
    ];
  }
}

/// Fixed format of the on-device export.
class _CsvFormatTile extends StatelessWidget {
  const _CsvFormatTile();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      key: const Key('local-export-format'),
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
      child: Row(
        children: [
          Chip(
            label: const Text('CSV'),
            avatar:
                Icon(Icons.table_chart_outlined, size: 18, color: cs.primary),
            side: BorderSide(color: cs.outlineVariant),
            backgroundColor: cs.surface,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'Ouvre dans Excel',
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
          ),
        ],
      ),
    );
  }
}

class _LocalErrorCard extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;

  const _LocalErrorCard({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      key: const Key('local-export-error'),
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline_rounded, color: cs.onErrorContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(message, style: TextStyle(color: cs.onErrorContainer)),
          ),
          TextButton(
            key: const Key('local-export-retry'),
            onPressed: onRetry,
            child: const Text('Réessayer'),
          ),
        ],
      ),
    );
  }
}
