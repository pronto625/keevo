import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../settings/presentation/widget/plan_limit_bottom_sheet.dart';
import '../../domain/exception/export_exception.dart';
import '../../domain/model/export_models.dart';
import '../export_presenter.dart';
import '../provider/export_providers.dart';

/// MyExportsPage — « Mes exports » (v1s-18-4, OWNER only).
///
/// Background export jobs (server-side, kept 24 h), newest first. READY →
/// « Ouvrir » (download + share sheet), FAILED → « Relancer », PENDING /
/// RUNNING → refreshed automatically. Routes: /exports and /exports/:id
/// (FCM `EXPORT_READY` / `EXPORT_FAILED` deep link — [highlightId] is
/// highlighted and, when READY, its file is offered once automatically).
class MyExportsPage extends ConsumerStatefulWidget {
  final String? highlightId;

  const MyExportsPage({super.key, this.highlightId});

  @override
  ConsumerState<MyExportsPage> createState() => _MyExportsPageState();
}

class _MyExportsPageState extends ConsumerState<MyExportsPage> {
  Timer? _timer;
  bool _autoOpened = false;
  final _busy = <String>{};

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  /// Periodic refresh only while a job is pending / running.
  void _syncTimer(bool active) {
    if (active && _timer == null) {
      _timer = Timer.periodic(ref.read(exportJobsRefreshIntervalProvider), (_) {
        if (mounted) ref.invalidate(exportJobsProvider);
      });
    } else if (!active && _timer != null) {
      _timer!.cancel();
      _timer = null;
    }
  }

  Future<void> _refresh() =>
      ref.refresh(exportJobsProvider.future).then((_) {}, onError: (_) {});

