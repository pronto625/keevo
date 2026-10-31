import 'package:flutter/foundation.dart';

import 'invoice.dart';

String? _string(Object? v) =>
    v is String && v.trim().isNotEmpty ? v.trim() : null;

String? _blankToNull(String? v) =>
    v == null || v.trim().isEmpty ? null : v.trim();

num _num(Object? v) => v is num ? v : (v is String ? num.tryParse(v) ?? 0 : 0);

/// A line typed in the invoice form (`InvoiceLineRequestDto`).
@immutable
class InvoiceLineInput {
  final String description;
  final num quantity;
  final int unitPrice;
  final String vatTreatment;

  const InvoiceLineInput({
    required this.description,
    required this.quantity,
    required this.unitPrice,
    this.vatTreatment = kVatStandard,
  });

  Map<String, Object?> toJson() => {
    'description': description.trim(),
    'quantity': quantity,
    'unitPrice': unitPrice,
    'vatTreatment': vatTreatment,
  };
}

/// The whole content of a draft (`POST /invoices`, `PUT /invoices/{id}`).
/// [clientId] = a CRM client chosen in the picker (Story 14.3).
@immutable
class InvoiceDraftInput {
  final String? clientId;
  final String clientName;
  final String? clientPhone;
  final String? clientNiu;
  final String? clientAddress;
  final List<InvoiceLineInput> lines;

  const InvoiceDraftInput({
    required this.clientName,
    required this.lines,
    this.clientId,
    this.clientPhone,
    this.clientNiu,
    this.clientAddress,
  });

  Map<String, Object?> toJson() => {
    'client': {
      'id': clientId,
      'name': _blankToNull(clientName),
      'phone': _blankToNull(clientPhone),
      'niu': _blankToNull(clientNiu),
      'address': _blankToNull(clientAddress),
    },
    'lines': [for (final l in lines) l.toJson()],
  };
}

/// Seller profile printed on invoices (`GET|PUT /api/v1/invoicing/settings`).
@immutable
class InvoicingSettings {
  final String? legalName;
  final String? address;
  final String? phone;
  final String? niu;
  final String? rccm;
  final bool vatRegistered;
  final num vatRate;
  final bool pricesIncludeVat;
  final String? paymentDetails;
  final String? footer;

  const InvoicingSettings({
    this.legalName,
    this.address,
    this.phone,
    this.niu,
    this.rccm,
    this.vatRegistered = false,
    this.vatRate = 19.25,
    this.pricesIncludeVat = true,
    this.paymentDetails,
    this.footer,
  });

  factory InvoicingSettings.fromJson(Map json) => InvoicingSettings(
    legalName: _string(json['legalName']),
    address: _string(json['address']),
    phone: _string(json['phone']),
    niu: _string(json['niu']),
    rccm: _string(json['rccm']),
    vatRegistered: json['vatRegistered'] == true,
    vatRate: json['vatRate'] == null ? 19.25 : _num(json['vatRate']),
    pricesIncludeVat: json['pricesIncludeVat'] != false,
    paymentDetails: _string(json['paymentDetails']),
    footer: _string(json['footer']),
  );

  Map<String, Object?> toJson() => {
    'legalName': _blankToNull(legalName),
    'address': _blankToNull(address),
    'phone': _blankToNull(phone),
    'niu': _blankToNull(niu),
    'rccm': _blankToNull(rccm),
    'vatRegistered': vatRegistered,
    'vatRate': vatRate,
    'pricesIncludeVat': pricesIncludeVat,
    'paymentDetails': _blankToNull(paymentDetails),
    'footer': _blankToNull(footer),
  };
}

/// A CRM client offered by the invoice client picker.
@immutable
class ClientSuggestion {
  final String id;
  final String name;
  final String? phone;

  const ClientSuggestion({required this.id, required this.name, this.phone});
}

/// `GET /api/v1/invoices/clients` (Story 14.3): [crmAvailable] false → the
/// form shows free-text client fields.
@immutable
class ClientSuggestions {
  final bool crmAvailable;
  final List<ClientSuggestion> clients;

  const ClientSuggestions({
    required this.crmAvailable,
    this.clients = const [],
  });

  factory ClientSuggestions.fromJson(Map json) {
    final raw = json['clients'];
    return ClientSuggestions(
      crmAvailable: json['crmAvailable'] == true,
      clients: [
        if (raw is List)
          for (final c in raw)
            if (c is Map && c['id'] is String && _string(c['name']) != null)
              ClientSuggestion(
                id: c['id'] as String,
                name: _string(c['name'])!,
                phone: _string(c['phone']),
              ),
      ],
    );
  }
}

/// One invoice line credited by a credit note.
@immutable
class CreditNoteLine {
  final int invoicePosition;
  final String description;
  final num quantity;
  final int totalTtc;

  const CreditNoteLine({
    required this.invoicePosition,
    required this.description,
    required this.quantity,
    this.totalTtc = 0,
  });
}

/// A credit note (`GET /api/v1/credit-notes/{id}`, Story 14.7).
@immutable
class CreditNote {
  final String id;
  final String? number;
  final String? reason;
  final DateTime? issuedAt;
  final List<CreditNoteLine> lines;
  final int totalTtc;

  const CreditNote({
    required this.id,
    required this.totalTtc,
    this.number,
    this.reason,
    this.issuedAt,
    this.lines = const [],
  });

  factory CreditNote.fromJson(Map json) {
    final id = json['id'];
    if (id is! String || id.isEmpty) {
      throw const FormatException('Avoir illisible : identifiant manquant');
    }
    final raw = json['lines'];
    final issued = json['issuedAt'];
    return CreditNote(
      id: id,
      number: _string(json['number']),
      reason: _string(json['reason']),
      issuedAt: issued is String ? DateTime.tryParse(issued)?.toLocal() : null,
      totalTtc: json['totalTtc'] is num ? (json['totalTtc'] as num).toInt() : 0,
      lines: [
        if (raw is List)
          for (final l in raw)
            if (l is Map)
              CreditNoteLine(
                invoicePosition: l['invoicePosition'] is num
                    ? (l['invoicePosition'] as num).toInt()
                    : 0,
                description: _string(l['description']) ?? '',
                quantity: _num(l['quantity']),
                totalTtc: l['totalTtc'] is num
                    ? (l['totalTtc'] as num).toInt()
                    : 0,
              ),
      ],
    );
  }
}

/// A line to credit: the invoice line [position] and the quantity.
typedef CreditNoteSelection = ({int position, num quantity});
