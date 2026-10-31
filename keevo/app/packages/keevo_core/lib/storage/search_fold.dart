/// Unicode search folding — case- and accent-insensitive local search.
///
/// SQLite `LOWER()` / drift `.lower()` fold ASCII only, so "épicerie" missed
/// "Épicerie" and "epicerie" missed both. [searchFoldTable] is the single
/// source of truth for the fold; it is applied:
/// - in SQL to the searched column ([foldedSqlExpr] for drift,
///   [foldedSqlRaw] for raw SQL), through the `keevo_fold()` scalar function
///   that [registerSearchFold] installs on every connection;
/// - in Dart to the user query of every SQL search ([foldForSql],
///   [likeContainsFolded]) — an exact mirror of the SQL side;
/// - in Dart on both sides of in-memory filters ([foldForSearch]).
///
/// No schema change; works under SQLCipher and in in-memory test databases.
/// The SQL side used to be 126 nested `replace()` calls: on Android's
/// SQLite/SQLCipher build that nesting overflows the parser stack
/// ("parser stack overflow"), so every local search and the product
/// duplicate check failed on device. The registered function has no nesting.
///
/// Limit (accepted): Latin-1 / French letters plus Cameroonian letters
/// (ɛ/ɔ/ŋ, General Alphabet letters, precomposed tone-marked vowels). Other
/// scripts keep the ASCII-only SQL case folding: same-case text matches,
/// different-case text does not (Story 16.16). [foldForSql] must
/// therefore never lower-case beyond ASCII, or query and column diverge.
library;

import 'package:drift/drift.dart';
import 'package:sqlite3/common.dart';

import 'sql_like.dart';

/// Character → ASCII replacement. Contains upper- and lower-case forms
/// because SQLite `lower()` folds ASCII only. Every key is one code point and
/// every value is lower-case ASCII (or empty for combining marks), so the
/// replacements never interact and their order does not matter.
const Map<String, String> searchFoldTable = {
  // Latin-1 upper case
  '\u00C0': 'a', // À
  '\u00C1': 'a', // Á
  '\u00C2': 'a', // Â
  '\u00C3': 'a', // Ã
  '\u00C4': 'a', // Ä
  '\u00C5': 'a', // Å
  '\u00C6': 'ae', // Æ
  '\u00C7': 'c', // Ç
  '\u00C8': 'e', // È
  '\u00C9': 'e', // É
  '\u00CA': 'e', // Ê
  '\u00CB': 'e', // Ë
  '\u00CC': 'i', // Ì
  '\u00CD': 'i', // Í
  '\u00CE': 'i', // Î
  '\u00CF': 'i', // Ï
  '\u00D0': 'd', // Ð
  '\u00D1': 'n', // Ñ
  '\u00D2': 'o', // Ò
  '\u00D3': 'o', // Ó
  '\u00D4': 'o', // Ô
  '\u00D5': 'o', // Õ
  '\u00D6': 'o', // Ö
  '\u00D8': 'o', // Ø
  '\u00D9': 'u', // Ù
  '\u00DA': 'u', // Ú
  '\u00DB': 'u', // Û
  '\u00DC': 'u', // Ü
  '\u00DD': 'y', // Ý
  '\u00DE': 'th', // Þ
  '\u00DF': 'ss', // ß
  // Latin-1 lower case
  '\u00E0': 'a', // à
  '\u00E1': 'a', // á
  '\u00E2': 'a', // â
  '\u00E3': 'a', // ã
  '\u00E4': 'a', // ä
  '\u00E5': 'a', // å
  '\u00E6': 'ae', // æ
  '\u00E7': 'c', // ç
  '\u00E8': 'e', // è
  '\u00E9': 'e', // é
  '\u00EA': 'e', // ê
  '\u00EB': 'e', // ë
  '\u00EC': 'i', // ì
  '\u00ED': 'i', // í
  '\u00EE': 'i', // î
  '\u00EF': 'i', // ï
  '\u00F0': 'd', // ð
  '\u00F1': 'n', // ñ
  '\u00F2': 'o', // ò
  '\u00F3': 'o', // ó
  '\u00F4': 'o', // ô
  '\u00F5': 'o', // õ
  '\u00F6': 'o', // ö
  '\u00F8': 'o', // ø
  '\u00F9': 'u', // ù
  '\u00FA': 'u', // ú
  '\u00FB': 'u', // û
  '\u00FC': 'u', // ü
  '\u00FD': 'y', // ý
  '\u00FE': 'th', // þ
  '\u00FF': 'y', // ÿ
  // French extras outside Latin-1
  '\u0152': 'oe', // Œ
  '\u0153': 'oe', // œ
  '\u0178': 'y', // Ÿ
  // Cameroonian alphabet letters (national languages)
  '\u0190': 'e', // Ɛ
  '\u025B': 'e', // ɛ
  '\u0186': 'o', // Ɔ
  '\u0254': 'o', // ɔ
  '\u014A': 'n', // Ŋ
  '\u014B': 'n', // ŋ
  // General Alphabet of Cameroon Languages letters
  '\u0181': 'b', // Ɓ
  '\u0253': 'b', // ɓ
  '\u018A': 'd', // Ɗ
  '\u0257': 'd', // ɗ
  '\u018F': 'e', // Ə
  '\u0259': 'e', // ə
  '\u0197': 'i', // Ɨ
  '\u0268': 'i', // ɨ
  '\u0244': 'u', // Ʉ
  '\u0289': 'u', // ʉ
  '\u01B3': 'y', // Ƴ
  '\u01B4': 'y', // ƴ
  // Precomposed (NFC) tone-marked vowels — macron (mid tone)
  '\u0100': 'a', // Ā
  '\u0101': 'a', // ā
  '\u0112': 'e', // Ē
  '\u0113': 'e', // ē
  '\u012A': 'i', // Ī
  '\u012B': 'i', // ī
  '\u014C': 'o', // Ō
  '\u014D': 'o', // ō
  '\u016A': 'u', // Ū
  '\u016B': 'u', // ū
  // caron (rising tone)
  '\u01CD': 'a', // Ǎ
  '\u01CE': 'a', // ǎ
  '\u011A': 'e', // Ě
  '\u011B': 'e', // ě
  '\u01CF': 'i', // Ǐ
  '\u01D0': 'i', // ǐ
  '\u01D1': 'o', // Ǒ
  '\u01D2': 'o', // ǒ
  '\u01D3': 'u', // Ǔ
  '\u01D4': 'u', // ǔ
  // other precomposed tone-marked letters
  '\u0143': 'n', // Ń
  '\u0144': 'n', // ń
  '\u01F8': 'n', // Ǹ
  '\u01F9': 'n', // ǹ
  '\u1E3E': 'm', // Ḿ
  '\u1E3F': 'm', // ḿ
  '\u1EBC': 'e', // Ẽ
  '\u1EBD': 'e', // ẽ
  // Combining marks (decomposed / NFD input) → removed
  '\u0300': '', // grave
  '\u0301': '', // acute
  '\u0302': '', // circumflex
  '\u0303': '', // tilde
  '\u0304': '', // macron
  '\u0306': '', // breve
  '\u0307': '', // dot above
  '\u0308': '', // diaeresis
  '\u030A': '', // ring above
  '\u030B': '', // double acute
  '\u030C': '', // caron
  '\u0327': '', // cedilla
  '\u0328': '', // ogonek
  '\u0323': '', // dot below
  '\u0331': '', // macron below
};

