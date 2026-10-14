import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/storage/search_fold.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  group('foldForSearch', () {
    test('lower-cases and strips French accents', () {
      expect(foldForSearch('Épicerie'), 'epicerie');
      expect(foldForSearch('ÉPICERIE'), 'epicerie');
      expect(foldForSearch('Hélène'), 'helene');
      expect(foldForSearch('Société Générale'), 'societe generale');
      expect(foldForSearch('Ça coûte où ? Noël, maïs, Ÿ'),
          'ca coute ou ? noel, mais, y');
    });

    test('ligatures and ß', () {
      expect(foldForSearch('Œufs'), 'oeufs');
      expect(foldForSearch('cœur'), 'coeur');
      expect(foldForSearch('Æther'), 'aether');
      expect(foldForSearch('Straße'), 'strasse');
    });

    test('combining marks (NFD) are removed', () {
      expect(foldForSearch('Cafe\u0301'), 'cafe');
      expect(foldForSearch('c\u0327a'), 'ca');
    });

    test('ASCII, digits and LIKE wildcards are untouched (except case)', () {
      expect(foldForSearch('Riz 50% a_b'), 'riz 50% a_b');
    });

    test('other scripts are only lower-cased (in-memory fold)', () {
      expect(foldForSearch('ЖУК'), 'жук');
    });

    test('Cameroonian letters ɛ/ɔ/ŋ and dot/macron below', () {
      expect(foldForSearch('Ndɔlɛ'), 'ndole');
      expect(foldForSearch('NDƆLƐ'), 'ndole');
      expect(foldForSearch('Ŋgaŋ'), 'ngan');
      expect(foldForSearch('o\u0323a\u0331'), 'oa');
    });

    test('precomposed tone-marked vowels and General Alphabet letters', () {
      expect(foldForSearch('ǎ'), 'a');
      expect(foldForSearch('ə'), 'e');
      expect(foldForSql('ǍƏ'), 'ae');
      expect(foldForSearch('ƁɗƗʉƳ ā ě ń ǹ ḿ ẽ'), 'bdiuy a e n n m e');
    });
  });

  group('foldForSql (exact mirror of the SQL fold)', () {
    test('folds table chars and ASCII case', () {
      expect(foldForSql('ÉPICERIE Fine'), 'epicerie fine');
      expect(foldForSql('ŒUFS'), 'oeufs');
      expect(foldForSql('NDƆLƐ'), 'ndole');
    });

    test('never lower-cases beyond ASCII', () {
      expect(foldForSql('ЖУК'), 'ЖУК');
      expect(foldForSql('жук'), 'жук');
      expect(foldForSql('ĦŁ'), 'ĦŁ');
    });

    test('likeContainsFolded folds then escapes', () {
      expect(likeContainsFolded('Épi_50%'), r'%epi\_50\%%');
    });

    test('every table value is lower-case ASCII', () {
      for (final v in searchFoldTable.values) {
        expect(RegExp(r'^[a-z]*$').hasMatch(v), isTrue, reason: v);
      }
    });

    test('every key is one code point; no key/value contains a quote', () {
      searchFoldTable.forEach((k, v) {
        expect(k.runes.length, 1, reason: k);
        expect(k.contains("'"), isFalse, reason: k);
        expect(v.contains("'"), isFalse, reason: v);
      });
    });
  });

  group('SQL fold parity with foldForSql', () {
    late AppDatabase db;

    setUp(() => db = AppDatabase.forTesting());
    tearDown(() => db.close());

    // Device regression: 126 nested replace() calls overflowed the parser
    // stack of Android's SQLCipher build ("parser stack overflow"), so every
    // local search and the product duplicate check failed on a real phone.
    test('fold SQL is one function call, no nested replace()', () {
      expect(foldedSqlRaw('p.name'), 'keevo_fold(p.name)');
      expect(foldedSqlRaw('p.name').contains('replace('), isFalse);
    });

    test('keevo_fold keeps NULL', () async {
      final row = await db
          .customSelect('SELECT ${foldedSqlRaw('NULL')} AS f')
          .getSingle();
      expect(row.read<String?>('f'), equals(null));
    });

    Future<String> rawFold(String input) async {
      final row = await db.customSelect(
        'SELECT ${foldedSqlRaw('v')} AS f FROM (SELECT ? AS v)',
        variables: [Variable.withString(input)],
      ).getSingle();
      return row.read<String>('f');
    }

    test('foldedSqlRaw(key) == foldForSql(key) for every table key',
        () async {
      for (final key in searchFoldTable.keys) {
        final row = await db.customSelect(
          'SELECT ${foldedSqlRaw('v')} AS f FROM (SELECT ? AS v)',
          variables: [Variable.withString(key)],
        ).getSingle();
        expect(row.read<String>('f'), foldForSql(key),
            reason: 'U+${key.codeUnitAt(0).toRadixString(16)}');
      }
    });

    test('foldedSqlRaw == foldForSql on non-table chars and mixed strings',
        () async {
      const samples = [
        'Ж',
        'ж',
        'Ħ',
        'Ł',
        'ЖУК',
        'жук',
        'ĦŁ Épicerie ЖУК',
        'Ndɔlɛ NDƆLƐ',
        'MiXeD Case 50% a_b',
      ];
      for (final s in samples) {
        expect(await rawFold(s), foldForSql(s), reason: s);
      }
    });

    test('foldedSqlExpr matches foldForSql on mixed strings', () async {
      const samples = [
        'Épicerie Fine',
        'ŒUFS FRAIS',
        'Hélène Ngo',
        'Cafe\u0301',
        'Riz 50%',
        'ЖУК жук Ħł ǍƏ',
        'Ndɔlɛ',
      ];
      for (final s in samples) {
        final expr = foldedSqlExpr(Variable<String>(s));
        final ctx = GenerationContext.fromDb(db);
        expr.writeInto(ctx);
        final raw = await db
            .customSelect('SELECT ${ctx.sql} AS f',
                variables: ctx.introducedVariables)
            .getSingle();
        expect(raw.read<String>('f'), foldForSql(s), reason: s);
      }
    });
  });
}
