import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';

import '../../domain/model/invoice.dart';
import '../invoicing_format.dart';
import '../provider/invoicing_providers.dart';
import '../widget/credit_note_form_sheet.dart';

/// Story 15.3 — invoice screen (`/invoicing/:id`, route of the Invoicing
/// module). Story 15.5: the whole invoice (client, lines, totals, payment,
/// WhatsApp sendings, credit notes) and its actions by status — draft:
/// edit, issue (compliance warnings first), delete; issued: record the
/// payment (chosen date), send by WhatsApp, PDF, cancel (owner), credit note
/// (owner); paid: PDF, WhatsApp, credit note (owner). Story 15.5b: read from
/// the device; offline, creation, edit, issue, payment and cancellation are
/// written locally and synced later (« En attente de synchronisation », the
/// number comes with the sync); PDF, WhatsApp, credit notes and deleting a
/// draft need the server (« Disponible une fois connecté »).
class InvoiceDetailPage extends ConsumerWidget {
  final String invoiceId;

  const InvoiceDetailPage({super.key, required this.invoiceId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = invoiceByIdProvider(invoiceId);
    final invoice = ref.watch(provider);
    return Scaffold(
      appBar: AppBar(title: const Text('Facture')),
      body: invoice.when(
        data: (i) => _InvoiceContent(invoice: i),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) =>
            AppErrorWidget(error: e, onRetry: () => ref.invalidate(provider)),
      ),
    );
  }
}

/// Common payment methods offered by the payment sheet.
const kPaymentMethods = ['Espèces', 'Mobile Money', 'Virement', 'Chèque'];

class _InvoiceContent extends ConsumerStatefulWidget {
  final Invoice invoice;

  const _InvoiceContent({required this.invoice});

  @override
  ConsumerState<_InvoiceContent> createState() => _InvoiceContentState();
}

class _InvoiceContentState extends ConsumerState<_InvoiceContent> {
  /// Action in progress (one at a time), null when idle.
  String? _busy;

  Invoice get _invoice => widget.invoice;

  void _refresh() => ref.invalidate(invoiceByIdProvider(_invoice.id));

  /// Runs [action] with the busy state; French snackbar on failure.
  Future<T?> _run<T>(String name, Future<T> Function() action) async {
    if (_busy != null) return null;
    setState(() => _busy = name);
    try {
      return await action();
    } catch (e) {
      if (mounted) showInvoicingError(context, e);
      return null;
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _openPdf() =>
      _run('pdf', () => ref.read(invoicePdfOpenerProvider)(_invoice));

  Future<void> _edit() async {
    final saved = await context.push<bool>(
      '/invoicing/${Uri.encodeComponent(_invoice.id)}/edit',
    );
    if (saved == true && mounted) _refresh();
  }

  Future<bool> _serverReady() async =>
      await _run('check', () async {
        await ref.read(invoiceRepositoryProvider).requireServer(_invoice.id);
        return true;
      }) ==
      true;

  Future<void> _issue() async {
    final isOwner = ref.read(isOwnerSessionProvider);
    // Story 15.5b — offline, the server checks compliance at the sync
    final offline = !await ref
        .read(invoiceRepositoryProvider)
        .canReachServer(_invoice.id);
    if (!mounted) return;
    final choice = await showDialog<_IssueChoice>(
      context: context,
      builder: (_) => _IssueDialog(
        warnings: _invoice.complianceWarnings,
        canEditSettings: isOwner,
        offline: offline,
      ),
    );
    if (!mounted || choice == null) return;
    if (choice == _IssueChoice.settings) {
      await context.push('/invoicing/settings');
      if (mounted) _refresh(); // warnings recomputed with the new settings
      return;
    }
    final issued = await _run(
      'issue',
      () => ref.read(invoiceRepositoryProvider).issue(_invoice.id),
    );
    if (issued != null && mounted) {
      showInvoicingInfo(
        context,
        issued.number == null
            ? 'Facture émise — numéro attribué à la synchronisation'
            : 'Facture n° ${issued.number} émise',
      );
    }
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Supprimer le brouillon ?'),
        content: const Text('Ce brouillon de facture sera supprimé.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Annuler'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final done = await _run('delete', () async {
      await ref.read(invoiceRepositoryProvider).delete(_invoice.id);
      return true;
    });
    if (done == true && mounted) {
      showInvoicingInfo(context, 'Brouillon supprimé');
      if (context.canPop()) {
        context.pop();
      } else {
        context.go('/invoicing');
      }
    }
  }

  Future<void> _pay() async {
    final payment = await showModalBottomSheet<_Payment>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _PaySheet(issueDate: _invoice.issueDate),
    );
    if (payment == null || !mounted) return;
    final paid = await _run(
      'pay',
      () => ref
          .read(invoiceRepositoryProvider)
          .pay(_invoice.id, method: payment.method, paidOn: payment.day),
    );
    if (paid != null && mounted) {
      showInvoicingInfo(
        context,
        paid.pending
            ? 'Paiement enregistré — en attente de synchronisation'
            : 'Paiement enregistré',
      );
    }
  }

  Future<void> _send() async {
    if (!await _serverReady() || !mounted) return;
    final phone = await showDialog<String>(
      context: context,
      builder: (_) => _SendDialog(initialPhone: _invoice.client.phone),
    );
    if (phone == null || !mounted) return;
    final sent = await _run('send', () async {
      final sent = await ref
          .read(invoiceDataSourceProvider)
          .send(_invoice.id, phone: phone);
      await ref.read(invoiceRepositoryProvider).saveServer(sent);
      return sent;
    });
    if (sent != null && mounted) {
      showInvoicingInfo(context, 'Facture envoyée');
    }
  }

  Future<void> _cancel() async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const _ReasonDialog(
        title: 'Annuler la facture ?',
        message:
            'La facture garde son numéro et apparaît comme annulée. '
            'Cette action est définitive.',
        label: 'Motif d\'annulation',
        confirm: 'Annuler la facture',
      ),
    );
    if (reason == null || !mounted) return;
    final cancelled = await _run(
      'cancel',
      () => ref.read(invoiceRepositoryProvider).cancel(_invoice.id, reason),
    );
    if (cancelled != null && mounted) {
      showInvoicingInfo(
        context,
        cancelled.pending
            ? 'Facture annulée — en attente de synchronisation'
            : 'Facture annulée',
      );
    }
  }

