import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/contact_validation.dart';
import 'package:keevo_core/widget/discard_changes_dialog.dart';

import '../../data/invoice_repository.dart';
import '../../domain/model/invoice.dart';
import '../../domain/model/invoicing_models.dart';
import '../invoicing_format.dart';
import '../provider/invoicing_providers.dart';

/// Story 15.5 — manual invoice: creation (`/invoicing/new`) or edit of a
/// draft (`/invoicing/:id/edit`). Client from the CRM picker when the CRM is
/// available (search as you type), else free-text name + phone; free lines
/// (designation, quantity, unit price, VAT treatment). Saved as a draft —
/// issuing is done from the invoice screen. Story 15.5b: offline, the draft
/// is saved on the device and synced later (free-text client: the CRM
/// picker needs the server).
class InvoiceFormPage extends ConsumerWidget {
  /// Draft to edit, null for a new invoice.
  final String? invoiceId;

  const InvoiceFormPage({super.key, this.invoiceId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = invoiceId;
    if (id == null) return const _InvoiceForm();
    final provider = invoiceByIdProvider(id);
    final invoice = ref.watch(provider);
    return invoice.when(
      data: (i) => i.isDraft
          ? _InvoiceForm(draft: i)
          : Scaffold(
              appBar: AppBar(title: const Text('Modifier la facture')),
              body: const Center(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: Text(
                    'Cette facture a été émise : elle ne peut plus être '
                    'modifiée.',
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            ),
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('Modifier la facture')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Scaffold(
        appBar: AppBar(title: const Text('Modifier la facture')),
        body: AppErrorWidget(error: e, onRetry: () => ref.invalidate(provider)),
      ),
    );
  }
}

/// Editable state of one line.
class _LineFields {
  final TextEditingController description;
  final TextEditingController quantity;
  final TextEditingController unitPrice;
  String vatTreatment;

  _LineFields({
    String description = '',
    String quantity = '1',
    String unitPrice = '',
    this.vatTreatment = kVatStandard,
  }) : description = TextEditingController(text: description),
       quantity = TextEditingController(text: quantity),
       unitPrice = TextEditingController(text: unitPrice);

  void dispose() {
    description.dispose();
    quantity.dispose();
    unitPrice.dispose();
  }
}

class _InvoiceForm extends ConsumerStatefulWidget {
  final Invoice? draft;

  const _InvoiceForm({this.draft});

  @override
  ConsumerState<_InvoiceForm> createState() => _InvoiceFormState();
}

class _InvoiceFormState extends ConsumerState<_InvoiceForm> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _phone;

  /// Whether the CRM picker is available, from the last answer of the
  /// server (kept while offline).
  bool _crmAvailable = false;
  late final TextEditingController _niu;
  late final TextEditingController _address;
  final List<_LineFields> _lines = [];
  String? _clientId;
  String _clientQuery = '';
  Timer? _debounce;
  bool _dirty = false;
  bool _saving = false;

  bool get _editing => widget.draft != null;

  @override
  void initState() {
    super.initState();
    final draft = widget.draft;
    final c = draft?.client;
    _name = TextEditingController(text: c?.name ?? '');
    _phone = TextEditingController(text: c?.phone ?? '');
    _niu = TextEditingController(text: c?.niu ?? '');
    _address = TextEditingController(text: c?.address ?? '');
    _clientId = c?.id;
    if (draft != null && draft.lines.isNotEmpty) {
      for (final l in draft.lines) {
        _lines.add(
          _LineFields(
            description: l.description,
            quantity: formatQuantity(l.quantity),
            unitPrice: '${l.unitPrice}',
            // An unknown treatment would crash the dropdown: standard VAT.
            vatTreatment: kVatTreatments.contains(l.vatTreatment)
                ? l.vatTreatment
                : kVatStandard,
          ),
        );
      }
    } else {
      _lines.add(_LineFields());
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _name.dispose();
    _phone.dispose();
    _niu.dispose();
    _address.dispose();
    for (final l in _lines) {
      l.dispose();
    }
    super.dispose();
  }

  void _touch() {
    if (!_dirty) setState(() => _dirty = true);
  }

  void _onNameChanged(String text) {
    _touch();
    // Typing over a CRM client unlinks it: the name is now free text.
    if (_clientId != null) setState(() => _clientId = null);
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (mounted) setState(() => _clientQuery = text.trim());
    });
  }

  void _pick(ClientSuggestion client) {
    setState(() {
      _clientId = client.id;
      _name.text = client.name;
      _phone.text = client.phone ?? '';
      _clientQuery = '';
      _dirty = true;
    });
    FocusScope.of(context).unfocus();
  }

  int get _linesTotal {
    var total = 0.0;
    for (final l in _lines) {
      final q = parseDecimal(l.quantity.text) ?? 0;
      final p = parseDecimal(l.unitPrice.text) ?? 0;
      total += q * p;
    }
    return total.round();
  }

  InvoiceDraftInput _input() => InvoiceDraftInput(
    clientId: _clientId,
    clientName: _name.text,
    // Story 15.5b — « 699000156 » → « +237699000156 », like the clients form
    clientPhone: normalizeContactPhone(_phone.text),
    clientNiu: _niu.text,
    clientAddress: _address.text,
    lines: [
      for (final l in _lines)
        InvoiceLineInput(
          description: l.description.text,
          quantity: parseDecimal(l.quantity.text) ?? 0,
          unitPrice: (parseDecimal(l.unitPrice.text) ?? 0).round(),
          vatTreatment: l.vatTreatment,
        ),
    ],
  );

  Future<void> _save() async {
    if (_saving || !(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _saving = true);
    final repository = ref.read(invoiceRepositoryProvider);
    try {
      final draft = widget.draft;
      if (draft == null) {
        final created = await repository.create(_input());
        if (!mounted) return;
        _dirty = false;
        showInvoicingInfo(
          context,
          created.pending
              ? 'Brouillon enregistré — en attente de synchronisation'
              : 'Brouillon enregistré',
        );
        context.pushReplacement(
          '/invoicing/${Uri.encodeComponent(created.id)}',
        );
      } else {
        final updated = await repository.update(draft.id, _input());
        if (!mounted) return;
        _dirty = false;
        showInvoicingInfo(
          context,
          updated.pending
              ? 'Brouillon enregistré — en attente de synchronisation'
              : 'Brouillon enregistré',
        );
        context.pop(true);
      }
    } catch (e) {
      if (mounted) showInvoicingError(context, e);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  String? _requiredQuantity(String? v) {
    final q = parseDecimal(v ?? '');
    if (q == null) return 'Quantité requise';
    if (q <= 0) return 'Doit être supérieure à 0';
    return null;
  }

  String? _requiredPrice(String? v) {
    final p = parseDecimal(v ?? '');
    if (p == null) return 'Prix requis';
    if (p < 0) return 'Ne peut pas être négatif';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final suggestions = ref.watch(clientSuggestionsProvider(_clientQuery));
    // the last answer counts while offline (the picker then says so)
    final known = suggestions.value?.crmAvailable;
    if (known != null) _crmAvailable = known;
    final crmAvailable = _crmAvailable;
    final theme = Theme.of(context);
    return PopScope(
      canPop: !_dirty,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final navigator = Navigator.of(context);
        if (await showDiscardChangesDialog(context)) {
          _dirty = false;
          navigator.pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(_editing ? 'Modifier le brouillon' : 'Nouvelle facture'),
        ),
        body: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Text(
                'Client',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              TextFormField(
                key: const Key('invoice-client-name'),
                controller: _name,
                onChanged: _onNameChanged,
                textCapitalization: TextCapitalization.words,
                maxLength: 200,
                decoration: InputDecoration(
                  labelText: 'Nom du client',
                  hintText: crmAvailable
                      ? 'Rechercher dans vos clients ou saisir un nom'
                      : null,
                  counterText: '',
                  prefixIcon: Icon(
                    _clientId != null
                        ? Icons.how_to_reg_rounded
                        : Icons.person_outline_rounded,
                  ),
                ),
                validator: (v) => (v == null || v.trim().isEmpty)
                    ? 'Le nom du client est requis'
                    : null,
              ),
              if (crmAvailable && _clientId == null && _clientQuery.isNotEmpty)
                _ClientSuggestionsList(suggestions: suggestions, onPick: _pick),
              const SizedBox(height: 12),
              TextFormField(
                key: const Key('invoice-client-phone'),
                controller: _phone,
                onChanged: (_) => _touch(),
                keyboardType: TextInputType.phone,
                maxLength: 30,
                // optional; when typed, a valid number (stored +237…)
                validator: (v) =>
                    v == null || v.trim().isEmpty ? null : contactPhoneError(v),
                decoration: const InputDecoration(
                  labelText: 'Téléphone (WhatsApp)',
                  hintText: '+237 6XX XX XX XX',
                  counterText: '',
                  prefixIcon: Icon(Icons.phone_outlined),
                ),
              ),
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: const Text('NIU et adresse du client'),
                initiallyExpanded:
                    _niu.text.isNotEmpty || _address.text.isNotEmpty,
                children: [
                  TextFormField(
                    controller: _niu,
                    onChanged: (_) => _touch(),
                    maxLength: 30,
                    decoration: const InputDecoration(
                      labelText: 'NIU du client',
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _address,
                    onChanged: (_) => _touch(),
                    maxLength: 300,
                    decoration: const InputDecoration(
                      labelText: 'Adresse du client',
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                'Lignes',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              for (var i = 0; i < _lines.length; i++)
                _LineCard(
                  key: ObjectKey(_lines[i]),
                  index: i,
                  fields: _lines[i],
                  canRemove: _lines.length > 1,
                  onChanged: () => setState(() => _dirty = true),
                  onRemove: () => setState(() {
                    _lines.removeAt(i).dispose();
                    _dirty = true;
                  }),
                  quantityValidator: _requiredQuantity,
                  priceValidator: _requiredPrice,
                ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('invoice-add-line'),
                  onPressed: _lines.length >= 200
                      ? null
                      : () => setState(() {
                          _lines.add(_LineFields());
                          _dirty = true;
                        }),
                  icon: const Icon(Icons.add_rounded),
                  label: const Text('Ajouter une ligne'),
                ),
              ),
              const Divider(),
              Row(
                children: [
                  const Expanded(child: Text('Montant des lignes')),
                  Text(
                    formatXaf(_linesTotal),
                    key: const Key('invoice-form-total'),
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                'La TVA est calculée selon vos informations de facturation.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: FilledButton.icon(
              key: const Key('invoice-save'),
              onPressed: _saving ? null : _save,
              icon: _saving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.save_outlined),
              label: const Text('Enregistrer le brouillon'),
            ),
          ),
        ),
      ),
    );
  }
}

/// No network (or the server unreachable): the CRM search cannot answer.
bool _offline(Object error) =>
    error is InvoiceUnavailableException ||
    (error is DioException &&
        (error.type == DioExceptionType.connectionError ||
            error.type == DioExceptionType.connectionTimeout));

class _ClientSuggestionsList extends StatelessWidget {
  final AsyncValue<ClientSuggestions> suggestions;
  final void Function(ClientSuggestion) onPick;

  const _ClientSuggestionsList({
    required this.suggestions,
    required this.onPick,
  });

  @override
  Widget build(BuildContext context) {
    final value = suggestions.value;
    if (suggestions.isLoading && value == null) {
      return const Padding(
        padding: EdgeInsets.all(8),
        child: LinearProgressIndicator(),
      );
    }
    if (suggestions.hasError && value == null) {
      if (_offline(suggestions.error!)) {
        return const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Text(
            'Hors ligne : le nom saisi sera utilisé.',
            key: Key('invoice-client-offline'),
          ),
        );
      }
      return AppErrorInline(error: suggestions.error!);
    }
    final clients = value?.clients ?? const [];
    if (clients.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 8),
        child: Text('Aucun client trouvé : le nom saisi sera utilisé.'),
      );
    }
    return Card(
      margin: const EdgeInsets.only(top: 4),
      child: Column(
        children: [
          for (final c in clients.take(8))
            ListTile(
              dense: true,
              leading: const Icon(Icons.person_rounded),
              title: Text(c.name),
              subtitle: c.phone == null ? null : Text(c.phone!),
              onTap: () => onPick(c),
            ),
        ],
      ),
    );
  }
}

class _LineCard extends StatelessWidget {
  final int index;
  final _LineFields fields;
  final bool canRemove;
  final VoidCallback onChanged;
  final VoidCallback onRemove;
  final FormFieldValidator<String> quantityValidator;
  final FormFieldValidator<String> priceValidator;

  const _LineCard({
    super.key,
    required this.index,
    required this.fields,
    required this.canRemove,
    required this.onChanged,
    required this.onRemove,
    required this.quantityValidator,
    required this.priceValidator,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 4, 12),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Ligne ${index + 1}',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                if (canRemove)
                  IconButton(
                    tooltip: 'Retirer la ligne',
                    icon: const Icon(Icons.delete_outline_rounded),
                    onPressed: onRemove,
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Column(
                children: [
                  TextFormField(
                    key: Key('invoice-line-$index-description'),
                    controller: fields.description,
                    onChanged: (_) => onChanged(),
                    maxLength: 500,
                    decoration: const InputDecoration(
                      labelText: 'Désignation',
                      counterText: '',
                    ),
                    validator: (v) => (v == null || v.trim().isEmpty)
                        ? 'La désignation est requise'
                        : null,
                  ),
                  const SizedBox(height: 8),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: TextFormField(
                          key: Key('invoice-line-$index-quantity'),
                          controller: fields.quantity,
                          onChanged: (_) => onChanged(),
                          keyboardType: const TextInputType.numberWithOptions(
                            decimal: true,
                          ),
                          inputFormatters: [
                            FilteringTextInputFormatter.allow(
                              RegExp(r'[0-9.,]'),
                            ),
                          ],
                          decoration: const InputDecoration(
                            labelText: 'Quantité',
                          ),
                          validator: quantityValidator,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        flex: 2,
                        child: TextFormField(
                          key: Key('invoice-line-$index-price'),
                          controller: fields.unitPrice,
                          onChanged: (_) => onChanged(),
                          keyboardType: TextInputType.number,
                          inputFormatters: [
                            FilteringTextInputFormatter.digitsOnly,
                          ],
                          decoration: const InputDecoration(
                            labelText: 'Prix unitaire',
                            suffixText: 'XAF',
                          ),
                          validator: priceValidator,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String>(
                    initialValue: fields.vatTreatment,
                    decoration: const InputDecoration(labelText: 'TVA'),
                    items: [
                      for (final t in kVatTreatments)
                        DropdownMenuItem(
                          value: t,
                          child: Text(vatTreatmentLabel(t)),
                        ),
                    ],
                    onChanged: (v) {
                      if (v == null) return;
                      fields.vatTreatment = v;
                      onChanged();
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
