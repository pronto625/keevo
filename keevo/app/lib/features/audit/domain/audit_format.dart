import 'dart:convert';

import 'audit_labels.dart';

/// One « Changements » line: [before] is `null` for a creation (no before
/// snapshot), else the formatted previous value (« — » when absent).
class AuditChangeLine {
  final String field;
  final String? before;
  final String after;

  const AuditChangeLine(this.field, this.before, this.after);

  /// « Champ : avant → après », or « Champ : valeur » for a creation.
  String get text =>
      before == null ? '$field : $after' : '$field : $before → $after';
}

/// AuditFormat — readable French rendering of an audit entry's before/after
/// values and context (v1s-16-20). Dart mirror of the server
/// `AuditChangesFormatter`: the history screens and the on-device audit CSV
/// apply exactly the rules of the server export, so no UUID, raw code, device
/// id or JSON ever reaches the owner.
///
/// Pure and total: malformed JSON renders nothing, never throws.
class AuditFormat {
  const AuditFormat._();

  static const none = '—';
  static const separator = ' ; ';

  /// Readable text of a stock movement note written by an inventory
  /// validation (`INVENTORY:<sessionId>`).
  static const inventoryNote = 'Ajustement d\'inventaire';
  static const inventoryNotePrefix = 'INVENTORY:';

  static const _itemsKey = 'itemsSnapshot';
  static const _fieldsChangedKey = 'fieldsChanged';
  static const automaticKey = 'isAutomatic';

  static final uuidPattern = RegExp(
      r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}');
  static final snakeCode = RegExp(r'^[A-Z0-9]+(_[A-Z0-9]+)+$');
  static final _upperCode = RegExp(r'^[A-Z0-9_]+$');

  static const _amountKeys = {
    'totalAmount',
    'discountAmount',
    'appliedPrice',
    'cataloguePrice',
    'totalRevenue',
    'price',
    'sellingPrice',
    'purchasePrice',
    'costPrice',
    'unitPrice',
    'buyPrice',
    'transportCost',
  };

  static const _quantityKeys = {
    'quantity',
    'quantityBefore',
    'quantityChange',
    'quantityAfter',
    'currentQuantity',
    'threshold',
    'lowStockThreshold',
    'requestedQuantity',
    'availableQuantity',
    'theoretical',
    'physical',
    'ecart',
  };

  static const _signedKeys = {'quantityChange', 'ecart'};

  // ── Labels ────────────────────────────────────────────────────────────────

  /// `FOO_BAR` → `Foo bar`, `someKey` → `Some key` (server
  /// `ExportValues.humanize`).
  static String humanize(String code) {
    final spaced = code
        .replaceAllMapped(
            RegExp(r'([a-z0-9])([A-Z])'), (m) => '${m[1]} ${m[2]}')
        .replaceAll('_', ' ')
        .trim()
        .toLowerCase();
    if (spaced.isEmpty) return code;
    return spaced[0].toUpperCase() + spaced.substring(1);
  }

  /// Technical keys never shown: the hidden table, `id`, `*Id`, `*Ids`.
  static bool isHiddenKey(String key) =>
      auditHiddenKeys.contains(key) ||
      key == 'id' ||
      key.endsWith('Id') ||
      key.endsWith('Ids');

  static bool isContextKey(String key) => auditContextLabels.containsKey(key);

  static String fieldLabel(String key) =>
      auditFieldLabels[key] ?? humanize(key);

  static String contextLabel(String key) =>
      auditContextLabels[key] ?? fieldLabel(key);

  /// French label of a code value: the table, else humanized.
  static String valueLabel(String code) =>
      auditValueLabels[code] ?? humanize(code);

  static String actionLabel(String action) =>
      auditActionLabels[action] ?? humanize(action);

  static String entityTypeLabel(String type) =>
      auditEntityTypeLabels[type] ?? humanize(type);

  static bool isAmountKey(String k) =>
      _amountKeys.contains(k) || k.endsWith('Price') || k.endsWith('Amount');