  Future<void> _creditNote() async {
    // Credit notes need the server's remaining quantities (online only).
    if (!await _serverReady() || !mounted) return;
    final note = await showCreditNoteFormSheet(context, _invoice);
    if (note == null || !mounted) return;
    showInvoicingInfo(
      context,
      note.number == null ? 'Avoir créé' : 'Avoir ${note.number} créé',
    );
    _refresh();
  }

  Future<void> _openCreditNotePdf(CreditNoteSummary note) =>
      _run('pdf-${note.id}', () => ref.read(creditNotePdfOpenerProvider)(note));

  @override
  Widget build(BuildContext context) {
    final invoice = _invoice;
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final isOwner = ref.watch(isOwnerSessionProvider);
    final client = invoice.clientName;
    final idle = _busy == null;
    final status = invoice.status;
    final canCredit =
        isOwner && invoice.isIssuedOrPaid && !invoice.fullyCredited;
    final canCancel =
        isOwner && status == kInvoiceIssued && invoice.creditNotes.isEmpty;

    Widget busyIcon(String name, IconData icon) => _busy == name
        ? const SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(strokeWidth: 2),
          )
        : Icon(icon);

    return RefreshIndicator(
      onRefresh: () async {
        _refresh();
        await ref.read(invoiceByIdProvider(invoice.id).future);
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          Text(
            invoice.number != null
                ? 'Facture n° ${invoice.number}'
                : invoice.isDraft
                ? 'Facture (brouillon)'
                : 'Facture émise',
            key: const Key('invoice-number'),
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              InvoiceBadge(
                key: const Key('invoice-status'),
                label: invoice.statusLabel,
                color: invoiceStatusColor(status, cs),
              ),
              if (invoice.supersededAt != null)
                InvoiceBadge(label: 'Remplacée', color: cs.tertiary),
              if (invoice.fullyCredited)
                InvoiceBadge(
                  label: 'Entièrement créditée',
                  color: cs.secondary,
                ),
              if (invoice.pending)
                InvoiceBadge(
                  key: const Key('invoice-pending'),
                  label: kPendingSyncLabel,
                  color: cs.outline,
                ),
            ],
          ),
          if (invoice.pending && invoice.isDraft) ...[
            const SizedBox(height: 8),
            Text(
              'Montants définitifs calculés à la synchronisation.',
              style: theme.textTheme.bodySmall,
            ),
          ],
          if (invoice.issueDate != null) ...[
            const SizedBox(height: 8),
            Text('Émise le ${formatDay(invoice.issueDate!)}'),
          ],
          if (invoice.isDraft && invoice.complianceWarnings.isNotEmpty) ...[
            const SizedBox(height: 12),
            _WarningsCard(warnings: invoice.complianceWarnings),
          ],
          const SizedBox(height: 8),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.person_outline_rounded),
            title: const Text('Client'),
            subtitle: Text(
              [
                client == null || client.isEmpty ? 'Non renseigné' : client,
                if (invoice.client.phone != null) invoice.client.phone!,
              ].join('\n'),
            ),
          ),
          const Divider(),
          for (final line in invoice.lines)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(line.description),
                        Text(
                          '${formatQuantity(line.quantity)} × '
                          '${formatXaf(line.unitPrice)}'
                          '${line.vatTreatment == kVatStandard ? '' : ' · ${vatTreatmentLabel(line.vatTreatment)}'}',
                          style: theme.textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  Text(formatXaf(line.totalTtc)),
                ],
              ),
            ),
          if (invoice.lines.isNotEmpty) const Divider(),
          _AmountRow(label: 'Total HT', amount: invoice.totalHt),
          _AmountRow(label: 'TVA', amount: invoice.totalVat),
          _AmountRow(
            label: 'Total TTC',
            amount: invoice.totalTtc,
            strong: true,
            amountKey: const Key('invoice-total'),
          ),
          if (invoice.paidAt != null) ...[
            const SizedBox(height: 12),
            Text(
              'Payée le ${formatDay(invoice.paidAt!)}'
              '${invoice.paymentMethod == null ? '' : ' — ${invoice.paymentMethod}'}',
              key: const Key('invoice-paid'),
              style: const TextStyle(
                color: AppTheme.successStrong,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
          if (invoice.cancelledAt != null) ...[
            const SizedBox(height: 12),
            Text(
              'Annulée le ${formatDay(invoice.cancelledAt!)}'
              '${invoice.cancellationReason == null ? '' : ' : ${invoice.cancellationReason}'}',
              style: TextStyle(color: cs.error),
            ),
          ],
          if (invoice.sendCount > 0 && invoice.lastSentAt != null) ...[
            const SizedBox(height: 12),
            Text(
              '${invoice.sendCount == 1 ? 'Envoyée 1 fois' : 'Envoyée ${invoice.sendCount} fois'}'
              ' — dernier envoi le ${formatDateTime(invoice.lastSentAt!)}'
              '${invoice.lastSentTo == null ? '' : ' au ${invoice.lastSentTo}'}',
              key: const Key('invoice-sent'),
              style: theme.textTheme.bodySmall,
            ),
          ],
          if (invoice.creditNotes.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(
              'Avoirs',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            for (final note in invoice.creditNotes)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.receipt_outlined),
                title: Text(note.number ?? 'Avoir'),
                subtitle: Text(
                  [
                    formatXaf(note.totalTtc),
                    if (note.issuedAt != null) formatDay(note.issuedAt!),
                    if (note.reason != null) note.reason!,
                  ].join(' · '),
                ),
                trailing: IconButton(
                  tooltip: 'Voir le PDF de l\'avoir',
                  onPressed: idle ? () => _openCreditNotePdf(note) : null,
                  icon: busyIcon(
                    'pdf-${note.id}',
                    Icons.picture_as_pdf_outlined,
                  ),
                ),
              ),
            _AmountRow(label: 'Total crédité', amount: invoice.creditedTtc),
            _AmountRow(
              label: 'Reste à créditer',
              amount: invoice.remainingTtc,
              amountKey: const Key('invoice-remaining'),
            ),
          ],
          const SizedBox(height: 24),
          if (invoice.isDraft) ...[
            FilledButton.icon(
              key: const Key('invoice-issue'),
              onPressed: idle ? _issue : null,
              icon: busyIcon('issue', Icons.verified_outlined),
              label: const Text('Émettre la facture'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              key: const Key('invoice-edit'),
              onPressed: idle ? _edit : null,
              icon: const Icon(Icons.edit_outlined),
              label: const Text('Modifier'),
            ),
            const SizedBox(height: 8),
            TextButton.icon(
              key: const Key('invoice-delete'),
              onPressed: idle ? _delete : null,
              icon: busyIcon('delete', Icons.delete_outline_rounded),
              label: const Text('Supprimer le brouillon'),
            ),
          ],
          if (status == kInvoiceIssued) ...[
            FilledButton.icon(
              key: const Key('invoice-pay'),
              onPressed: idle ? _pay : null,
              icon: busyIcon('pay', Icons.payments_outlined),
              label: const Text('Enregistrer le paiement'),
            ),
            const SizedBox(height: 8),
          ],
          if (invoice.isIssuedOrPaid) ...[
            OutlinedButton.icon(
              key: const Key('invoice-send'),
              onPressed: idle ? _send : null,
              icon: busyIcon('send', Icons.send_rounded),
              label: const Text('Envoyer par WhatsApp'),
            ),
            const SizedBox(height: 8),
          ],
          if (!invoice.isDraft)
            OutlinedButton.icon(
              onPressed: idle ? _openPdf : null,
              icon: busyIcon('pdf', Icons.picture_as_pdf_outlined),
              label: const Text('Voir le PDF'),
            )
          else
            TextButton.icon(
              onPressed: idle ? _openPdf : null,
              icon: busyIcon('pdf', Icons.picture_as_pdf_outlined),
              label: const Text('Voir le PDF'),
            ),
          if (canCredit) ...[
            const SizedBox(height: 8),
            OutlinedButton.icon(
              key: const Key('invoice-credit-note'),
              onPressed: idle ? _creditNote : null,
              icon: const Icon(Icons.undo_rounded),
              label: const Text('Créer un avoir'),
            ),
          ],
          if (canCancel) ...[
            const SizedBox(height: 8),
            TextButton.icon(
              key: const Key('invoice-cancel'),
              style: TextButton.styleFrom(foregroundColor: cs.error),
              onPressed: idle ? _cancel : null,
              icon: busyIcon('cancel', Icons.block_rounded),
              label: const Text('Annuler la facture'),
            ),
          ],
        ],
      ),
    );
  }
}

