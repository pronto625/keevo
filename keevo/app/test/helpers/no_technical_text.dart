import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// v1s-16-20 guard — technical data an end user must never see: a UUID, a
/// raw SNAKE_CASE code (`CATEGORY_RENAMED`, `PENDING_VALIDATION`), JSON, or a
/// short hex id fragment (`…abcd1234`).
final technicalTextPatterns = <String, RegExp>{
  'UUID': RegExp(
      r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'),
  'SNAKE_CASE code': RegExp(r'\b[A-Z]+(_[A-Z]+)+\b'),
  'JSON': RegExp(r'\{"|\["'),
  'truncated id': RegExp(r'…[0-9a-fA-F]{8}|[0-9a-fA-F]{8}…'
      r'|\.\.\.[0-9a-fA-F]{8}|[0-9a-fA-F]{8}\.\.\.'
      r'|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}'),
  // Single-word raw enum codes (NFR35), case-sensitive, whole words.
  'raw code': RegExp(r'\b(' '${_singleWordCodes.join('|')}' r')\b'),
};

/// Known single-word enum codes of the backend / sync layer.
const _singleWordCodes = [
  'ONLINE', 'SYNC', 'SYSTEM', 'CASH', 'DRAFT', 'ACTIVE', 'INACTIVE', 'FULL',
  'PARTIAL', 'CATEGORY', 'WAREHOUSE', 'STORE', 'PENDING', 'COMPLETED',
  'CANCELLED', 'VALIDATED', 'CONFLICT', 'OWNER', 'EMPLOYEE', 'MANAGER',
  'SALE', 'PURCHASE', 'ADJUSTMENT', 'INVENTORY', 'SUCCESS', 'FAILED', 'WARN',
  'PUSH', 'PULL', 'DIAGNOSTIC', 'ABANDON', 'ABANDONED', 'SENT', 'DAILY',
  'WEEKLY',
];

/// Names of the technical patterns found in [text] (empty when clean).
List<String> technicalFindings(String text) => [
      for (final e in technicalTextPatterns.entries)
        if (e.value.hasMatch(text)) '${e.key}: «$text»',
    ];

/// Fails when [text] holds technical data.
void expectNoTechnicalString(String text, {String? reason}) {
  expect(technicalFindings(text), isEmpty, reason: reason);
}

/// Fails when any displayed text of the current widget tree — every [Text],
/// [RichText] and [EditableText] / [SelectableText] plain text — holds
/// technical data.
void expectNoTechnicalText(WidgetTester tester) {
  final findings = <String>[];
  void check(String? text) {
    if (text == null || text.isEmpty) return;
    findings.addAll(technicalFindings(text));
  }

  for (final w in tester.widgetList(find.byType(Text))) {
    final t = w as Text;
    check(t.data ?? t.textSpan?.toPlainText());
  }
  for (final w in tester.widgetList(find.byType(RichText))) {
    check((w as RichText).text.toPlainText());
  }
  for (final w in tester.widgetList(find.byType(EditableText))) {
    check((w as EditableText).controller.text);
  }
  expect(findings, isEmpty, reason: 'technical data displayed to the user');
}
