import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/date_range_clamp.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../settings/presentation/widget/plan_limit_bottom_sheet.dart';
import '../../domain/exception/export_exception.dart';
import '../../domain/model/export_models.dart';
import '../export_presenter.dart';
import '../provider/export_providers.dart';

/// ExportPage — « Exporter mes données » (v1s-18-4, OWNER only).
///
/// Datasets → filters (store, period) → format → « Exporter ». A direct
/// export is streamed to a temp file and handed to the share sheet; a
/// background export (Premium) is confirmed and listed in « Mes exports ».
/// Route: /exports/new (extra: [ExportPrefill]).
class ExportPage extends ConsumerStatefulWidget {
  final ExportPrefill? prefill;

  const ExportPage({super.key, this.prefill});

  @override
  ConsumerState<ExportPage> createState() => _ExportPageState();
}

enum _Period { today, week, month, custom }

class _ExportPageState extends ConsumerState<ExportPage> {
  late Set<String> _selected;
  late String? _storeId;
  late DateTime? _from;
  late DateTime? _to;
  late _Period? _period;

  /// Audit log criteria from the prefill — kept even when not sent.
  late Map<String, String> _criteria;

  ExportFormat _format = ExportFormat.csv;
  bool _busy = false;
  bool _pruned = false;
  ExportException? _error;
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
    _period = (_from != null || _to != null) ? _Period.custom : null;
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

  Future<void> _selectFormat(ExportFormat f, bool? premium) async {
    if (f == ExportFormat.pdf) return; // « Bientôt » (v1s-18-3)
    if (f == ExportFormat.xlsx && premium == false) {
      await _showOffer();
      return;
    }
    setState(() {
      _format = f;
      _clearError();
    });
  }

  Future<void> _showOffer() => showPremiumFeatureSheet(
        context: context,
        title: 'Excel est réservé au plan Premium',
        message: 'Le CSV reste disponible sur tous les plans. Passez au '
            'plan Premium pour exporter en Excel et préparer les gros '
            'exports en arrière-plan.',
      );