class _AmountRow extends StatelessWidget {
  final String label;
  final int amount;
  final bool strong;
  final Key? amountKey;

  const _AmountRow({
    required this.label,
    required this.amount,
    this.strong = false,
    this.amountKey,
  });

  @override
  Widget build(BuildContext context) {
    final style = strong
        ? Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)
        : null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(formatXaf(amount), key: amountKey, style: style),
        ],
      ),
    );
  }
}

class _WarningsCard extends StatelessWidget {
  final List<String> warnings;

  const _WarningsCard({required this.warnings});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Mentions manquantes',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                color: cs.onTertiaryContainer,
              ),
            ),
            const SizedBox(height: 4),
            for (final w in warnings)
              Text('• $w', style: TextStyle(color: cs.onTertiaryContainer)),
          ],
        ),
      ),
    );
  }
}

enum _IssueChoice { issue, settings }

/// Confirmation of the issue; lists the compliance warnings first, with
/// « Compléter mes informations » for the owner.
class _IssueDialog extends StatelessWidget {
  final List<String> warnings;
  final bool canEditSettings;

  /// Issued offline: numbered and checked by the server at the sync.
  final bool offline;

  const _IssueDialog({
    required this.warnings,
    required this.canEditSettings,
    this.offline = false,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const Key('invoice-issue-dialog'),
      title: const Text('Émettre la facture ?'),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Elle recevra un numéro et ne pourra plus être modifiée.',
            ),
            if (offline) ...[
              const SizedBox(height: 12),
              const Text(
                'Hors ligne : numéro attribué à la synchronisation. '
                'Conformité vérifiée à la synchronisation.',
                key: Key('invoice-issue-offline'),
              ),
            ],
            if (warnings.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Text(
                'Mentions manquantes sur la facture :',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 4),
              for (final w in warnings) Text('• $w'),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Annuler'),
        ),
        if (warnings.isNotEmpty && canEditSettings)
          TextButton(
            key: const Key('invoice-issue-settings'),
            onPressed: () => Navigator.pop(context, _IssueChoice.settings),
            child: const Text('Compléter mes informations'),
          ),
        FilledButton(
          key: const Key('invoice-issue-confirm'),
          onPressed: () => Navigator.pop(context, _IssueChoice.issue),
          child: Text(warnings.isEmpty ? 'Émettre' : 'Émettre quand même'),
        ),
      ],
    );
  }
}

