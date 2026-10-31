import 'package:flutter/foundation.dart';

/// Today in Africa/Douala (UTC+1, no daylight saving) — the server's
/// calendar day for issue and payment dates, whatever the device's zone.
DateTime doualaToday({DateTime? now}) {
  final douala = (now ?? DateTime.now()).toUtc().add(const Duration(hours: 1));
  return DateTime(douala.year, douala.month, douala.day);
}

/// Invoice status values sent by `/api/v1/invoices`.
const kInvoiceDraft = 'DRAFT';
const kInvoiceIssued = 'ISSUED';
const kInvoicePaid = 'PAID';
const kInvoiceCancelled = 'CANCELLED';

/// French label of an invoice status (same wording as the server's
/// `statusLabel`), « Facture » for an unknown value — never the raw code.
String invoiceStatusLabel(String status) => switch (status) {
  kInvoiceDraft => 'Brouillon',
  kInvoiceIssued => 'Émise',
  kInvoicePaid => 'Payée',
  kInvoiceCancelled => 'Annulée',
  _ => 'Facture',
};

/// VAT treatment of a line (`vatTreatment` of the API).
const kVatStandard = 'STANDARD';
const kVatExempt = 'EXEMPT';
const kVatStateBorne = 'STATE_BORNE';

/// Every VAT treatment the form offers.
const kVatTreatments = [kVatStandard, kVatExempt, kVatStateBorne];

/// French label of a line's VAT treatment — never the raw code.
String vatTreatmentLabel(String treatment) => switch (treatment) {
  kVatExempt => 'Exonérée',
  kVatStateBorne => 'Prise en charge État',
  _ => 'TVA normale',
};

String? _string(Object? v) =>
    v is String && v.trim().isNotEmpty ? v.trim() : null;

int _int(Object? v) => v is num ? v.toInt() : 0;

num _num(Object? v) => v is num ? v : (v is String ? num.tryParse(v) ?? 0 : 0);

DateTime? _instant(Object? v) =>
    v is String ? DateTime.tryParse(v)?.toLocal() : null;

String _isoDay(DateTime day) =>
    '${day.year.toString().padLeft(4, '0')}-'
    '${day.month.toString().padLeft(2, '0')}-'
    '${day.day.toString().padLeft(2, '0')}';

