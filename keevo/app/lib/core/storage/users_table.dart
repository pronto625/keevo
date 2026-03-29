import 'package:drift/drift.dart';

/// Users — auth cache for offline session validation and role resolution.
///
/// Populated after successful login/JWT validation.
/// Allows POS to operate offline without calling the auth backend.
class Users extends Table {
  TextColumn get id => text()();
  TextColumn get phoneNumber => text()();
  TextColumn get tenantId => text()();

  /// Role string: 'OWNER' | 'EMPLOYEE'
  TextColumn get role => text()();

  /// User first name — nullable, populated from JWT firstName claim (Story 7.1).
  TextColumn get firstName => text().nullable()();

  DateTimeColumn get createdAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