/// [day] null = paid today (the server's day: no `paidAt` sent).
typedef _Payment = ({DateTime? day, String method});

/// « Enregistrer le paiement »: the day the client paid (today in
/// Africa/Douala by default, never in the future nor before the issue date)
/// and the method, chosen by the user.
class _PaySheet extends StatefulWidget {
  final DateTime? issueDate;

  const _PaySheet({required this.issueDate});

  @override
  State<_PaySheet> createState() => _PaySheetState();
}

class _PaySheetState extends State<_PaySheet> {
  final DateTime _today = doualaToday();
  late DateTime _day = _today;
  String? _method;

  DateTime get _first {
    final issue = widget.issueDate;
    if (issue == null || issue.isAfter(_today)) return _today;
    return DateUtils.dateOnly(issue);
  }

  Future<void> _pickDay() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _day,
      firstDate: _first,
      lastDate: _today,
      helpText: 'Date du paiement',
    );
    if (picked != null && mounted) {
      setState(() => _day = DateUtils.dateOnly(picked));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final method = _method;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Enregistrer le paiement',
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 12),
            ListTile(
              key: const Key('pay-date'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event_rounded),
              title: const Text('Date du paiement'),
              subtitle: Text(_day == _today ? 'Aujourd\'hui' : formatDay(_day)),
              trailing: const Icon(Icons.edit_calendar_outlined),
              onTap: _pickDay,
            ),
            const SizedBox(height: 8),
            const Text('Mode de paiement'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                for (final m in kPaymentMethods)
                  ChoiceChip(
                    label: Text(m),
                    selected: _method == m,
                    onSelected: (s) => setState(() => _method = s ? m : null),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            FilledButton(
              key: const Key('pay-confirm'),
              // The method is chosen by the user (none preselected).
              onPressed: method == null
                  ? null
                  : () => Navigator.pop<_Payment>(context, (
                      day: _day == _today ? null : _day,
                      method: method,
                    )),
              child: const Text('Enregistrer'),
            ),
          ],
        ),
      ),
    );
  }
}

