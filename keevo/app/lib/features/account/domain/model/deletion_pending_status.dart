/// DeletionPendingStatus — the tenant has asked for its account to be deleted
/// and is inside the 30-day grace period (Story 14.5, FR91 — AC8).
///
/// [scheduledAt] is null when the pending state is only known from the JWT
/// claim `tenantStatus == DELETION_PENDING` (request made from another
/// device): the server does not expose the date to the app.
class DeletionPendingStatus {
  final DateTime? scheduledAt;

  const DeletionPendingStatus({this.scheduledAt});

  /// Whole days left before the purge, never negative. Null without a date.
  int? daysRemaining({DateTime? now}) {
    final at = scheduledAt;
    if (at == null) return null;
    final diff = at.difference(now ?? DateTime.now());
    if (diff.isNegative) return 0;
    // Round up: 29 days and 2 hours left still reads as "30 jours".
    return (diff.inMinutes / Duration.minutesPerDay).ceil();
  }

  @override
  bool operator ==(Object other) =>
      other is DeletionPendingStatus && other.scheduledAt == scheduledAt;

  @override
  int get hashCode => scheduledAt.hashCode;
}
