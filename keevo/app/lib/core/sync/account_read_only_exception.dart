/// Why the account is in read-only mode (PRD « lecture seule », FR91).
enum AccountReadOnlyReason { suspended, deletionPending }

/// Thrown before any write — local or synced — while the session's
/// `tenantStatus` claim says SUSPENDED or DELETION_PENDING, and by the sync
/// push when the server answers 403 `ACCOUNT_SUSPENDED` /
/// `ACCOUNT_DELETION_PENDING`. Queued operations are kept: they are sent once
/// the account is writable again.
///
/// [message] is the French text shown to the user (`appErrorMessage` reads it).
class AccountReadOnlyException implements Exception {
  final AccountReadOnlyReason reason;

  const AccountReadOnlyException(this.reason);

  static const suspendedMessage =
      'Votre compte est suspendu : consultation uniquement. Contactez Keevo.';
  static const deletionPendingMessage =
      'Suppression du compte programmée : consultation uniquement. Annulez la '
      'suppression pour continuer à travailler.';

  String get message => switch (reason) {
        AccountReadOnlyReason.suspended => suspendedMessage,
        AccountReadOnlyReason.deletionPending => deletionPendingMessage,
      };

  /// The exception matching a `tenantStatus` claim, null when writes are
  /// allowed (ACTIVE, unknown or absent).
  static AccountReadOnlyException? forTenantStatus(String? tenantStatus) =>
      switch (tenantStatus) {
        'SUSPENDED' =>
          const AccountReadOnlyException(AccountReadOnlyReason.suspended),
        'DELETION_PENDING' =>
          const AccountReadOnlyException(AccountReadOnlyReason.deletionPending),
        _ => null,
      };

  /// The exception matching a server `domainCode`, null for any other code.
  static AccountReadOnlyException? forDomainCode(String? domainCode) =>
      switch (domainCode) {
        'ACCOUNT_SUSPENDED' =>
          const AccountReadOnlyException(AccountReadOnlyReason.suspended),
        'ACCOUNT_DELETION_PENDING' =>
          const AccountReadOnlyException(AccountReadOnlyReason.deletionPending),
        _ => null,
      };

  /// True when [text] is one of the read-only messages (state objects that
  /// keep their error as text).
  static bool isReadOnlyMessage(String? text) =>
      text == suspendedMessage || text == deletionPendingMessage;

  @override
  String toString() => message;
}
