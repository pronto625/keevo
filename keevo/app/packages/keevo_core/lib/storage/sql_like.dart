/// SQL `LIKE` helpers — single source of truth for user-typed search terms.
///
/// In SQLite `LIKE`, `%` and `_` are wildcards. A user typing "50%" or "a_b"
/// expects a literal match, so every user-facing local search escapes them
/// and declares [likeEscapeChar] (`ESCAPE '\'` in raw SQL,
/// `.like(q, escapeChar: likeEscapeChar)` in Drift). Story 16.14.
library;

/// Escape character used with every `LIKE` built by [likeContains].
const likeEscapeChar = r'\';

/// Returns a `%…%` substring pattern matching [raw] literally.
///
/// Escapes [likeEscapeChar] first, then `%` and `_`. Casing is left to the
/// caller.
String likeContains(String raw) {
  const e = likeEscapeChar;
  final escaped = raw
      .replaceAll(e, '$e$e')
      .replaceAll('%', '$e%')
      .replaceAll('_', '${e}_');
  return '%$escaped%';
}
