import '../model/deletion_pending_status.dart';

/// AccountDeletionRepository — FR91 self-service deletion (Story 14.5).
abstract interface class AccountDeletionRepository {
  /// Asks the server to schedule the deletion (30-day grace period).
  /// Returns the status with the scheduled date.
  Future<DeletionPendingStatus> requestDeletion();

  /// Cancels a pending deletion; the account is active again.
  Future<void> cancelDeletion();

  /// Current pending status, or null when no deletion is scheduled.
  Future<DeletionPendingStatus?> currentStatus();
}
