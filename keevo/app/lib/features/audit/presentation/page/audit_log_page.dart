import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/date_range_clamp.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../export/domain/model/export_models.dart';
import '../../../export/presentation/widget/export_entry_button.dart';
import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/model/audit_filter.dart';
import '../audit_presenter.dart';
import '../provider/audit_log_provider.dart';

/// Accent color of an action family (theme tokens only).
Color auditCategoryColor(AuditCategory c, ColorScheme cs) => switch (c) {
      AuditCategory.product => AppTheme.iconBlue,
      AuditCategory.price => AppTheme.iconAmber,
      AuditCategory.stock => AppTheme.iconTeal,
      AuditCategory.sale => AppTheme.iconIndigo,
      AuditCategory.contact => AppTheme.iconCyan,
      AuditCategory.catalog => AppTheme.iconPurple,
      AuditCategory.store => AppTheme.iconOrange,
      AuditCategory.team => AppTheme.iconBlue,
      AuditCategory.account => AppTheme.iconGrey,
      AuditCategory.closure => AppTheme.iconIndigo,
      AuditCategory.inventory => AppTheme.iconTeal,
      AuditCategory.other => cs.onSurfaceVariant,
    };

/// v1s-18-4 — « Exporter » prefill of the history: only the audit log, the
/// same store and period, and the employee / action / object / search
/// filters as criteria.
ExportPrefill auditExportPrefill(AuditFilter f) => ExportPrefill(
      datasets: const {kAuditLogDatasetKey},
      storeId: f.storeId,
      from: f.from,
      to: f.to,
      criteria: {
        if (f.userId != null) 'userId': f.userId!,
        if (f.action != null) 'action': f.action!,
        if (f.entityType != null) 'entityType': f.entityType!,
        if (f.normalizedQuery != null) 'q': f.normalizedQuery!,
      },
    );

/// AuditLogPage — « Historique des actions » (v1s-18-7, OWNER only).
///
/// Readable, day-grouped history of every recorded action, filterable by
/// period, store, employee, action and object, with infinite scroll. Offline
/// (or when the server cannot be reached) it shows the copy synced on this
/// device with an « Hors ligne » banner. Route: /audit.
class AuditLogPage extends ConsumerStatefulWidget {
  const AuditLogPage({super.key});

  @override
  ConsumerState<AuditLogPage> createState() => _AuditLogPageState();
}

enum _Period { today, week, month, custom }

class _AuditLogPageState extends ConsumerState<AuditLogPage> {
  final _scroll = ScrollController();
  final _search = TextEditingController();
  Timer? _debounce;
  _Period? _period;

