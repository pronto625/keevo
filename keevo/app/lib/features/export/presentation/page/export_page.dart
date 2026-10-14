import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../settings/presentation/widget/plan_limit_bottom_sheet.dart';
import '../../domain/exception/export_exception.dart';
import '../../domain/model/export_models.dart';
import '../provider/export_providers.dart';
import '../widget/export_form_widgets.dart';

/// ExportPage — « Exporter mes données » (v1s-18-4, OWNER only).
///
/// Datasets → filters (store, period) → format → « Exporter ». A direct
/// export is streamed to a temp file and handed to the share sheet; a
/// background export (Premium) is confirmed and listed in « Mes exports ».
/// PDF (Premium, v1s-18-3) replaces the dataset checkboxes by a single-choice
/// « Modèle » list (Fin de journée, Rapport d'inventaire, État de stock,
/// Liste des ventes) read from the server catalogue; the template is sent as
/// its one dataset key.
/// Route: /exports/new (extra: [ExportPrefill]).
class ExportPage extends ConsumerStatefulWidget {
  final ExportPrefill? prefill;

  const ExportPage({super.key, this.prefill});

  @override
  ConsumerState<ExportPage> createState() => _ExportPageState();
}

class _ExportPageState extends ConsumerState<ExportPage> {
  late Set<String> _selected;
  late String? _storeId;
  late DateTime? _from;
  late DateTime? _to;
  late ExportPeriod? _period;

  /// Audit log criteria from the prefill — kept even when not sent.
  late Map<String, String> _criteria;

  ExportFormat _format = ExportFormat.csv;

  /// Dataset key of the chosen PDF template (PDF only; the CSV / Excel
  /// selection in [_selected] is kept untouched meanwhile).
  String? _template;
  bool _busy = false;
  bool _pruned = false;
  ExportException? _error;

