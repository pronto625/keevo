import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/exception/audit_exception.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../audit_presenter.dart';
import '../provider/audit_log_provider.dart';
import 'audit_log_page.dart' show auditCategoryColor;

/// AuditEntryDetailPage — full detail of one recorded action (v1s-18-7).
///
/// Renders the tapped entry ([initialEntry]) immediately, then the fresh
/// detail from `GET /api/v1/audit/{id}` (local row when unreachable). If the
/// refresh fails, the tapped data stays on screen. Route: /audit/entry/:id.
class AuditEntryDetailPage extends ConsumerWidget {
  final String entryId;
  final AuditEntryDto? initialEntry;

  const AuditEntryDetailPage({
    super.key,
    required this.entryId,
    this.initialEntry,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final detailAsync = ref.watch(auditEntryDetailProvider(entryId));
    final entry = detailAsync.valueOrNull ?? initialEntry;

    Widget body;
    if (entry != null) {
      body = _DetailBody(entry: entry);
    } else if (detailAsync.hasError) {
      final err = detailAsync.error!;
      body = AppErrorWidget(
        error: err,
        title: 'Action introuvable',
        message: err is AuditException ? err.message : null,
        onRetry: () => ref.invalidate(auditEntryDetailProvider(entryId)),
      );
    } else {
      body = const Center(child: CircularProgressIndicator());
    }

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
          'Détail de l\'action',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      body: body,
    );
  }
}

class _DetailBody extends ConsumerWidget {
  final AuditEntryDto entry;
  const _DetailBody({required this.entry});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final selfUserId = ref.watch(currentUserIdProvider).valueOrNull;
    final employees =
        ref.watch(auditEmployeeOptionsProvider).valueOrNull ?? const [];
    final info = AuditPresenter.actionInfo(entry.action);
    final sentence = AuditPresenter.sentence(entry, selfUserId: selfUserId);
    final changes = AuditPresenter.changes(entry);
    final context_ = AuditPresenter.context(entry);
    final isSelf = AuditPresenter.isSelf(entry, selfUserId);

    final String role;
    if (isSelf) {
      role = 'Propriétaire';
    } else if (employees.any((e) => e.id == entry.userId)) {
      role = 'Employé';
    } else {
      role = 'Non renseigné';
    }
    final actorName = isSelf
        ? 'Vous'
        : AuditPresenter.actorName(entry, selfUserId: selfUserId);
    final store = entry.storeName?.trim().isNotEmpty == true
        ? entry.storeName!
        : (entry.storeId != null ? 'Boutique inconnue' : 'Non renseignée');

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SoftIconBadge(
              icon: info.icon,
              color: auditCategoryColor(info.category, cs),
              size: 48,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                sentence.toString(),
                style: theme.textTheme.titleMedium
                    ?.copyWith(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        _Section(
          title: 'Qui',
          children: [
            _Line('Nom', actorName),
            _Line('Rôle', role),
            if (AuditPresenter.deviceLabel(entry) case final device?)
              _Line('Appareil', device),
          ],
        ),
        _Section(
          title: 'Quoi',
          children: [
            _Line('Action', info.label),
          ],
        ),
        _Section(
          title: 'Sur quoi',
          children: [
            _Line('Objet', AuditPresenter.objectLabel(entry)),
            if (entry.entityType == 'Sale')
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('audit-open-sale'),
                  onPressed: () =>
                      context.push('/pos/sales-history/${entry.entityId}'),
                  icon: const Icon(Icons.receipt_long_outlined, size: 18),
                  label: const Text('Voir la vente'),
                ),
              ),
          ],
        ),
        _Section(
          title: 'Quand et où',
          children: [
            _Line('Date', AuditPresenter.formatDateTime(entry.occurredAt)),
            _Line('Boutique', store),
            _Line('Source', AuditPresenter.sourceLabel(entry)),
          ],
        ),
        _Section(
          title: 'Changements',
          children: [
            if (changes.isEmpty)
              Text(
                'Aucun changement détaillé',
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: cs.onSurfaceVariant),
              )
            else
              _ChangesTable(changes),
          ],
        ),
        if (context_.isNotEmpty)
          _Section(
            title: 'Contexte',
            children: [
              for (final c in context_) _Line(c.label, c.value),
            ],
          ),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  final String title;
  final List<Widget> children;

  const _Section({required this.title, required this.children});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
        border: Border.all(color: cs.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: theme.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w700,
              color: cs.primary,
            ),
          ),
          const SizedBox(height: 8),
          ...children,
        ],
      ),
    );
  }
}

class _Line extends StatelessWidget {
  final String label;
  final String value;

  const _Line(this.label, this.value);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(child: Text(value, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}

class _ChangesTable extends StatelessWidget {
  final List<AuditChange> changes;
  const _ChangesTable(this.changes);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final head = theme.textTheme.labelSmall?.copyWith(
      color: cs.onSurfaceVariant,
      fontWeight: FontWeight.w700,
    );
    return Table(
      columnWidths: const {
        0: FlexColumnWidth(1.1),
        1: FlexColumnWidth(1),
        2: FlexColumnWidth(1),
      },
      defaultVerticalAlignment: TableCellVerticalAlignment.top,
      children: [
        TableRow(children: [
          _cell(Text('Champ', style: head)),
          _cell(Text('Avant', style: head)),
          _cell(Text('Après', style: head)),
        ]),
        for (final c in changes)
          TableRow(
            decoration: BoxDecoration(
              border: Border(top: BorderSide(color: cs.outlineVariant)),
            ),
            children: [
              _cell(Text(c.field, style: theme.textTheme.bodySmall)),
              _cell(Text(c.before,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: cs.onSurfaceVariant))),
              _cell(Text(c.after,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(fontWeight: FontWeight.w600))),
            ],
          ),
      ],
    );
  }

  static Widget _cell(Widget child) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 2),
        child: child,
      );
}