  Future<void> _launch(List<ExportDataset> datasets,
      {ExportRequest? replay}) async {
    if (_busy) return;
    if (replay == null) _pruneSelection(datasets);
    final ExportRequest request;
    try {
      request = replay ??
          buildExportRequest(
            selection: _selected,
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
        // Unknown plan let Excel through: fall back to the free format.
        if (mounted) setState(() => _format = ExportFormat.csv);
      } else if (mounted) {
        setState(() => _error = e);
      }
    } catch (_) {
      if (mounted) setState(() => _error = ExportException.unexpected);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    // After the busy state: the sheet must not keep the spinner running.
    if (planGate && mounted) await _showOffer();
  }

  Future<void> _pickPeriod() async {
    final choice = await _pickOption<_Period>(
      title: 'Période',
      selected: _period,
      allLabel: 'Toutes les dates',
      options: const [
        (_Period.today, 'Aujourd\'hui'),
        (_Period.week, '7 jours'),
        (_Period.month, 'Mois (30 derniers jours)'),
        (_Period.custom, 'Personnalisé…'),
      ],
    );
    if (choice == null || !mounted) return;
    setState(_clearError);
    final now = DateTime.now();
    final startOfToday = DateTime(now.year, now.month, now.day);
    final endOfToday = startOfToday
        .add(const Duration(days: 1))
        .subtract(const Duration(milliseconds: 1));
    switch (choice.value) {
      case null:
        setState(() {
          _period = null;
          _from = null;
          _to = null;
        });
      case _Period.today:
        setState(() {
          _period = _Period.today;
          _from = startOfToday;
          _to = endOfToday;
        });
      case _Period.week:
        setState(() {
          _period = _Period.week;
          _from = startOfToday.subtract(const Duration(days: 6));
          _to = endOfToday;
        });
      case _Period.month:
        setState(() {
          _period = _Period.month;
          _from = startOfToday.subtract(const Duration(days: 29));
          _to = endOfToday;
        });
      case _Period.custom:
        final first = DateTime(2020);
        final current = _from != null && _to != null
            ? DateTimeRange(start: _from!, end: _to!)
            : null;
        final range = await showDateRangePicker(
          context: context,
          firstDate: first,
          lastDate: now,
          initialDateRange: clampDateRange(current, first, now),
          helpText: 'Choisir une période',
        );
        if (range == null || !mounted) return;
        final end = DateTime(range.end.year, range.end.month, range.end.day)
            .add(const Duration(days: 1))
            .subtract(const Duration(milliseconds: 1));
        setState(() {
          _period = _Period.custom;
          _from = range.start;
          _to = end;
        });
    }
  }

  Future<void> _pickStore(List<ExportStoreOption> stores) async {
    final choice = await _pickOption<String>(
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

  /// Bottom sheet with « all » + options (same pattern as the audit
  /// filters). `null` when dismissed, a [_Choice] with a `null` value for
  /// « all ».
  Future<_Choice<T>?> _pickOption<T>({
    required String title,
    required T? selected,
    required String allLabel,
    required List<(T, String)> options,
  }) {
    return showModalBottomSheet<_Choice<T>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) {
        final cs = Theme.of(context).colorScheme;
        Widget tile(T? value, String label) => ListTile(
              title: Text(label),
              trailing: value == selected
                  ? Icon(Icons.check_rounded, color: cs.primary)
                  : null,
              onTap: () => Navigator.of(context).pop(_Choice<T>(value)),
            );
        return SafeArea(
          child: ConstrainedBox(
            constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(context).height * 0.7),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(title,
                      style: Theme.of(context).textTheme.titleMedium),
                ),
                Flexible(
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      tile(null, allLabel),
                      for (final o in options) tile(o.$1, o.$2),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
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

    final canLaunch =
        !offline && !_busy && _selected.isNotEmpty && datasetsAsync.hasValue;

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
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          if (offline) ...[
            _Banner(
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
            const ListTile(
              key: Key('export-local-csv-entry'),
              enabled: false,
              leading: SoftIconBadge(icon: Icons.phone_android_rounded),
              title: Text('CSV — données de cet appareil'),
              subtitle: Text('Bientôt disponible'),
            ),
          ],
          if (_error != null)
            _ErrorCard(
              message: _error!.message,
              onRetry: _busy || offline || _lastRequest == null
                  ? null
                  : () => _launch(datasets, replay: _lastRequest),
            ),
          if (_queued != null) const _QueuedCard(),
          const _SectionTitle('Quoi'),
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
            data: (list) => _datasetTiles(list),
          ),
          if (_criteria.isNotEmpty && _selected.contains(kAuditLogDatasetKey))
            _criteriaHint(context),
          const _SectionTitle('Filtres'),
          ..._filterTiles(datasets, stores),
          const _SectionTitle('Format'),
          ..._formatTiles(premium),
        ],
      ),
    );
  }

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

  Widget _criteriaHint(BuildContext context) {
    final applies = exportCriteriaApply(_selected);
    return _Banner(
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
    final selected = [
      for (final d in datasets)
        if (_selected.contains(d.key)) d,
    ];
    // Unknown catalogue (still loading): keep the pickers usable.
    final storeEnabled = selected.isEmpty || selected.any((d) => d.storeFilter);
    final periodEnabled =
        selected.isEmpty || selected.any((d) => d.periodFilter);

    String? storeLabel;
    for (final s in stores) {
      if (s.id == _storeId) storeLabel = s.label;
    }
    final periodLabel = switch (_period) {
      _Period.today => 'Aujourd\'hui',
      _Period.week => '7 jours',
      _Period.month => 'Mois (30 derniers jours)',
      _Period.custom => ExportPresenter.period(_from, _to),
      null => 'Toutes les dates',
    };
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

  List<Widget> _formatTiles(bool? premium) {
    final cs = Theme.of(context).colorScheme;
    Widget tile(ExportFormat f, String subtitle, {Widget? badge}) {
      final disabled = f == ExportFormat.pdf;
      final selected = _format == f;
      final listTile = ListTile(
        key: Key('export-format-${f.name}'),
        enabled: !disabled,
        leading: Icon(
          selected
              ? Icons.radio_button_checked_rounded
              : Icons.radio_button_off_rounded,
          color: disabled
              ? cs.onSurfaceVariant
              : (selected ? cs.primary : cs.onSurfaceVariant),
        ),
        title: Row(children: [
          Text(f.label),
          if (badge != null) ...[const SizedBox(width: 8), badge],
        ]),
        subtitle: Text(subtitle),
        onTap: disabled ? null : () => _selectFormat(f, premium),
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
      tile(ExportFormat.pdf, 'Modèles PDF',
          badge: const _Badge('Bientôt', muted: true)),
    ];
  }
}

class _Choice<T> {
  final T? value;
  const _Choice(this.value);
}

class _SectionTitle extends StatelessWidget {
  final String text;
  const _SectionTitle(this.text);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(
        text,
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  final IconData icon;
  final String text;
  final Widget? action;

  const _Banner(
      {super.key, required this.icon, required this.text, this.action});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(AppTheme.radiusS),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: cs.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
          ),
          if (action != null) action!,
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  final String text;
  final bool muted;

  const _Badge(this.text, {this.muted = false});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final color = muted ? cs.onSurfaceVariant : AppTheme.iconAmber;
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