  /// J11: the error banner sits at the top of the form — scroll back to it
  /// when an export fails, the « Exporter » button being at the bottom.
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _revealError() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.animateTo(0,
          duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    });
  }
  ExportRequest? _lastRequest;
  ExportJob? _queued;

  @override
  void initState() {
    super.initState();
    final p = widget.prefill;
    _selected = {...?p?.datasets};
    _storeId = p?.storeId;
    _from = p?.from;
    _to = p?.to;
    _period = (_from != null || _to != null) ? ExportPeriod.custom : null;
    _criteria = {
      for (final e in (p?.criteria ?? const <String, String>{}).entries)
        if (e.value.trim().isNotEmpty) e.key: e.value.trim(),
    };
  }

  // ── Actions ────────────────────────────────────────────────────────────────

  /// A changed choice invalidates the failed request (« Réessayer »).
  void _clearError() {
    _error = null;
    _lastRequest = null;
  }

  /// Drops prefilled keys the server catalogue does not list (they could not
  /// be unticked and would be refused). Runs once, when the list is known.
  void _pruneSelection(List<ExportDataset> datasets) {
    if (_pruned) return;
    _pruned = true;
    final keys = {for (final d in datasets) d.key};
    _selected.retainWhere(keys.contains);
  }

  void _toggle(String key, bool on) => setState(() {
        on ? _selected.add(key) : _selected.remove(key);
        _queued = null;
        _clearError();
      });

  void _toggleAll(List<ExportDataset> datasets) => setState(() {
        final all = datasets.every((d) => _selected.contains(d.key));
        if (all) {
          _selected.clear();
        } else {
          _selected.addAll(datasets.map((d) => d.key));
        }
        _queued = null;
        _clearError();
      });

  Future<void> _selectFormat(
      ExportFormat f, bool? premium, List<ExportDataset> datasets) async {
    if (f != ExportFormat.csv && premium == false) {
      await _showOffer(f);
      return;
    }
    setState(() {
      if (f == ExportFormat.pdf && _format != ExportFormat.pdf) {
        // Preselect the template when the selection holds exactly one.
        final templated = [
          for (final d in datasets)
            if (d.hasPdfTemplate && _selected.contains(d.key)) d.key,
        ];
        _template = templated.length == 1 ? templated.first : null;
      }
      _format = f;
      _queued = null;
      _clearError();
    });
  }

  void _selectTemplate(String key) => setState(() {
        _template = key;
        _queued = null;
        _clearError();
      });

  Future<void> _showOffer(ExportFormat format) => format == ExportFormat.pdf
      ? showPremiumFeatureSheet(
          context: context,
          title: 'Le PDF est réservé au plan Premium',
          message: 'Le CSV reste disponible sur tous les plans. Passez au '
              'plan Premium pour obtenir les documents PDF : fin de '
              'journée, rapport d\'inventaire, état de stock et liste des '
              'ventes.',
        )
      : showPremiumFeatureSheet(
          context: context,
          title: 'Excel est réservé au plan Premium',
          message: 'Le CSV reste disponible sur tous les plans. Passez au '
              'plan Premium pour exporter en Excel et préparer les gros '
              'exports en arrière-plan.',
        );

  /// Datasets the request is built from: the chosen template for PDF.
  Set<String> get _requestSelection => _format == ExportFormat.pdf
      ? {if (_template != null) _template!}
      : _selected;

  Future<void> _launch(List<ExportDataset> datasets,
      {ExportRequest? replay}) async {
    if (_busy) return;
    if (replay == null) _pruneSelection(datasets);
    final ExportRequest request;
    try {
      request = replay ??
          buildExportRequest(
            selection: _requestSelection,
            format: _format,
            storeId: _storeId,
            from: _from,
            to: _to,
            criteria: _criteria,
            datasets: datasets,
          );
    } on ArgumentError {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _queued = null;
      _lastRequest = request;
    });
    var planGate = false;
    final requestedFormat = request.format;
    try {
      final dir = await ref.read(exportTempDirProvider.future);
      final outcome = await ref
          .read(exportDataSourceProvider)
          .requestExport(request, dir: dir);
      if (!mounted) return;
      switch (outcome) {
        case ExportFileReady(:final file):
          await ref.read(exportFileSharerProvider)(file);
          if (!mounted) return;
          ScaffoldMessenger.maybeOf(context)
              ?.showSnackBar(const SnackBar(content: Text('Fichier prêt')));
        case ExportQueued(:final job):
          setState(() => _queued = job);
          ref.invalidate(exportJobsProvider);
      }
    } on ExportException catch (e) {
      if (e.isPlanGate) {
        planGate = true;
        // Unknown plan let Excel / PDF through: fall back to the free format.
        if (mounted) setState(() => _format = ExportFormat.csv);
      } else if (mounted) {
        setState(() => _error = e);
        _revealError();
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = ExportException.unexpected);
        _revealError();
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    // After the busy state: the sheet must not keep the spinner running.
    if (planGate && mounted) await _showOffer(requestedFormat);
  }

  Future<void> _pickPeriod() async {
    final picked = await pickExportPeriod(context,
        current: _period, from: _from, to: _to);
    if (picked == null || !mounted) return;
    setState(() {
      _clearError();
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
        _clearError();
      });
    }
  }

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final datasetsAsync = ref.watch(exportDatasetsProvider);
    final datasets = datasetsAsync.valueOrNull ?? const <ExportDataset>[];
    if (datasetsAsync.hasValue) _pruneSelection(datasets);
    final offline = ref.watch(exportOnlineProvider).valueOrNull == false;
    final premium = ref.watch(exportIsPremiumProvider).valueOrNull;
    final stores =
        ref.watch(exportStoreOptionsProvider).valueOrNull ?? const [];

    final pdf = _format == ExportFormat.pdf;
    final canLaunch = !offline &&
        !_busy &&
        _requestSelection.isNotEmpty &&
        datasetsAsync.hasValue;

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
          'Exporter',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        actions: [
          IconButton(
            key: const Key('export-my-exports'),
            icon: Icon(Icons.folder_zip_outlined, color: cs.primary),
            tooltip: 'Mes exports',
            onPressed: () => context.push('/exports'),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: FilledButton.icon(
            key: const Key('export-submit'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
              ),
            ),
            onPressed: canLaunch ? () => _launch(datasets) : null,
            icon: _busy
                ? SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: cs.onPrimary),
                  )
                : const Icon(Icons.ios_share_rounded),
            label: Text(_busy ? 'Export en cours…' : 'Exporter'),
          ),
        ),
      ),
      body: ListView(
        controller: _scroll,
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          if (offline) ...[
            ExportInfoBanner(
              key: const Key('export-offline-banner'),
              icon: Icons.cloud_off_rounded,
              text: 'Hors ligne — l\'export complet nécessite une connexion',
              action: TextButton(
                key: const Key('export-offline-retry'),
                onPressed: () {
                  ref.invalidate(exportOnlineProvider);
                  ref.invalidate(exportDatasetsProvider);
                },
                child: const Text('Réessayer'),
              ),
            ),
            _localCsvTile(),
          ],
          if (_error != null)
            _ErrorCard(
              message: _error!.message,
              onRetry: _busy || offline || _lastRequest == null
                  ? null
                  : () => _launch(datasets, replay: _lastRequest),
            ),
          // Server unreachable: the on-device CSV is the way out (v1s-18-5).
          if (!offline && _error != null && _error!.isNetwork) _localCsvTile(),
          if (_queued != null) const _QueuedCard(),
          ExportSectionTitle(pdf ? 'Modèle' : 'Quoi'),
          ...datasetsAsync.when(
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
                title: 'Jeux de données indisponibles',
                message: err is ExportException ? err.message : null,
                onRetry: () => ref.invalidate(exportDatasetsProvider),
              ),
            ],
            data: (list) => pdf ? _templateTiles(list) : _datasetTiles(list),
          ),
          if (!pdf &&
              _criteria.isNotEmpty &&
              _selected.contains(kAuditLogDatasetKey))
            _criteriaHint(context),
          const ExportSectionTitle('Filtres'),
          ..._filterTiles(datasets, stores),
          const ExportSectionTitle('Format'),
          ..._formatTiles(premium, datasets),
        ],
      ),
    );
  }

  /// « CSV — données de cet appareil » (v1s-18-5): opens the on-device
  /// export with the current selection, store and period.
  Widget _localCsvTile() => ListTile(
        key: const Key('export-local-csv-entry'),
        leading: const SoftIconBadge(icon: Icons.phone_android_rounded),
        title: const Text('CSV — données de cet appareil'),
        subtitle: const Text(
            'Contient uniquement les données synchronisées sur cet appareil'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: () => context.push(
          '/exports/local',
          extra: ExportPrefill(
            datasets: {..._selected},
            storeId: _storeId,
            from: _from,
            to: _to,
            criteria: _criteria,
          ),
        ),
      );

  List<Widget> _datasetTiles(List<ExportDataset> list) {
    final allSelected =
        list.isNotEmpty && list.every((d) => _selected.contains(d.key));
    final noneSelected = list.every((d) => !_selected.contains(d.key));
    return [
      CheckboxListTile(
        key: const Key('export-all'),
        tristate: true,
        value: allSelected ? true : (noneSelected ? false : null),
        onChanged: list.isEmpty ? null : (_) => _toggleAll(list),
        title:
            const Text('Tout', style: TextStyle(fontWeight: FontWeight.w600)),
        controlAffinity: ListTileControlAffinity.leading,
      ),
      for (final d in list)
        CheckboxListTile(
          key: Key('export-dataset-${d.key}'),
          value: _selected.contains(d.key),
          onChanged: (v) => _toggle(d.key, v ?? false),
          title: Text(d.label),
          controlAffinity: ListTileControlAffinity.leading,
          dense: true,
        ),
    ];
  }

  /// PDF: one template per dataset that has one, single choice.
  List<Widget> _templateTiles(List<ExportDataset> list) {
    final cs = Theme.of(context).colorScheme;
    final templated = [
      for (final d in list)
        if (d.hasPdfTemplate) d,
    ];
    if (templated.isEmpty) {
      return [
        const ListTile(
          key: Key('export-template-none'),
          enabled: false,
          title: Text('Aucun modèle PDF disponible'),
        ),
      ];
    }
    return [
      for (final d in templated)
        Semantics(
          container: true,
          selected: _template == d.key,
          inMutuallyExclusiveGroup: true,
          child: ListTile(
            key: Key('export-template-${d.key}'),
            leading: Icon(
              _template == d.key
                  ? Icons.radio_button_checked_rounded
                  : Icons.radio_button_off_rounded,
              color: _template == d.key ? cs.primary : cs.onSurfaceVariant,
            ),
            title: Text(d.pdfTemplate!),
            subtitle: Text('Données : ${d.label}'),
            dense: true,
            onTap: () => _selectTemplate(d.key),
          ),
        ),
    ];
  }

  Widget _criteriaHint(BuildContext context) {
    final applies = exportCriteriaApply(_selected);
    return ExportInfoBanner(
      key: Key(applies ? 'export-criteria-applied' : 'export-criteria-note'),
      icon: applies ? Icons.filter_alt_outlined : Icons.info_outline_rounded,
      text: applies
          ? 'Les critères de l\'historique (employé, action, objet, '
              'recherche) sont appliqués'
          : 'Les critères du journal (employé, action, objet, recherche) '
              'ne s\'appliquent qu\'au Journal d\'audit seul',
    );
  }

  List<Widget> _filterTiles(
      List<ExportDataset> datasets, List<ExportStoreOption> stores) {
    final selection = _requestSelection;
    final selected = [
      for (final d in datasets)
        if (selection.contains(d.key)) d,
    ];
    // Unknown catalogue (still loading): keep the pickers usable.
    final storeEnabled = selected.isEmpty || selected.any((d) => d.storeFilter);
    final periodEnabled =
        selected.isEmpty || selected.any((d) => d.periodFilter);

    String? storeLabel;
    for (final s in stores) {
      if (s.id == _storeId) storeLabel = s.label;
    }
    final periodLabel = exportPeriodLabel(_period, _from, _to);
    return [
      ListTile(
        key: const Key('export-store'),
        enabled: storeEnabled,
        leading: const SoftIconBadge(icon: Icons.storefront_outlined),
        title: const Text('Boutique'),
        subtitle: Text(storeEnabled
            ? (_storeId == null
                ? 'Toutes les boutiques'
                : storeLabel ?? 'Boutique sélectionnée')
            : 'Ne s\'applique pas à ces données'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: () => _pickStore(stores),
      ),
      ListTile(
        key: const Key('export-period'),
        enabled: periodEnabled,
        leading: const SoftIconBadge(icon: Icons.calendar_today_rounded),
        title: const Text('Période'),
        subtitle: Text(
            periodEnabled ? periodLabel : 'Ne s\'applique pas à ces données'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: _pickPeriod,
      ),
    ];
  }

  List<Widget> _formatTiles(bool? premium, List<ExportDataset> datasets) {
    final cs = Theme.of(context).colorScheme;
    Widget tile(ExportFormat f, String subtitle, {Widget? badge}) {
      final selected = _format == f;
      final listTile = ListTile(
        key: Key('export-format-${f.name}'),
        leading: Icon(
          selected
              ? Icons.radio_button_checked_rounded
              : Icons.radio_button_off_rounded,
          color: selected ? cs.primary : cs.onSurfaceVariant,
        ),
        title: Row(children: [
          Text(f.label),
          if (badge != null) ...[const SizedBox(width: 8), badge],
        ]),
        subtitle: Text(subtitle),
        onTap: () => _selectFormat(f, premium, datasets),
      );
      return Semantics(
        container: true,
        selected: selected,
        inMutuallyExclusiveGroup: true,
        child: listTile,
      );
    }

    final premiumBadge = premium == true ? null : const _Badge('Premium');
    return [
      tile(ExportFormat.csv, 'Tous les plans · ouvre dans Excel'),
      tile(ExportFormat.xlsx, 'Une feuille par jeu de données',
          badge: premiumBadge),
      tile(
          ExportFormat.pdf, 'Fin de journée, inventaire, état de stock, ventes',
          badge: premiumBadge),
    ];
  }
}

class _Badge extends StatelessWidget {
  final String text;

  const _Badge(this.text);

  @override
  Widget build(BuildContext context) {
    const color = AppTheme.iconAmber;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(AppTheme.radiusS),
      ),
      child: Text(
        text,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w700,
            ),
      ),
    );
  }
}

class _ErrorCard extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;

  const _ErrorCard({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      key: const Key('export-error'),
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
            key: const Key('export-retry'),
            onPressed: onRetry,
            child: const Text('Réessayer'),
          ),
        ],
      ),
    );
  }
}

class _QueuedCard extends StatelessWidget {
  const _QueuedCard();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      key: const Key('export-queued'),
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.primaryContainer,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.hourglass_top_rounded, color: cs.onPrimaryContainer),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Votre export se prépare, nous vous prévenons dès qu\'il '
                  'est prêt',
                  style: TextStyle(color: cs.onPrimaryContainer),
                ),
              ),
            ],
          ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              key: const Key('export-see-my-exports'),
              onPressed: () => context.push('/exports'),
              child: const Text('Voir Mes exports'),
            ),
          ),
        ],
      ),
    );
  }
}