/// « Envoyer par WhatsApp »: the client's phone prefilled, editable.
class _SendDialog extends StatefulWidget {
  final String? initialPhone;

  const _SendDialog({this.initialPhone});

  @override
  State<_SendDialog> createState() => _SendDialogState();
}

class _SendDialogState extends State<_SendDialog> {
  late final _phone = TextEditingController(text: widget.initialPhone ?? '');
  String? _error;

  @override
  void dispose() {
    _phone.dispose();
    super.dispose();
  }

  void _submit() {
    final phone = _phone.text.trim();
    if (phone.isEmpty) {
      setState(() => _error = 'Indiquez le numéro WhatsApp du client');
      return;
    }
    Navigator.pop(context, phone);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Envoyer par WhatsApp'),
      content: TextField(
        key: const Key('send-phone'),
        controller: _phone,
        keyboardType: TextInputType.phone,
        maxLength: 30,
        autofocus: widget.initialPhone == null,
        decoration: InputDecoration(
          labelText: 'Numéro WhatsApp du client',
          hintText: '+237 6XX XX XX XX',
          errorText: _error,
          counterText: '',
        ),
        onSubmitted: (_) => _submit(),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Annuler'),
        ),
        FilledButton(
          key: const Key('send-confirm'),
          onPressed: _submit,
          child: const Text('Envoyer'),
        ),
      ],
    );
  }
}

/// Asks a required reason; resolves to it, null when dismissed.
class _ReasonDialog extends StatefulWidget {
  final String title;
  final String message;
  final String label;
  final String confirm;

  const _ReasonDialog({
    required this.title,
    required this.message,
    required this.label,
    required this.confirm,
  });

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _reason = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.message),
          const SizedBox(height: 12),
          TextField(
            key: const Key('reason-field'),
            controller: _reason,
            maxLength: 500,
            decoration: InputDecoration(
              labelText: widget.label,
              errorText: _error,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Retour'),
        ),
        FilledButton(
          key: const Key('reason-confirm'),
          onPressed: () {
            final text = _reason.text.trim();
            if (text.isEmpty) {
              setState(() => _error = 'Le motif est requis');
              return;
            }
            Navigator.pop(context, text);
          },
          child: Text(widget.confirm),
        ),
      ],
    );
  }
}
