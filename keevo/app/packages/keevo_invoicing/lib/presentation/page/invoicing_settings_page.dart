import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/discard_changes_dialog.dart';

import '../../domain/model/invoicing_models.dart';
import '../invoicing_format.dart';
import '../provider/invoicing_providers.dart';

/// Story 15.5 — « Mes informations de facturation » (`/invoicing/settings`):
/// the seller profile printed on each invoice when it is issued (legal name,
/// address, phone, NIU, RCCM, VAT, payment details). Editable by the owner;
/// the server's validation messages are shown under their field.
class InvoicingSettingsPage extends ConsumerWidget {
  const InvoicingSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(invoicingSettingsProvider);
    return settings.when(
      data: (s) => _SettingsForm(initial: s),
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('Informations de facturation')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Scaffold(
        appBar: AppBar(title: const Text('Informations de facturation')),
        body: AppErrorWidget(
          error: e,
          onRetry: () => ref.invalidate(invoicingSettingsProvider),
        ),
      ),
    );
  }
}

class _SettingsForm extends ConsumerStatefulWidget {
  final InvoicingSettings initial;

  const _SettingsForm({required this.initial});

  @override
  ConsumerState<_SettingsForm> createState() => _SettingsFormState();
}

class _SettingsFormState extends ConsumerState<_SettingsForm> {
  late final _legalName = TextEditingController(text: widget.initial.legalName);
  late final _address = TextEditingController(text: widget.initial.address);
  late final _phone = TextEditingController(text: widget.initial.phone);
  late final _niu = TextEditingController(text: widget.initial.niu);
  late final _rccm = TextEditingController(text: widget.initial.rccm);
  late final _vatRate = TextEditingController(
    text: formatQuantity(widget.initial.vatRate),
  );
  late final _paymentDetails = TextEditingController(
    text: widget.initial.paymentDetails,
  );
  late final _footer = TextEditingController(text: widget.initial.footer);
  late bool _vatRegistered = widget.initial.vatRegistered;
  late bool _pricesIncludeVat = widget.initial.pricesIncludeVat;
  Map<String, String> _errors = const {};
  bool _dirty = false;
  bool _saving = false;

