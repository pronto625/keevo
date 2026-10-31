import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// v1s-16-3 — regression guard: every color outside the theme file must
/// resolve through `ColorScheme`, `KeevoColors` or a named `AppTheme` token.
/// `Colors.transparent` is the only literal allowed.
void main() {
  test('noHardcodedColorsOutsideThemeFile', () {
    final forbidden = RegExp(
      r'\bColors\.(?!transparent\b)[a-zA-Z]'
      r'|\bCupertinoColors\.'
      r'|\bColor\(\s*(0[xX]|\d)'
      r'|\bColor\.from(ARGB|RGBO)?\s*\('
      r'|\b(MaterialColor|ColorSwatch)\s*\(',
    );
    // A `//` starts a comment only at line start or after whitespace, so a
    // URL inside a string ('https://…') does not hide the rest of the line.
    final lineComment = RegExp(r'(^|\s)//');
    const themeFile = 'packages/keevo_core/lib/theme/app_theme.dart';
    final offenders = <String>[];

    // Story 15.4a — the app and the keevo_core kernel package.
    final files = [Directory('lib'), Directory('packages/keevo_core/lib')]
        .expand((d) => d.listSync(recursive: true))
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .toList();
    // Guard against running from the wrong directory (would scan nothing).
    expect(files.length, greaterThan(100),
        reason: 'lib/ not found — run from keevo/app');
    for (final file in files) {
      final path = file.path.replaceAll(r'\', '/');
      if (path == themeFile) continue;
      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        final commentAt = lines[i].indexOf(lineComment);
        final code =
            commentAt >= 0 ? lines[i].substring(0, commentAt) : lines[i];
        if (forbidden.hasMatch(code)) {
          offenders.add('$path:${i + 1}: ${lines[i].trim()}');
        }
      }
    }

    expect(
      offenders,
      isEmpty,
      reason: 'Literal colors outside $themeFile (use colorScheme / '
          'KeevoColors / AppTheme tokens):\n${offenders.join('\n')}',
    );
  });
}
