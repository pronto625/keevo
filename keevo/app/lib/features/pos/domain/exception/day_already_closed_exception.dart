/// Thrown when the store's day has already been closed: the backend accepts a
/// single closure per store and calendar day.
class DayAlreadyClosedException implements Exception {
  @override
  String toString() => 'La journée a déjà été clôturée pour cette boutique.';
}
