/// Thrown when an employee action has no offline fallback and the server
/// could not be reached (Story 14.11).
///
/// The backend sync handler (`EmployeeSyncHandler`) only replays
/// CREATE / REASSIGN / DEACTIVATE / REACTIVATE. Editing a name or phone
/// number, changing a role or setting a password have no queued form, so
/// they are refused up-front instead of being silently lost.
class EmployeeActionRequiresConnectionException implements Exception {
  const EmployeeActionRequiresConnectionException();

  @override
  String toString() => 'Cette action nécessite une connexion internet.';
}
