import 'package:intl/intl.dart';

/// `dd/MM/yyyy` — locale-free so it needs no `initializeDateFormatting`
/// (which only `main.dart` runs), hence safe in widget tests.
String formatDeletionDate(DateTime date) =>
    DateFormat('dd/MM/yyyy').format(date.toLocal());
