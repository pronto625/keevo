import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/widget/app_error_widget.dart';

import '../../domain/model/invoice.dart';
import '../../domain/model/invoicing_models.dart';
import '../invoicing_format.dart';
import '../provider/invoicing_providers.dart';

/// Story 15.5 — opens the credit note sheet of [invoice] (owner, issued or
/// paid invoice); resolves to the created credit note, null when closed.
Future<CreditNote?> showCreditNoteFormSheet(
  BuildContext context,
  Invoice invoice,
) {
  return showModalBottomSheet<CreditNote>(
    context: context,
    isScrollControlled: true,
    // Not dismissible by a drag while saving: PopScope below covers the
    // barrier tap and the back button, a drag would bypass it.
    enableDrag: false,
    builder: (_) => CreditNoteFormSheet(invoice: invoice),
  );
}

/// « Créer un avoir »: full (everything still to credit) or per line with
/// quantities up to what remains on each line; the reason is required. The
/// server checks the quantities again (its French message is shown).
class CreditNoteFormSheet extends ConsumerStatefulWidget {
  final Invoice invoice;

  const CreditNoteFormSheet({super.key, required this.invoice});

  @override
  ConsumerState<CreditNoteFormSheet> createState() =>
      _CreditNoteFormSheetState();
}

class _CreditNoteFormSheetState extends ConsumerState<CreditNoteFormSheet> {
  final _reason = TextEditingController();
  final Map<int, TextEditingController> _quantities = {};
  bool _partial = false;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    for (final c in _quantities.values) {
      c.dispose();
    }
    super.dispose();
  }

  TextEditingController _quantity(int position) =>
      _quantities.putIfAbsent(position, () => TextEditingController());

  Future<void> _submit(Map<int, num> credited) async {
    final reason = _reason.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'Le motif de l\'avoir est requis');
      return;
    }
    List<CreditNoteSelection>? lines;
    if (_partial) {
      lines = [];
      for (final line in widget.invoice.lines) {
        final text = _quantities[line.position]?.text ?? '';
        if (text.trim().isEmpty) continue;
        final q = parseDecimal(text);
        final remaining = remainingQuantity(
          line.quantity,
          credited[line.position] ?? 0,
        );
        if (q == null || q < 0) {
          setState(() => _error = 'Quantité invalide : ${line.description}');
          return;
        }
        if (toThousandths(q) > toThousandths(remaining)) {
          setState(
            () => _error =
                'Au plus ${formatQuantity(remaining)} pour « ${line.description} »',
          );
          return;
        }
        if (q > 0) lines.add((position: line.position, quantity: q));
      }
      if (lines.isEmpty) {
        setState(() => _error = 'Indiquez au moins une quantité à créditer');
        return;
      }
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final note = await ref
          .read(invoiceDataSourceProvider)
          .createCreditNote(widget.invoice.id, reason: reason, lines: lines);
      if (mounted) Navigator.of(context).pop(note);
    } catch (e) {
      if (mounted) setState(() => _error = invoicingErrorMessage(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final invoice = widget.invoice;
    final provider = creditedQuantitiesProvider(creditNoteKey(invoice));
    final credited = ref.watch(provider);
    final theme = Theme.of(context);
    return PopScope(
      // Never closed while the credit note is being created.
      canPop: !_saving,
      child: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Créer un avoir',
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Reste à créditer : ${formatXaf(invoice.remainingTtc)}',
                  key: const Key('credit-note-remaining'),
                ),
                const SizedBox(height: 12),
                SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(value: false, label: Text('Avoir total')),
                    ButtonSegment(value: true, label: Text('Par ligne')),
                  ],
                  selected: {_partial},
                  onSelectionChanged: (s) => setState(() {
                    _partial = s.first;
                    _error = null;
                  }),
                ),
                const SizedBox(height: 12),
                if (_partial)
                  credited.when(
                    data: (c) => Column(
                      children: [
                        for (final line in invoice.lines)
                          _LineQuantity(
                            line: line,
                            remaining: remainingQuantity(
                              line.quantity,
                              c[line.position] ?? 0,
                            ),
                            controller: _quantity(line.position),
                          ),
                      ],
                    ),
                    loading: () => const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                    error: (e, _) => Column(
                      children: [
                        AppErrorInline(error: e),
                        TextButton(
                          onPressed: () => ref.invalidate(provider),
                          child: const Text('Réessayer'),
                        ),
                      ],
                    ),
                  )
                else
                  const Text(
                    'Tout ce qui reste à créditer sur la facture sera crédité.',
                  ),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('credit-note-reason'),
                  controller: _reason,
                  maxLength: 300,
                  maxLines: 2,
                  minLines: 1,
                  decoration: const InputDecoration(
                    labelText: 'Motif de l\'avoir',
                    hintText: 'Retour de marchandise, erreur de prix…',
                  ),
                ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      _error!,
                      key: const Key('credit-note-error'),
                      style: TextStyle(color: theme.colorScheme.error),
                    ),
                  ),
                FilledButton(
                  key: const Key('credit-note-submit'),
                  onPressed: _saving || (_partial && !credited.hasValue)
                      ? null
                      : () => _submit(credited.value ?? const {}),
                  child: _saving
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Créer l\'avoir'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _LineQuantity extends StatelessWidget {
  final InvoiceLine line;
  final num remaining;
  final TextEditingController controller;

  const _LineQuantity({
    required this.line,
    required this.remaining,
    required this.controller,
  });

  @override
  Widget build(BuildContext context) {
    final none = remaining <= 0;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.description, maxLines: 2),
                Text(
                  none
                      ? 'Déjà entièrement créditée'
                      : 'Reste ${formatQuantity(remaining)} sur '
                            '${formatQuantity(line.quantity)}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 90,
            child: TextField(
              key: Key('credit-note-line-${line.position}'),
              controller: controller,
              enabled: !none,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]')),
              ],
              decoration: const InputDecoration(labelText: 'Qté'),
            ),
          ),
        ],
      ),
    );
  }
}
