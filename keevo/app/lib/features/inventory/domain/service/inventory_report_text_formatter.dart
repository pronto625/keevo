import '../../domain/model/inventory_gap_report_model.dart';

/// Formats the inventory gap report as emoji-rich WhatsApp text.
/// Mirrors InventoryReportTextFormatter.java on the backend.
/// Story 6.3.
String formatWhatsAppReport(
    InventoryGapReportModel report, String actorName) {
  final buf = StringBuffer();
  final now = report.generatedAt;
  final date = '${now.day} ${_frenchMonth(now.month)} ${now.year}';
  final time = '${now.hour}h${now.minute.toString().padLeft(2, '0')}';

  buf.writeln('📋 Rapport d\'inventaire — ${report.storeName}');
  buf.writeln('📅 $date — $time');
  buf.writeln('👤 $actorName');
  buf.writeln();
  buf.writeln('✅ Concordants : ${report.summary.totalConcordant} produits');

  if (report.summary.totalSurplus > 0) {
    buf.writeln('⚠️ Surplus : ${report.summary.totalSurplus} produits '
        '(+${formatXaf(report.summary.totalSurplusValueXaf)})');
  }
  if (report.summary.totalShortage > 0) {
    buf.writeln('🔴 Manquants : ${report.summary.totalShortage} produits '
        '(−${formatXaf(report.summary.totalShortageValueXaf)})');
  }

  if (report.shortageRows.isNotEmpty) {
    buf.writeln();
    buf.writeln('Top manques :');
    for (final row in report.shortageRows.take(5)) {
      final label = row.variantLabel != null
          ? '${row.productName} ${row.variantLabel}'
          : row.productName;
      final units = row.ecart.abs() > 1 ? 'unités' : 'unité';
      buf.writeln(
          '• $label : ${row.ecart} $units (−${formatXaf(row.gapValueXaf)})');
    }
  }

  return buf.toString();
}

/// Formats the inventory gap report as detailed text for export/download.
String formatDetailedTextReport(
    InventoryGapReportModel report, String actorName) {
  final buf = StringBuffer();
  final now = report.generatedAt;
  final date = '${now.day} ${_frenchMonth(now.month)} ${now.year}';
  final time = '${now.hour}h${now.minute.toString().padLeft(2, '0')}';

  buf.writeln('RAPPORT D\'INVENTAIRE DÉTAILLÉ');
  buf.writeln('${'=' * 40}');
  buf.writeln('Boutique : ${report.storeName}');
  buf.writeln('Date : $date — $time');
  buf.writeln('Généré par : $actorName');
  buf.writeln('Périmètre : ${report.scope}');
  buf.writeln();

  buf.writeln('RÉSUMÉ');
  buf.writeln('${'-' * 40}');
  buf.writeln('Total comptés : ${report.summary.totalCounted}');
  buf.writeln('Concordants : ${report.summary.totalConcordant}');
  buf.writeln('Surplus : ${report.summary.totalSurplus} '
      '(+${formatXaf(report.summary.totalSurplusValueXaf)})');
  buf.writeln('Manquants : ${report.summary.totalShortage} '
      '(−${formatXaf(report.summary.totalShortageValueXaf)})');
  buf.writeln();

  if (report.shortageRows.isNotEmpty) {
    buf.writeln('MANQUANTS (par valeur décroissante)');
    buf.writeln('${'-' * 40}');
    for (final row in report.shortageRows) {
      final label = row.variantLabel != null
          ? '${row.productName} ${row.variantLabel}'
          : row.productName;
      buf.writeln(
          '$label | Keevo: ${row.theoretical} → Réel: ${row.physical} | '
          'Écart: ${row.ecart} | −${formatXaf(row.gapValueXaf)}');
    }
    buf.writeln();
  }

  if (report.surplusRows.isNotEmpty) {
    buf.writeln('SURPLUS (par valeur décroissante)');
    buf.writeln('${'-' * 40}');
    for (final row in report.surplusRows) {
      final label = row.variantLabel != null
          ? '${row.productName} ${row.variantLabel}'
          : row.productName;
      buf.writeln(
          '$label | Keevo: ${row.theoretical} → Réel: ${row.physical} | '
          'Écart: +${row.ecart} | +${formatXaf(row.gapValueXaf)}');
    }
    buf.writeln();
  }

  if (report.concordantRows.isNotEmpty) {
    buf.writeln('CONCORDANTS');
    buf.writeln('${'-' * 40}');
    for (final row in report.concordantRows) {
      buf.writeln('${row.productName} | Quantité: ${row.theoretical}');
    }
  }

  return buf.toString();
}

/// Format XAF amount with space as thousands separator.
String formatXaf(int amount) {
  final str = amount.toString();
  final buf = StringBuffer();
  for (var i = 0; i < str.length; i++) {
    if (i > 0 && (str.length - i) % 3 == 0) buf.write(' ');
    buf.write(str[i]);
  }
  return '${buf.toString()} FCFA';
}

String _frenchMonth(int month) {
  const months = [
    '',
    'janvier',
    'février',
    'mars',
    'avril',
    'mai',
    'juin',
    'juillet',
    'août',
    'septembre',
    'octobre',
    'novembre',
    'décembre',
  ];
  return months[month];
}