  @override
  void dispose() {
    for (final c in [
      _legalName,
      _address,
      _phone,
      _niu,
      _rccm,
      _vatRate,
      _paymentDetails,
      _footer,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  void _touch() {
    if (!_dirty) setState(() => _dirty = true);
  }

  Future<void> _save() async {
    // The rate only matters for a VAT-registered seller: left blank
    // otherwise, the stored one is kept (the server always needs a value).
    final typed = parseDecimal(_vatRate.text);
    final blank = _vatRate.text.trim().isEmpty;
    final num rate;
    if (typed != null) {
      rate = typed;
    } else if (blank && !_vatRegistered) {
      rate = widget.initial.vatRate;
    } else {
      setState(
        () => _errors = {
          'vatRate': blank
              ? 'Le taux de TVA est requis'
              : 'Le taux de TVA n\'est pas un nombre',
        },
      );
      return;
    }
    if (rate < 0 || rate >= 100) {
      setState(
        () => _errors = {
          'vatRate': 'Le taux de TVA doit être compris entre 0 et 100 %',
        },
      );
      return;
    }
    setState(() {
      _saving = true;
      _errors = const {};
    });
    try {
      await ref
          .read(invoiceDataSourceProvider)
          .saveSettings(
            InvoicingSettings(
              legalName: _legalName.text,
              address: _address.text,
              phone: _phone.text,
              niu: _niu.text,
              rccm: _rccm.text,
              vatRegistered: _vatRegistered,
              vatRate: rate,
              pricesIncludeVat: _pricesIncludeVat,
              paymentDetails: _paymentDetails.text,
              footer: _footer.text,
            ),
          );
      if (!mounted) return;
      _dirty = false;
      showInvoicingInfo(context, 'Informations de facturation enregistrées');
      ref.invalidate(invoicingSettingsProvider);
      if (context.canPop()) context.pop(true);
    } catch (e) {
      if (!mounted) return;
      final fields = serverFieldErrors(e);
      if (fields.isNotEmpty) {
        setState(() {
          _errors = {
            for (final f in fields.entries)
              // @AssertTrue isVatRateConsistent → the rate field.
              (f.key == 'vatRateConsistent' ? 'vatRate' : f.key): f.value,
          };
        });
      } else {
        showInvoicingError(context, e);
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Widget _field(
    String key,
    TextEditingController controller,
    String label, {
    int maxLength = 200,
    int maxLines = 1,
    String? helper,
    TextInputType? keyboardType,
    List<TextInputFormatter>? formatters,
    bool enabled = true,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        key: Key('invoicing-settings-$key'),
        controller: controller,
        enabled: enabled,
        onChanged: (_) => _touch(),
        maxLength: maxLength,
        maxLines: maxLines,
        minLines: 1,
        keyboardType: keyboardType,
        inputFormatters: formatters,
        decoration: InputDecoration(
          labelText: label,
          helperText: helper,
          errorText: _errors[key],
          counterText: '',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isOwner = ref.watch(isOwnerSessionProvider);
    final generalError = _errors.entries
        .where(
          (e) => !const {
            'legalName',
            'address',
            'phone',
            'niu',
            'rccm',
            'vatRate',
            'paymentDetails',
            'footer',
          }.contains(e.key),
        )
        .map((e) => e.value)
        .toList();
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
        appBar: AppBar(title: const Text('Informations de facturation')),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
          children: [
            Text(
              'Imprimées sur chaque facture au moment de son émission. Les '
              'factures déjà émises ne changent pas.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (!isOwner) ...[
              const SizedBox(height: 8),
              const Text(
                'Seul le propriétaire peut modifier ces informations.',
                key: Key('invoicing-settings-read-only'),
              ),
            ],
            const SizedBox(height: 16),
            _field(
              'legalName',
              _legalName,
              'Nom du commerce',
              enabled: isOwner,
            ),
            _field(
              'address',
              _address,
              'Adresse',
              maxLength: 300,
              maxLines: 2,
              enabled: isOwner,
            ),
            _field(
              'phone',
              _phone,
              'Téléphone',
              maxLength: 30,
              keyboardType: TextInputType.phone,
              enabled: isOwner,
            ),
            _field(
              'niu',
              _niu,
              'NIU (numéro d\'identifiant unique)',
              maxLength: 30,
              enabled: isOwner,
            ),
            _field('rccm', _rccm, 'RCCM', maxLength: 60, enabled: isOwner),
            SwitchListTile(
              key: const Key('invoicing-settings-vatRegistered'),
              contentPadding: EdgeInsets.zero,
              title: const Text('Assujetti à la TVA'),
              value: _vatRegistered,
              onChanged: isOwner
                  ? (v) => setState(() {
                      _vatRegistered = v;
                      _dirty = true;
                    })
                  : null,
            ),
            _field(
              'vatRate',
              _vatRate,
              _vatRegistered
                  ? 'Taux de TVA (%)'
                  : 'Taux de TVA (%) — facultatif',
              maxLength: 6,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              formatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]')),
              ],
              enabled: isOwner,
            ),
            SwitchListTile(
              key: const Key('invoicing-settings-pricesIncludeVat'),
              contentPadding: EdgeInsets.zero,
              title: const Text('Prix saisis TVA comprise'),
              value: _pricesIncludeVat,
              onChanged: isOwner
                  ? (v) => setState(() {
                      _pricesIncludeVat = v;
                      _dirty = true;
                    })
                  : null,
            ),
            const SizedBox(height: 8),
            _field(
              'paymentDetails',
              _paymentDetails,
              'Coordonnées de paiement',
              helper: 'Mobile Money, compte bancaire…',
              maxLength: 1000,
              maxLines: 3,
              enabled: isOwner,
            ),
            _field(
              'footer',
              _footer,
              'Pied de page',
              maxLength: 1000,
              maxLines: 3,
              enabled: isOwner,
            ),
            for (final message in generalError)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  message,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
          ],
        ),
        bottomNavigationBar: isOwner
            ? SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: FilledButton.icon(
                    key: const Key('invoicing-settings-save'),
                    onPressed: _saving ? null : _save,
                    icon: _saving
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.save_outlined),
                    label: const Text('Enregistrer'),
                  ),
                ),
              )
            : null,
      ),
    );
  }
}
