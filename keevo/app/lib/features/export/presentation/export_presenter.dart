/// Display helpers shared by the export screens (v1s-18-4).
class ExportPresenter {
  ExportPresenter._();

  static String _two(int v) => v.toString().padLeft(2, '0');

  /// `dd/MM/yyyy` in local time.
  static String date(DateTime d) {
    final l = d.toLocal();
    return '${_two(l.day)}/${_two(l.month)}/${l.year}';
  }

  /// `dd/MM/yyyy à HH:mm` in local time.
  static String dateTime(DateTime d) {
    final l = d.toLocal();
    return '${date(l)} à ${_two(l.hour)}:${_two(l.minute)}';
  }

  /// `from – to`, or « Toutes les dates ».
  static String period(DateTime? from, DateTime? to) {
    if (from == null && to == null) return 'Toutes les dates';
    if (from != null && to != null) {
      final a = date(from);
      final b = date(to);
      return a == b ? a : '$a – $b';
    }
    return from != null ? 'Depuis le ${date(from)}' : 'Jusqu\'au ${date(to!)}';
  }

  /// Human file size (`12 Ko`, `3,4 Mo`).
  static String fileSize(int bytes) {
    if (bytes < 1024) return '$bytes o';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} Ko';
    final mb = bytes / (1024 * 1024);
    return '${mb.toStringAsFixed(1).replaceAll('.', ',')} Mo';
  }
}
