import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// v1s-16-17 — regression guard: `AppTheme.success` / `errorColor` /
/// `secondary` and `cs.error` fail AA as fills under white text (2.0:1 to
/// 3.28:1). Fixed feedback fills must use the strong tokens
/// (`AppTheme.successStrong`, `errorStrong`, `warningStrong`) with `onBrand`.
/// Inside a SnackBar the theme forces `onBrand` content, so light fills
/// (`AppTheme.warning`, brightness-aware `cs.success`/`warning`…) are
/// forbidden there too.
void main() {
  test('noWeakFeedbackFillsOutsideThemeFile', () {
    // `backgroundColor:` (value possibly on the next line or in a ternary)
    // whose expression, up to the next `,` or `)`, names a weak fill.
    final weakFill = RegExp(
      r'backgroundColor:\s*(?:[^,()]|\([^()]*\))*?'
      r'(\bAppTheme\.(success|errorColor|secondary)\b(?!\w)'
      r'|(\bcs|\bcolorScheme)\.error\b(?!\w))'
      // A translucent tint (`.withOpacity` / `.withValues(alpha:)`) is not
      // a fill under white content.
      r'(?!\s*\.with(Opacity|Values|Alpha)\b)',
      multiLine: true,
    );
    // Inside a SnackBar span: light fills under the theme's white content.
    final snackLightFill = RegExp(
      r'backgroundColor:\s*(?:[^,()]|\([^()]*\))*?'
      r'(\bAppTheme\.warning\b(?!\w)'
      r'|(\bcs|\bcolorScheme)\.'
      r'(success|warning|successStrong|warningStrong)\b(?!\w))'
      r'(?!\s*\.with(Opacity|Values|Alpha)\b)',
      multiLine: true,
    );
    final lineComment = RegExp(r'(^|\s)//[^\n]*');
    const themeFile = 'packages/keevo_core/lib/theme/app_theme.dart';
    final offenders = <String>[];

    expect(Directory('lib').existsSync(), isTrue, reason: 'run from keevo/app');
    // Story 15.4a — the app and the keevo_core kernel package.
    final files = [Directory('lib'), Directory('packages/keevo_core/lib')]
        .expand((d) => d.listSync(recursive: true))
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .toList();
    expect(files.length, greaterThan(100),
        reason: 'lib/ not found — run from keevo/app');

    for (final file in files) {
      final path = file.path.replaceAll(r'\', '/');
      if (path == themeFile) continue;
      // Blank out line comments, keeping offsets (and line numbers) intact.
      final text = file
          .readAsStringSync()
          .replaceAllMapped(lineComment, (m) => ' ' * m[0]!.length);
      int lineOf(int offset) =>
          '\n'.allMatches(text.substring(0, offset)).length + 1;
      String report(int offset, String why) {
        final line = lineOf(offset);
        return '$path:$line: $why';
      }

      for (final m in weakFill.allMatches(text)) {
        offenders.add(report(m.start, m[0]!.replaceAll(RegExp(r'\s+'), ' ')));
      }

      for (final s in RegExp(r'\bSnackBar\(').allMatches(text)) {
        var i = s.end;
        var depth = 1;
        while (depth > 0 && i < text.length) {
          final c = text[i];
          if (c == '(') depth++;
          if (c == ')') depth--;
          i++;
        }
        final span = text.substring(s.start, i);
        for (final m in snackLightFill.allMatches(span)) {
          offenders.add(report(s.start + m.start,
              'SnackBar ${m[0]!.replaceAll(RegExp(r'\s+'), ' ')}'));
        }
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'Weak feedback fills (use AppTheme.successStrong / '
          'errorStrong / warningStrong + onBrand):\n${offenders.join('\n')}',
    );
  });
}
