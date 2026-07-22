/// Thrown when the user attempts an action that requires connectivity while
/// offline — cancelling/correcting a COMPLETED sale (Story v1s-13-5, Décision D2).
///
/// Unlike PENDING sale actions (fully offline-first via the sync queue),
/// COMPLETED-sale cancel/correct is online-only: implementing a safe offline
/// queue would require idempotent stock restoration + a way to prove the
/// device's role at sync time, both out of scope for this story.
class OfflineActionNotSupportedException implements Exception {
  @override
  String toString() => 'Cette action nécessite une connexion internet.';
}