/// A calendar day sent as `yyyy-MM-dd` (no time zone shift).
DateTime? _day(Object? v) {
  if (v is! String) return null;
  final d = DateTime.tryParse(v);
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

/// The client printed on an invoice. [id] = the CRM client picked (Story
/// 14.3), null for a free-text client.
@immutable
class InvoiceClient {
  final String? id;
  final String? name;
  final String? phone;
  final String? niu;
  final String? address;

  const InvoiceClient({this.id, this.name, this.phone, this.niu, this.address});

  Map<String, Object?> toJson() => {
    'id': id,
    'name': name,
    'phone': phone,
    'niu': niu,
    'address': address,
  };

  factory InvoiceClient.fromJson(Object? json) {
    if (json is! Map) return const InvoiceClient();
    return InvoiceClient(
      id: _string(json['id']),
      name: _string(json['name']),
      phone: _string(json['phone']),
      niu: _string(json['niu']),
      address: _string(json['address']),
    );
  }
}

/// One line of an invoice (amounts in francs, VAT included in [totalTtc]).
@immutable
class InvoiceLine {
  final int position;
  final String description;
  final num quantity;
  final int unitPrice;
  final String vatTreatment;
  final int totalHt;
  final int totalVat;
  final int totalTtc;

  const InvoiceLine({
    required this.position,
    required this.description,
    required this.quantity,
    required this.unitPrice,
    this.vatTreatment = kVatStandard,
    this.totalHt = 0,
    this.totalVat = 0,
    this.totalTtc = 0,
  });

  Map<String, Object?> toJson() => {
    'position': position,
    'description': description,
    'quantity': quantity,
    'unitPrice': unitPrice,
    'vatTreatment': vatTreatment,
    'totalHt': totalHt,
    'totalVat': totalVat,
    'totalTtc': totalTtc,
  };

  factory InvoiceLine.fromJson(Map json) => InvoiceLine(
    position: _int(json['position']),
    description: _string(json['description']) ?? '',
    quantity: _num(json['quantity']),
    unitPrice: _int(json['unitPrice']),
    vatTreatment: _string(json['vatTreatment']) ?? kVatStandard,
    totalHt: _int(json['totalHt']),
    totalVat: _int(json['totalVat']),
    totalTtc: _int(json['totalTtc']),
  );
}

/// A credit note (« avoir ») issued on the invoice, as listed by the
/// invoice (Story 14.7): number, date, credited TTC, reason.
@immutable
class CreditNoteSummary {
  final String id;
  final String? number;
  final DateTime? issuedAt;
  final int totalTtc;
  final String? reason;

  const CreditNoteSummary({
    required this.id,
    required this.totalTtc,
    this.number,
    this.issuedAt,
    this.reason,
  });

  Map<String, Object?> toJson() => {
    'id': id,
    'number': number,
    'issuedAt': issuedAt?.toUtc().toIso8601String(),
    'totalTtc': totalTtc,
    'reason': reason,
  };

  factory CreditNoteSummary.fromJson(Map json) => CreditNoteSummary(
    id: json['id'] is String ? json['id'] as String : '',
    number: _string(json['number']),
    issuedAt: _instant(json['issuedAt']),
    totalTtc: _int(json['totalTtc']),
    reason: _string(json['reason']),
  );
}

/// An invoice as returned by `/api/v1/invoices` (`InvoiceResponseDto`):
/// Story 15.3 read fields, extended by Story 15.5 for the invoicing screens.
@immutable
class Invoice {
  final String id;

  /// Number in its series (`null` for a draft: numbered when issued).
  final String? number;
  final String status;

  /// French status label sent by the server (`statusLabel`).
  final String? serverStatusLabel;

  /// Client name (`null` / blank when none was recorded).
  final String? clientName;
  final InvoiceClient client;
  final int totalHt;
  final int totalVat;
  final int totalTtc;
  final List<InvoiceLine> lines;

  /// Calendar day of the issue (`null` for a draft).
  final DateTime? issueDate;
  final DateTime? paidAt;
  final String? paymentMethod;
  final DateTime? cancelledAt;
  final String? cancellationReason;

  /// Missing seller mentions (French sentences from the server), shown
  /// before issuing a draft.
  final List<String> complianceWarnings;
  final DateTime? lastSentAt;

  /// Masked phone of the last WhatsApp sending (« +237 6•• ••• 111 »).
  final String? lastSentTo;
  final int sendCount;
  final int creditedTtc;
  final bool fullyCredited;
  final List<CreditNoteSummary> creditNotes;

  /// Sale the invoice was created from (POS → Invoicing bridge).
  final String? saleId;

  /// Set when a sale correction replaced this invoice by a new one.
  final DateTime? supersededAt;

  /// When the invoice was created (server), null when not sent.
  final DateTime? createdAt;

  /// Story 15.5b — last change (server `updatedAt`, or the local write).
  final DateTime? updatedAt;

  /// Story 15.5b — changed on this device and not confirmed by the server yet
  /// (« En attente de synchronisation »). Local state, never sent.
  final bool pending;

  const Invoice({
    required this.id,
    required this.status,
    required this.totalTtc,
    this.number,
    this.serverStatusLabel,
    this.clientName,
    this.client = const InvoiceClient(),
    this.totalHt = 0,
    this.totalVat = 0,
    this.lines = const [],
    this.issueDate,
    this.paidAt,
    this.paymentMethod,
    this.cancelledAt,
    this.cancellationReason,
    this.complianceWarnings = const [],
    this.lastSentAt,
    this.lastSentTo,
    this.sendCount = 0,
    this.creditedTtc = 0,
    this.fullyCredited = false,
    this.creditNotes = const [],
    this.saleId,
    this.supersededAt,
    this.createdAt,
    this.updatedAt,
    this.pending = false,
  });

  /// The server's French label, else the local one — never the raw code.
  /// Story 15.5b: an invoice issued offline has no number until the sync.
  String get statusLabel => status == kInvoiceIssued && number == null
      ? 'Émise — numéro attribué à la synchronisation'
      : serverStatusLabel ?? invoiceStatusLabel(status);

  /// Story 15.5b — issued (or paid / cancelled) on this device, its number not
  /// allocated by the server yet (numbering is server-side only).
  bool get awaitingNumber => !isDraft && number == null;

  /// Story 15.5b — what the list shows as the invoice's title.
  String get title => number ?? (isDraft ? 'Brouillon' : 'Numéro à venir');

  /// The same invoice with the local [pending] flag.
  Invoice withPending(bool pending) => Invoice(
    id: id,
    status: status,
    totalTtc: totalTtc,
    number: number,
    serverStatusLabel: serverStatusLabel,
    clientName: clientName,
    client: client,
    totalHt: totalHt,
    totalVat: totalVat,
    lines: lines,
    issueDate: issueDate,
    paidAt: paidAt,
    paymentMethod: paymentMethod,
    cancelledAt: cancelledAt,
    cancellationReason: cancellationReason,
    complianceWarnings: complianceWarnings,
    lastSentAt: lastSentAt,
    lastSentTo: lastSentTo,
    sendCount: sendCount,
    creditedTtc: creditedTtc,
    fullyCredited: fullyCredited,
    creditNotes: creditNotes,
    saleId: saleId,
    supersededAt: supersededAt,
    createdAt: createdAt,
    updatedAt: updatedAt,
    pending: pending,
  );

  /// Story 15.5b — the invoice in the API's shape (`InvoiceResponseDto`
  /// fields this model reads), as stored on the device. [pending] is not
  /// part of it (a column of the local store).
  Map<String, dynamic> toJson() => {
    'id': id,
    'number': number,
    'status': status,
    'statusLabel': serverStatusLabel,
    'issueDate': issueDate == null ? null : _isoDay(issueDate!),
    'client': {...client.toJson(), 'name': client.name ?? clientName},
    'lines': [for (final l in lines) l.toJson()],
    'totalHt': totalHt,
    'totalVat': totalVat,
    'totalTtc': totalTtc,
    'paidAt': paidAt?.toUtc().toIso8601String(),
    'paymentMethod': paymentMethod,
    'cancelledAt': cancelledAt?.toUtc().toIso8601String(),
    'cancellationReason': cancellationReason,
    'complianceWarnings': complianceWarnings,
    'lastSentAt': lastSentAt?.toUtc().toIso8601String(),
    'lastSentTo': lastSentTo,
    'sendCount': sendCount,
    'creditedTtc': creditedTtc,
    'fullyCredited': fullyCredited,
    'creditNotes': [for (final n in creditNotes) n.toJson()],
    'saleId': saleId,
    'supersededAt': supersededAt?.toUtc().toIso8601String(),
    'createdAt': createdAt?.toUtc().toIso8601String(),
    'updatedAt': updatedAt?.toUtc().toIso8601String(),
  };

  bool get isDraft => status == kInvoiceDraft;

  /// Issued or paid: can be sent, and credited (owner).
  bool get isIssuedOrPaid => status == kInvoiceIssued || status == kInvoicePaid;

  /// What remains to credit (TTC).
  int get remainingTtc =>
      (totalTtc - creditedTtc) < 0 ? 0 : totalTtc - creditedTtc;

  /// Throws [FormatException] (French message) when the id is missing or a
  /// field has the wrong type.
  factory Invoice.fromJson(Map<String, dynamic> json) {
    final id = json['id'];
    final saleId = json['saleId'];
    final status = json['status'];
    if (id is! String || id.isEmpty) {
      throw const FormatException('Facture illisible : identifiant manquant');
    }
    if (saleId != null && saleId is! String) {
      throw const FormatException('Facture illisible : vente invalide');
    }
    final client = InvoiceClient.fromJson(json['client']);
    final rawLines = json['lines'];
    final rawWarnings = json['complianceWarnings'];
    final rawNotes = json['creditNotes'];
    return Invoice(
      id: id,
      number: _string(json['number']),
      status: status is String ? status : '',
      serverStatusLabel: _string(json['statusLabel']),
      clientName: client.name,
      client: client,
      totalHt: _int(json['totalHt']),
      totalVat: _int(json['totalVat']),
      totalTtc: _int(json['totalTtc']),
      lines: [
        if (rawLines is List)
          for (final l in rawLines)
            if (l is Map) InvoiceLine.fromJson(l),
      ],
      issueDate: _day(json['issueDate']),
      paidAt: _instant(json['paidAt']),
      paymentMethod: _string(json['paymentMethod']),
      cancelledAt: _instant(json['cancelledAt']),
      cancellationReason: _string(json['cancellationReason']),
      complianceWarnings: [
        if (rawWarnings is List)
          for (final w in rawWarnings)
            if (w is String && w.trim().isNotEmpty) w.trim(),
      ],
      lastSentAt: _instant(json['lastSentAt']),
      lastSentTo: _string(json['lastSentTo']),
      sendCount: _int(json['sendCount']),
      creditedTtc: _int(json['creditedTtc']),
      fullyCredited: json['fullyCredited'] == true,
      creditNotes: [
        if (rawNotes is List)
          for (final n in rawNotes)
            if (n is Map && n['id'] is String) CreditNoteSummary.fromJson(n),
      ],
      saleId: saleId as String?,
      supersededAt: _instant(json['supersededAt']),
      createdAt: _instant(json['createdAt']),
      updatedAt: _instant(json['updatedAt']),
    );
  }
}

/// One page of the invoice list.
@immutable
class InvoicePage {
  final List<Invoice> items;
  final int totalElements;

  const InvoicePage({required this.items, required this.totalElements});
}