  void _snack(String message, {VoidCallback? retry}) =>
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(
        // J27: an action no longer makes the SnackBar stay forever.
        persist: false,
        showCloseIcon: true,
        content: Text(message),
        action: retry == null
            ? null
            : SnackBarAction(label: 'Réessayer', onPressed: retry),
      ));

  /// The job is gone / expired / not ready on the server: the card is stale,
  /// so the list is refreshed instead of offering a retry.
  static bool _isStale(ExportException e) =>
      e.isExpired || e.statusCode == 404 || e.statusCode == 409;

  Future<void> _open(ExportJob job) async {
    if (!_busy.add(job.id)) return;
    setState(() {});
    try {
      final dir = await ref.read(exportTempDirProvider.future);
      final file = await ref
          .read(exportDataSourceProvider)
          .downloadJobFile(job, dir: dir);
      if (!mounted) return;
      await ref.read(exportFileSharerProvider)(file);
    } on ExportException catch (e) {
      if (!mounted) return;
      if (_isStale(e)) {
        _snack(e.message);
        ref.invalidate(exportJobsProvider);
      } else {
        _snack(e.message, retry: () => _open(job));
      }
    } catch (_) {
      if (mounted) {
        _snack(ExportException.unexpected.message, retry: () => _open(job));
      }
    } finally {
      _busy.remove(job.id);
      if (mounted) setState(() {});
    }
  }

  Future<void> _relaunch(ExportJob job) async {
    final format = job.exportFormat;
    if (format == null) return;
    if (!_busy.add(job.id)) return;
    setState(() {});
    // Criteria are not carried by the job DTO: they cannot be restored.
    final request = ExportRequest(
      datasets: job.datasets,
      format: format,
      storeId: job.storeId,
      from: job.from,
      to: job.to,
    );
    try {
      final dir = await ref.read(exportTempDirProvider.future);
      final outcome = await ref
          .read(exportDataSourceProvider)
          .requestExport(request, dir: dir);
      if (!mounted) return;
      switch (outcome) {
        case ExportFileReady(:final file):
          await ref.read(exportFileSharerProvider)(file);
        case ExportQueued():
          _snack('Export relancé — nous vous prévenons dès qu\'il est prêt');
      }
      ref.invalidate(exportJobsProvider);
    } on ExportException catch (e) {
      if (!mounted) return;
      if (e.isPlanGate) {
        await showPremiumFeatureSheet(
          context: context,
          title: 'Fonction Premium',
          message: 'Les exports Excel, PDF et en arrière-plan sont réservés '
              'au plan Premium. Le CSV reste disponible sur tous les plans.',
        );
      } else {
        _snack(e.message, retry: () => _relaunch(job));
      }
    } catch (_) {
      if (mounted) {
        _snack(ExportException.unexpected.message, retry: () => _relaunch(job));
      }
    } finally {
      _busy.remove(job.id);
      if (mounted) setState(() {});
    }
  }

  void _maybeAutoOpen(List<ExportJob> jobs) {
    final id = widget.highlightId;
    if (_autoOpened || id == null) return;
    for (final j in jobs) {
      if (j.id != id) continue;
      if (j.effectiveStatus(DateTime.now()) == ExportJobStatus.ready) {
        _autoOpened = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _open(j);
        });
      } else if (!j.isActive) {
        // Failed / expired: nothing to open, stop looking.
        _autoOpened = true;
      }
      return;
    }
    // Loaded list without that job (purged, other account…): say so once.
    _autoOpened = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _snack('Export introuvable ou expiré');
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final jobsAsync = ref.watch(exportJobsProvider);
    final labels = {
      for (final d in ref.watch(exportDatasetsProvider).valueOrNull ??
          const <ExportDataset>[])
        d.key: d.label,
    };
    final stores = {
      for (final s in ref.watch(exportStoreOptionsProvider).valueOrNull ??
          const <ExportStoreOption>[])
        s.id: s.label,
    };

    final jobs = jobsAsync.valueOrNull;
    _syncTimer(jobs != null && jobs.any((j) => j.isActive));
    if (jobs != null) _maybeAutoOpen(jobs);

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
          'Mes exports',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        actions: [
          IconButton(
            key: const Key('my-exports-new'),
            icon: Icon(Icons.add_rounded, color: cs.primary),
            tooltip: 'Nouvel export',
            onPressed: () => context.push('/exports/new'),
          ),
        ],
      ),
      body: jobsAsync.when(
        skipLoadingOnRefresh: true,
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => AppErrorWidget(
          error: err,
          title: 'Mes exports indisponibles',
          message: err is ExportException ? err.message : null,
          onRetry: () {
            ref.invalidate(exportOnlineProvider);
            ref.invalidate(exportJobsProvider);
          },
        ),
        data: (list) {
          if (list.isEmpty) {
            return RefreshIndicator(
              onRefresh: _refresh,
              child: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: SizedBox(
                    height: constraints.maxHeight,
                    child: const _EmptyState(),
                  ),
                ),
              ),
            );
          }
          final now = DateTime.now();
          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
              itemCount: list.length,
              itemBuilder: (context, i) {
                final job = list[i];
                return _JobCard(
                  job: job,
                  now: now,
                  labels: labels,
                  storeLabel: job.storeId == null ? null : stores[job.storeId],
                  highlighted: job.id == widget.highlightId,
                  busy: _busy.contains(job.id),
                  onOpen: () => _open(job),
                  onRelaunch: () => _relaunch(job),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _JobCard extends StatelessWidget {
  final ExportJob job;
  final DateTime now;
  final Map<String, String> labels;
  final String? storeLabel;
  final bool highlighted;
  final bool busy;
  final VoidCallback onOpen;
  final VoidCallback onRelaunch;

  const _JobCard({
    required this.job,
    required this.now,
    required this.labels,
    required this.storeLabel,
    required this.highlighted,
    required this.busy,
    required this.onOpen,
    required this.onRelaunch,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final status = job.effectiveStatus(now);
    final title = job.datasets.isEmpty
        ? 'Export'
        // v1s-16-20: an unknown dataset key reads « Export », never the key.
        : job.datasets.map((k) => labels[k] ?? 'Export').join(', ');
    final format = job.exportFormat?.label ?? job.format;
    final store = job.storeId == null
        ? 'Toutes les boutiques'
        : (storeLabel ?? 'Une boutique');
    final muted =
        theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant);
    final canRelaunch = job.exportFormat != null;

    return Container(
      key: Key('export-job-${job.id}'),
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        border: Border.all(
          color: highlighted ? cs.primary : cs.outlineVariant,
          width: highlighted ? 2 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SoftIconBadge(
                icon: Icons.description_outlined,
                color: _statusColor(status, cs),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: theme.textTheme.bodyMedium
                          ?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '$format · ${ExportPresenter.period(job.from, job.to)}'
                      ' · $store',
                      style: muted,
                    ),
                    if (job.createdAt != null)
                      Text(
                          'Créé le ${ExportPresenter.dateTime(job.createdAt!)}',
                          style: muted),
                    if (status == ExportJobStatus.ready) ...[
                      if (job.rowCount != null || job.fileSize != null)
                        Text(
                          [
                            if (job.rowCount != null) '${job.rowCount} lignes',
                            if (job.fileSize != null)
                              ExportPresenter.fileSize(job.fileSize!),
                          ].join(' · '),
                          style: muted,
                        ),
                      if (job.expiresAt != null)
                        Text(
                            'Expire le ${ExportPresenter.dateTime(job.expiresAt!)}',
                            style: muted),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _StatusChip(status),
            ],
          ),
          if (status == ExportJobStatus.ready ||
              (status == ExportJobStatus.failed && canRelaunch))
            Align(
              alignment: Alignment.centerRight,
              child: busy
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    )
                  : status == ExportJobStatus.ready
                      ? TextButton.icon(
                          key: Key('export-open-${job.id}'),
                          onPressed: onOpen,
                          icon: const Icon(Icons.ios_share_rounded),
                          label: const Text('Ouvrir'),
                        )
                      : TextButton.icon(
                          key: Key('export-relaunch-${job.id}'),
                          onPressed: onRelaunch,
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Relancer'),
                        ),
            ),
        ],
      ),
    );
  }
}

Color _statusColor(ExportJobStatus s, ColorScheme cs) => switch (s) {
      ExportJobStatus.pending => AppTheme.iconAmber,
      ExportJobStatus.running => AppTheme.iconBlue,
      ExportJobStatus.ready => AppTheme.iconTeal,
      ExportJobStatus.failed => cs.error,
      ExportJobStatus.expired || ExportJobStatus.unknown => cs.onSurfaceVariant,
    };

String _statusLabel(ExportJobStatus s) => switch (s) {
      ExportJobStatus.pending => 'En attente',
      ExportJobStatus.running => 'En cours',
      ExportJobStatus.ready => 'Prêt',
      ExportJobStatus.failed => 'Échec',
      ExportJobStatus.expired => 'Expiré',
      ExportJobStatus.unknown => 'Inconnu',
    };

class _StatusChip extends StatelessWidget {
  final ExportJobStatus status;
  const _StatusChip(this.status);

  @override
  Widget build(BuildContext context) {
    final color = _statusColor(status, Theme.of(context).colorScheme);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(AppTheme.radiusS),
      ),
      child: Text(
        _statusLabel(status),
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w700,
            ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SoftIconBadge(icon: Icons.folder_zip_outlined, size: 64),
            const SizedBox(height: 16),
            Text(
              'Aucun export en arrière-plan',
              textAlign: TextAlign.center,
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              'Les gros exports (Premium) sont préparés sur le serveur et '
              'apparaissent ici pendant 24 h. Les petits exports s\'ouvrent '
              'directement dans le partage.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
