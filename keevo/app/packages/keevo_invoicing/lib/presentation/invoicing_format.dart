import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';

import '../domain/model/invoice.dart';

/// Story 15.5 — shared presentation helpers of the invoicing screens: FCFA
/// amounts (same formatter as the POS screens), French dates, quantities and
/// server error messages.

final _currency = NumberFormat.currency(
  locale: 'fr_CM',
  symbol: 'XAF',
  decimalDigits: 0,
);

/// Amount in francs, « 59 625 XAF ».
String formatXaf(num amount) => _currency.format(amount);

/// Today in Africa/Douala (UTC+1, no daylight saving) — the server's
/// calendar day for issue and payment dates, whatever the device's zone.
DateTime doualaToday({DateTime? now}) {
  final douala = (now ?? DateTime.now()).toUtc().add(const Duration(hours: 1));
  return DateTime(douala.year, douala.month, douala.day);
}

/// « 31 oct. 2026 ».
String formatDay(DateTime day) => DateFormat('d MMM y', 'fr_FR').format(day);

/// « 31 oct. 2026 à 14:05 ».
String formatDateTime(DateTime at) =>
    DateFormat("d MMM y 'à' HH:mm", 'fr_FR').format(at);

/// A quantity with at most 3 decimals and a French decimal comma (« 2 »,
/// « 1,5 »).
String formatQuantity(num quantity) {
  final fixed = quantity.toStringAsFixed(3);
  final trimmed = fixed.contains('.')
      ? fixed.replaceFirst(RegExp(r'\.?0+$'), '')
      : fixed;
  return trimmed.replaceAll('.', ',');
}

/// Parses a quantity or an amount typed with a comma or a dot; null when not
/// a number.
num? parseDecimal(String text) {
  final t = text.trim().replaceAll(' ', '').replaceAll(' ', '');
  if (t.isEmpty) return null;
  return num.tryParse(t.replaceAll(',', '.'));
}

/// [quantity] in thousandths (quantities have at most 3 decimals): exact
/// integer arithmetic instead of floating point (0.3 − 0.1 ≠ 0.2).
int toThousandths(num quantity) => (quantity * 1000).round();

/// What remains to credit on a line of [quantity] of which [credited] is
/// already credited, computed in thousandths; never negative.
num remainingQuantity(num quantity, num credited) {
  final rest = toThousandths(quantity) - toThousandths(credited);
  return rest <= 0 ? 0 : rest / 1000;
}

/// Field errors of a server validation refusal (`VALIDATION_ERROR` with a
/// `details` map: field → French message), empty otherwise.
Map<String, String> serverFieldErrors(Object error) {
  if (error is! DioException) return const {};
  final body = error.response?.data;
  if (body is! Map || body['domainCode'] != 'VALIDATION_ERROR') {
    return const {};
  }
  final details = body['details'];
  if (details is! Map) return const {};
  return {
    for (final e in details.entries)
      if (e.key is String &&
          e.value is String &&
          (e.value as String).isNotEmpty)
        e.key as String: e.value as String,
  };
}

/// French message of an error of an invoicing call: the first field message
/// of a validation refusal (the generic « Données invalides » says nothing),
/// else the app's message ([appErrorMessage]).
String invoicingErrorMessage(Object error) {
  final fields = serverFieldErrors(error);
  if (fields.isNotEmpty) return fields.values.first;
  return appErrorMessage(error);
}

/// Red snackbar with the French message of [error].
void showInvoicingError(BuildContext context, Object error) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(invoicingErrorMessage(error)),
      backgroundColor: AppTheme.errorStrong,
    ),
  );
}

/// Plain confirmation snackbar.
void showInvoicingInfo(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

/// Colour of an invoice status badge.
Color invoiceStatusColor(String status, ColorScheme cs) => switch (status) {
  kInvoicePaid => AppTheme.successStrong,
  kInvoiceCancelled => cs.error,
  kInvoiceDraft => cs.onSurfaceVariant,
  _ => cs.primary,
};

/// A small rounded badge (status, « Remplacée », « Entièrement créditée »).
class InvoiceBadge extends StatelessWidget {
  final String label;
  final Color color;

  const InvoiceBadge({super.key, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w600,
          fontSize: 12,
        ),
      ),
    );
  }
}