String _applyTable(String input) {
  final buf = StringBuffer();
  for (final rune in input.runes) {
    final ch = String.fromCharCode(rune);
    buf.write(searchFoldTable[ch] ?? ch);
  }
  return buf.toString();
}

/// Folds a query compared against [foldedSqlExpr] / [foldedSqlRaw]: the
/// table applied to the raw characters, then ASCII-only lower-case (A–Z).
/// Exact mirror of the SQL fold: a character outside the table keeps its
/// case on both sides, so same-case non-Latin text still matches.
String foldForSql(String input) {
  final folded = _applyTable(input);
  final buf = StringBuffer();
  for (final unit in folded.codeUnits) {
    buf.writeCharCode(unit >= 0x41 && unit <= 0x5A ? unit + 0x20 : unit);
  }
  return buf.toString();
}

/// Folds [input] for in-memory Dart filters only (both sides folded in
/// Dart): Unicode lower-case, then Latin accents and ligatures → ASCII.
/// Never use it for a query compared in SQL — use [foldForSql].
String foldForSearch(String input) => _applyTable(input.toLowerCase());

/// Name of the SQL scalar function installed by [registerSearchFold].
const String searchFoldSqlFunction = 'keevo_fold';

/// Installs `keevo_fold(text)` on a raw connection: [foldForSql] applied in
/// Dart (NULL stays NULL). Must run in every `NativeDatabase` setup that
/// executes [foldedSqlExpr] / [foldedSqlRaw].
void registerSearchFold(CommonDatabase db) {
  db.createFunction(
    functionName: searchFoldSqlFunction,
    argumentCount: const AllowedArgumentCount(1),
    deterministic: true,
    directOnly: false,
    function: (args) {
      final value = args[0];
      return value == null ? null : foldForSql(value.toString());
    },
  );
}

/// Drift expression folding [column] the same way as [foldForSql]
/// (`keevo_fold(column)`).
Expression<String> foldedSqlExpr(Expression<String> column) =>
    FunctionCallExpression<String>(searchFoldSqlFunction, [column]);

/// Raw-SQL counterpart of [foldedSqlExpr] for `customSelect` queries.
/// [columnSql] is inserted verbatim (e.g. `p.name`) — never user input.
String foldedSqlRaw(String columnSql) => '$searchFoldSqlFunction($columnSql)';

/// `%…%` LIKE pattern for [query], folded and escaped (use with
/// [likeEscapeChar] against [foldedSqlExpr] / [foldedSqlRaw]).
String likeContainsFolded(String query) => likeContains(foldForSql(query));