  /// The last load-more failed: the footer offers « Réessayer » instead of
  /// retrying on every scroll event.
  bool _loadMoreFailed = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _scroll.dispose();
    _search.dispose();
    super.dispose();
  }

  AuditFilter get _filter => ref.read(auditLogFilterNotifierProvider);

  void _setFilter(AuditFilter f) =>
      ref.read(auditLogFilterNotifierProvider.notifier).set(f);

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (!_loadMoreFailed && pos.pixels >= pos.maxScrollExtent - 300) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    final notifier = ref.read(auditLogNotifierProvider.notifier);
    if (notifier.isLoadingMore) return;
    final current = ref.read(auditLogNotifierProvider).valueOrNull;
    if (current == null || !current.hasMore) return;
    setState(() => _loadMoreFailed = false);
    try {
      await notifier.loadMore();
    } catch (_) {
      if (mounted) {
        _loadMoreFailed = true;
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(
              content: Text('Impossible de charger la suite de l\'historique')),
        );
      }
    } finally {
      if (mounted) setState(() {});
    }
  }

  Future<void> _refresh() => ref
      .refresh(auditLogNotifierProvider.future)
      .then((_) {}, onError: (_) {});

  void _onSearchChanged(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      if (mounted) _setFilter(_filter.copyWith(query: text));
    });
  }

  // ── Filter pickers ─────────────────────────────────────────────────────────

  Future<void> _pickPeriod() async {
    final choice = await _pickOption<_Period>(
      title: 'Période',
      selected: _period,
      allLabel: 'Toutes les dates',
      options: const [
        (_Period.today, 'Aujourd\'hui'),
        (_Period.week, '7 derniers jours'),
        (_Period.month, '30 derniers jours'),
        (_Period.custom, 'Personnalisé…'),
      ],
    );
    if (choice == null || !mounted) return;
    final now = DateTime.now();
    final startOfToday = DateTime(now.year, now.month, now.day);
    final endOfToday = startOfToday
        .add(const Duration(days: 1))
        .subtract(const Duration(milliseconds: 1));
    switch (choice.value) {
      case null:
        setState(() => _period = null);
        _setFilter(_filter.clearPeriod());
      case _Period.today:
        setState(() => _period = _Period.today);
        _setFilter(_filter.copyWith(from: startOfToday, to: endOfToday));
      case _Period.week:
        setState(() => _period = _Period.week);
        _setFilter(_filter.copyWith(
            from: startOfToday.subtract(const Duration(days: 6)),
            to: endOfToday));
      case _Period.month:
        setState(() => _period = _Period.month);
        _setFilter(_filter.copyWith(
            from: startOfToday.subtract(const Duration(days: 29)),
            to: endOfToday));
      case _Period.custom:
        final first = DateTime(2020);
        final current = _filter.from != null && _filter.to != null
            ? DateTimeRange(start: _filter.from!, end: _filter.to!)
            : null;
        final range = await showDateRangePicker(
          context: context,
          firstDate: first,
          lastDate: now,
          initialDateRange: clampDateRange(current, first, now),
          helpText: 'Choisir une période',
        );
        if (range == null || !mounted) return;
        setState(() => _period = _Period.custom);
        final end = DateTime(range.end.year, range.end.month, range.end.day)
            .add(const Duration(days: 1))
            .subtract(const Duration(milliseconds: 1));
        _setFilter(_filter.copyWith(from: range.start, to: end));
    }
  }

  Future<void> _pickStore(List<AuditFilterOption> stores) async {
    final choice = await _pickOption<String>(
      title: 'Boutique',
      selected: _filter.storeId,
      allLabel: 'Toutes les boutiques',
      options: [for (final s in stores) (s.id, s.label)],
    );
    if (choice != null) _setFilter(_filter.copyWith(storeId: choice.value));
  }

  Future<void> _pickEmployee(
      List<AuditFilterOption> employees, String? selfUserId) async {
    final choice = await _pickOption<String>(
      title: 'Employé',
      selected: _filter.userId,
      allLabel: 'Tout le monde',
      options: [
        if (selfUserId != null) (selfUserId, 'Vous (propriétaire)'),
        for (final e in employees)
          if (e.id != selfUserId) (e.id, e.label),
      ],
    );
    if (choice != null) _setFilter(_filter.copyWith(userId: choice.value));
  }

  Future<void> _pickAction() async {
    final actions = AuditPresenter.catalogue.entries.toList()
      ..sort((a, b) {
        final c = a.value.category.index.compareTo(b.value.category.index);
        return c != 0 ? c : a.value.label.compareTo(b.value.label);
      });
    final choice = await _pickOption<String>(
      title: 'Type d\'action',
      selected: _filter.action,
      allLabel: 'Toutes les actions',
      options: [for (final a in actions) (a.key, a.value.label)],
    );
    if (choice != null) _setFilter(_filter.copyWith(action: choice.value));
  }

  Future<void> _pickObject() async {
    final choice = await _pickOption<String>(
      title: 'Objet',
      selected: _filter.entityType,
      allLabel: 'Tous les objets',
      options: [
        for (final t in AuditPresenter.objectTypes.entries) (t.key, t.value),
      ],
    );
    if (choice != null) _setFilter(_filter.copyWith(entityType: choice.value));
  }

  void _clearFilters() {
    _debounce?.cancel();
    _search.clear();
    setState(() => _period = null);
    ref.read(auditLogFilterNotifierProvider.notifier).clear();
  }

  /// Bottom sheet with « all » + options. Returns `null` when dismissed,
  /// a [_Choice] with a `null` value for « all ».
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
    final filter = ref.watch(auditLogFilterNotifierProvider);
    ref.listen(auditLogFilterNotifierProvider, (_, __) {
      if (_loadMoreFailed) setState(() => _loadMoreFailed = false);
    });
    final logAsync = ref.watch(auditLogNotifierProvider);
    final selfUserId = ref.watch(currentUserIdProvider).valueOrNull;
    final stores = ref.watch(auditStoreOptionsProvider).valueOrNull ?? const [];
    final employees =
        ref.watch(auditEmployeeOptionsProvider).valueOrNull ?? const [];

    String? labelOf(List<AuditFilterOption> opts, String? id) {
      if (id == null) return null;
      for (final o in opts) {
        if (o.id == id) return o.label;
      }
      return null;
    }

    final periodLabel = switch (_period) {
      _Period.today => 'Aujourd\'hui',
      _Period.week => '7 jours',
      _Period.month => '30 jours',
      _Period.custom => filter.from != null && filter.to != null
          ? '${_short(filter.from!)} – ${_short(filter.to!)}'
          : 'Personnalisé',
      null => null,
    };
    final employeeLabel = filter.userId == null
        ? null
        : (filter.userId == selfUserId
            ? 'Vous'
            : labelOf(employees, filter.userId) ?? 'Employé');

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
          'Historique des actions',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        actions: [
          ExportEntryButton(
            key: const Key('audit-export'),
            prefill: auditExportPrefill(filter),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              key: const Key('audit-search'),
              controller: _search,
              onChanged: _onSearchChanged,
              onSubmitted: (t) {
                _debounce?.cancel();
                _setFilter(_filter.copyWith(query: t));
              },
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                isDense: true,
                hintText: 'Rechercher un objet (produit, client…)',
                prefixIcon: const Icon(Icons.search_rounded),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(AppTheme.radiusM),
                ),
              ),
            ),
          ),
          SizedBox(
            height: 44,
            child: SingleChildScrollView(
              key: const Key('audit-filter-chips'),
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(children: [
                if (!filter.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: ActionChip(
                      avatar: const Icon(Icons.close_rounded, size: 18),
                      label: const Text('Effacer'),
                      onPressed: _clearFilters,
                    ),
                  ),
                _FilterChip(
                  label: periodLabel ?? 'Période',
                  icon: Icons.calendar_today_rounded,
                  active: periodLabel != null,
                  onTap: _pickPeriod,
                ),
                _FilterChip(
                  label: filter.storeId == null
                      ? 'Boutique'
                      : labelOf(stores, filter.storeId) ?? 'Boutique',
                  icon: Icons.storefront_outlined,
                  active: filter.storeId != null,
                  onTap: () => _pickStore(stores),
                ),
                _FilterChip(
                  label: employeeLabel ?? 'Employé',
                  icon: Icons.person_outline_rounded,
                  active: filter.userId != null,
                  onTap: () => _pickEmployee(employees, selfUserId),
                ),
                _FilterChip(
                  label: filter.action == null
                      ? 'Action'
                      : AuditPresenter.actionInfo(filter.action!).label,
                  icon: Icons.bolt_rounded,
                  active: filter.action != null,
                  onTap: _pickAction,
                ),
                _FilterChip(
                  label: filter.entityType == null
                      ? 'Objet'
                      : AuditPresenter.objectTypeLabel(filter.entityType!),
                  icon: Icons.category_outlined,
                  active: filter.entityType != null,
                  onTap: _pickObject,
                ),
              ]),
            ),
          ),
          Expanded(
            child: logAsync.when(
              skipLoadingOnRefresh: true,
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (err, _) => AppErrorWidget(
                error: err,
                title: 'Historique indisponible',
                message: err is AuditException ? err.message : null,
                onRetry: () => ref.invalidate(auditLogNotifierProvider),
              ),
              data: (state) => _buildList(context, state, selfUserId),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildList(
      BuildContext context, AuditLogState state, String? selfUserId) {
    final cs = Theme.of(context).colorScheme;
    final banner = state.isOffline
        ? Container(
            key: const Key('audit-offline-banner'),
            width: double.infinity,
            margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: cs.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(AppTheme.radiusS),
            ),
            child: Row(
              children: [
                Icon(Icons.cloud_off_rounded,
                    size: 18, color: cs.onSurfaceVariant),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Hors ligne — dernier état connu',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                  ),
                ),
              ],
            ),
          )
        : null;

    if (state.entries.isEmpty) {
      return RefreshIndicator(
        onRefresh: _refresh,
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            child: SizedBox(
              height: constraints.maxHeight,
              child: Column(
                children: [
                  if (banner != null) banner,
                  Expanded(
                    child: _EmptyState(offline: state.isOffline),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    // Flatten into day headers + tiles.
    final items = <Object>[];
    String? currentDay;
    for (final e in state.entries) {
      final day = AuditPresenter.dayHeader(e.occurredAt);
      if (day != currentDay) {
        items.add(day);
        currentDay = day;
      }
      items.add(e);
    }
    final notifier = ref.read(auditLogNotifierProvider.notifier);
    final showFooter = state.hasMore || notifier.isLoadingMore;
    final lead = banner != null ? 1 : 0;

    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView.builder(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.only(bottom: 24),
        itemCount: lead + items.length + (showFooter ? 1 : 0),
        itemBuilder: (context, index) {
          if (banner != null && index == 0) return banner;
          final i = index - lead;
          if (i == items.length) {
            if (_loadMoreFailed && !notifier.isLoadingMore) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Center(
                  child: TextButton.icon(
                    onPressed: _loadMore,
                    icon: const Icon(Icons.refresh_rounded),
                    label: const Text('Réessayer'),
                  ),
                ),
              );
            }
            return const Padding(
              padding: EdgeInsets.symmetric(vertical: 20),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          final item = items[i];
          if (item is String) return _DayHeader(item);
          final entry = item as AuditEntryDto;
          return AuditLogTile(
            entry: entry,
            selfUserId: selfUserId,
            onTap: () => context.push('/audit/entry/${entry.id}', extra: entry),
          );
        },
      ),
    );
  }

  static String _short(DateTime d) {
    final l = d.toLocal();
    return '${l.day.toString().padLeft(2, '0')}/${l.month.toString().padLeft(2, '0')}';
  }
}

class _Choice<T> {
  final T? value;
  const _Choice(this.value);
}

class _FilterChip extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool active;
  final VoidCallback onTap;

  const _FilterChip({
    required this.label,
    required this.icon,
    required this.active,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: FilterChip(
        avatar: Icon(icon, size: 18),
        label: Text(label),
        selected: active,
        showCheckmark: false,
        onSelected: (_) => onTap(),
      ),
    );
  }
}

class _DayHeader extends StatelessWidget {
  final String label;
  const _DayHeader(this.label);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        label,
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final bool offline;
  const _EmptyState({required this.offline});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SoftIconBadge(icon: Icons.history_rounded, size: 64),
            const SizedBox(height: 16),
            Text(
              offline
                  ? 'Aucune action synchronisée sur cet appareil pour ces filtres'
                  : 'Aucune action pour ces filtres',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyLarge?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// One line of the history: type icon, readable sentence, store and time.
class AuditLogTile extends StatelessWidget {
  final AuditEntryDto entry;
  final String? selfUserId;
  final VoidCallback onTap;

  const AuditLogTile({
    super.key,
    required this.entry,
    required this.selfUserId,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final info = AuditPresenter.actionInfo(entry.action);
    final sentence = AuditPresenter.sentence(entry, selfUserId: selfUserId);
    const bold = TextStyle(fontWeight: FontWeight.w700);
    final meta = [
      if (entry.storeName != null && entry.storeName!.trim().isNotEmpty)
        entry.storeName!,
      AuditPresenter.formatTime(entry.occurredAt),
    ].join(' · ');

    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SoftIconBadge(
              icon: info.icon,
              color: auditCategoryColor(info.category, cs),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text.rich(
                    TextSpan(
                      style: theme.textTheme.bodyMedium,
                      children: [
                        TextSpan(text: sentence.actor, style: bold),
                        TextSpan(text: ' ${sentence.verb}'),
                        if (sentence.object != null) ...[
                          TextSpan(text: sentence.joiner),
                          TextSpan(text: sentence.object, style: bold),
                        ],
                        if (sentence.tail != null) ...[
                          TextSpan(
                              text: sentence.tailLead == null
                                  ? ' '
                                  : ' ${sentence.tailLead} '),
                          TextSpan(text: sentence.tail, style: bold),
                        ],
                      ],
                    ),
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    meta,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: cs.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right_rounded, color: cs.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}
