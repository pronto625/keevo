import 'package:flutter/foundation.dart';

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

/// Story 15.3 — the fields of an invoice the minimal read-only screen shows
/// (`InvoiceResponseDto`); Story 15.5 extends it.
@immutable
class Invoice {
  final String id;

  /// Number in its series (`null` for a draft: numbered when issued).
  final String? number;
  final String status;

  /// Client name (`null` / blank when none was recorded).
  final String? clientName;
  final int totalTtc;

  /// Sale the invoice was created from (POS → Invoicing bridge).
  final String? saleId;

  /// Set when a sale correction replaced this invoice by a new one.
  final DateTime? supersededAt;

  /// When the invoice was created (server), null when not sent.
  final DateTime? createdAt;

  const Invoice({
    required this.id,
    required this.status,
    required this.totalTtc,
    this.number,
    this.clientName,
    this.saleId,
    this.supersededAt,
    this.createdAt,
  });

  String get statusLabel => invoiceStatusLabel(status);

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
    final created = json['createdAt'];
    final client = json['client'];
    final number = json['number'];
    final superseded = json['supersededAt'];
    final total = json['totalTtc'];
    return Invoice(
      id: id,
      number: number is String && number.trim().isNotEmpty ? number : null,
      status: status is String ? status : '',
      clientName: client is Map && client['name'] is String
          ? (client['name'] as String).trim()
          : null,
      totalTtc: total is num ? total.toInt() : 0,
      saleId: saleId as String?,
      supersededAt: superseded is String ? DateTime.tryParse(superseded) : null,
      createdAt: created is String ? DateTime.tryParse(created) : null,
    );
  }
}