  static bool isQuantityKey(String k) => _quantityKeys.contains(k);

  /// Stock movement / transfer note (server `ExportValues.notes`): an
  /// inventory validation note reads « Ajustement d'inventaire »; any other
  /// UUID is removed (« Annulation vente <id> » → « Annulation vente »).
  /// `null` when nothing readable remains.
  static String? notes(String? value) {
    if (value == null) return null;
    if (value.startsWith(inventoryNotePrefix)) return inventoryNote;
    final cleaned = value
        .replaceAll(uuidPattern, ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    return cleaned.isEmpty ? null : cleaned;
  }

  /// Server `ExportValues.isTechnical`: a UUID, a SNAKE_CASE code (whole or
  /// embedded) or JSON.
  static bool isTechnical(String text) {
    final t = text.trim();
    return uuidPattern.hasMatch(t) ||
        _embeddedSnake.hasMatch(t) ||
        t.contains('{"') ||
        t.contains('["') ||
        t.startsWith('{') ||
        t.startsWith('[');
  }

  static final _embeddedSnake = RegExp(r'\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b');

  // ── Values ────────────────────────────────────────────────────────────────

  /// Readable value of one field; `null` when it must not be shown (UUID,
  /// nested object, JSON text). [display] converts a date before formatting
  /// (default: WAT, like the server export).
  static String? formatValue(String key, Object? v,
      {DateTime Function(DateTime)? display}) {
    if (v == null) return none;
    if (v is bool) return v ? 'Oui' : 'Non';
    if (v is List) {
      if (key == _fieldsChangedKey && v.every((x) => x is String)) {
        return v.map((x) => fieldLabel(x as String)).join(', ');
      }
      return _items(key, v.length);
    }
    if (v is Map) return null;
    if (v is num) {
      if (isAmountKey(key)) return formatXaf(v);
      if (_quantityKeys.contains(key)) {
        final sign = _signedKeys.contains(key) && v > 0 ? '+' : '';
        return '$sign${formatNumber(v)} unité(s)';
      }
      return formatNumber(v);
    }
    final s = v.toString().trim();
    if (s.isEmpty) return none;
    if (s.startsWith(inventoryNotePrefix)) return inventoryNote;
    if (uuidPattern.hasMatch(s)) {
      // ids removed; nothing readable left → not shown
      final cleaned = notes(s);
      return cleaned == null
          ? null
          : formatValue(key, cleaned, display: display);
    }
    if (isAmountKey(key)) {
      final n = num.tryParse(s);
      if (n != null) return formatXaf(n);
    }
    if (auditCodeKeys.contains(key) && _upperCode.hasMatch(s)) {
      return valueLabel(s);
    }
    if (snakeCode.hasMatch(s)) return valueLabel(s);
    if (key.endsWith('At') || key == 'from' || key == 'to') {
      final d = DateTime.tryParse(s);
      if (d != null) return formatDateTime((display ?? toWat)(d));
    }
    if (s.startsWith('{') || s.startsWith('[')) return null;
    return s;
  }

  static String _items(String key, int n) =>
      key == _itemsKey ? '$n article(s)' : '$n élément(s)';

  // ── Changes ───────────────────────────────────────────────────────────────

  /// Field-by-field lines of [valueBefore] → [valueAfter] (raw JSON).
  static List<AuditChangeLine> changes(String? valueBefore, String? valueAfter,
      {DateTime Function(DateTime)? display}) {
    final before = decode(valueBefore);
    final after = decode(valueAfter);
    if ((before is List || after is List) && before is! Map && after is! Map) {
      // Whole snapshots that are lists (sale items).
      final b = before is List ? _items(_itemsKey, before.length) : none;
      final a = after is List ? _items(_itemsKey, after.length) : none;
      return [AuditChangeLine(fieldLabel(_itemsKey), before == null ? null : b, a)];
    }
    final bm = before is Map ? before.cast<String, dynamic>() : const {};
    final am = after is Map ? after.cast<String, dynamic>() : const {};
    final keys = <String>[...am.keys];
    for (final k in bm.keys) {
      if (!keys.contains(k)) keys.add(k);
    }
    final lines = <AuditChangeLine>[];
    for (final k in keys) {
      if (isHiddenKey(k) || isContextKey(k)) continue;
      final bv = bm[k];
      final av = am[k];
      if (bv == null && av == null) continue;
      if (bv is Map || av is Map) continue;
      final fb = formatValue(k, bv, display: display);
      final fa = formatValue(k, av, display: display);
      if (fb == null || fa == null) continue;
      if (before != null && after != null && fb == fa) continue;
      lines.add(AuditChangeLine(fieldLabel(k), before == null ? null : fb, fa));
    }
    return lines;
  }

  /// « Changements » cell of the audit export, `null` when empty.
  static String? changesText(String? valueBefore, String? valueAfter) {
    final lines = changes(valueBefore, valueAfter);
    return lines.isEmpty ? null : lines.map((l) => l.text).join(separator);
  }

  // ── Context ───────────────────────────────────────────────────────────────

  /// (label, value) context rows from [metadata], or for legacy rows the
  /// context keys still in [valueAfter].
  static List<(String, String)> details(String? metadata, String? valueAfter,
      {DateTime Function(DateTime)? display}) {
    final meta = decodeMap(metadata);
    final Map<String, dynamic> source;
    if (meta.isNotEmpty) {
      source = meta;
    } else {
      final after = decodeMap(valueAfter);
      source = {
        for (final e in after.entries)
          if (isContextKey(e.key)) e.key: e.value,
      };
    }
    final rows = <(String, String)>[];
    source.forEach((k, v) {
      if (isHiddenKey(k) || v == null || v is Map) return;
      final String? value;
      if (k == automaticKey) {
        value = v == true || v == 'true' ? 'Automatique' : 'Manuel';
      } else {
        value = formatValue(k, v, display: display);
      }
      if (value == null || value.trim().isEmpty || value == none) return;
      rows.add((contextLabel(k), value));
    });
    return rows;
  }

  /// « Détails » cell of the audit export, `null` when empty.
  static String? detailsText(String? metadata, String? valueAfter) {
    final rows = details(metadata, valueAfter);
    return rows.isEmpty
        ? null
        : rows.map((r) => '${r.$1} : ${r.$2}').join(separator);
  }

  // ── Formatting ────────────────────────────────────────────────────────────

  /// `15 000 XAF` (integer XAF, space grouping).
  static String formatXaf(num amount) => '${formatNumber(amount.round())} XAF';

  static String formatNumber(num v) {
    final isInt = v == v.roundToDouble();
    final negative = v < 0;
    final abs = v.abs();
    final intPart = abs.truncate().toString();
    final grouped = StringBuffer();
    for (var i = 0; i < intPart.length; i++) {
      if (i > 0 && (intPart.length - i) % 3 == 0) grouped.write(' ');
      grouped.write(intPart[i]);
    }
    var out = grouped.toString();
    if (!isInt) {
      final dec = abs.toString().split('.');
      if (dec.length == 2) out = '$out,${dec[1]}';
    }
    return negative ? '-$out' : out;
  }

  /// WAT wall clock (UTC+01:00, no DST) as a UTC [DateTime].
  static DateTime toWat(DateTime d) =>
      d.toUtc().add(const Duration(hours: 1));

  /// `dd/MM/yyyy HH:mm` of an already converted [d].
  static String formatDateTime(DateTime d) =>
      '${_pad2(d.day)}/${_pad2(d.month)}/${d.year} ${_pad2(d.hour)}:${_pad2(d.minute)}';

  static String _pad2(int v) => v.toString().padLeft(2, '0');

  // ── JSON ──────────────────────────────────────────────────────────────────

  static Object? decode(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    try {
      return jsonDecode(raw);
    } catch (_) {
      return null;
    }
  }

  static Map<String, dynamic> decodeMap(String? raw) {
    final v = decode(raw);
    return v is Map ? v.cast<String, dynamic>() : const {};
  }
}
