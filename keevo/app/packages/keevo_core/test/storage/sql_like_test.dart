import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/storage/sql_like.dart';

void main() {
  group('likeContains', () {
    test('wraps plain text without changing casing', () {
      expect(likeContains('Riz'), '%Riz%');
    });

    test('escapes %, _ and backslash (backslash first)', () {
      expect(likeContains('50%'), r'%50\%%');
      expect(likeContains('a_b'), r'%a\_b%');
      expect(likeContains(r'C:\x'), r'%C:\\x%');
      expect(likeContains(r'\%'), r'%\\\%%');
    });

    test('escape char is backslash', () {
      expect(likeEscapeChar, r'\');
    });
  });
}
