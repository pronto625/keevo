import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/reports/presentation/provider/report_history_providers.dart';
import 'package:keevo/features/team/data/datasource/local_employee_datasource.dart';
import 'package:keevo/features/team/domain/model/employee_model.dart';
import 'package:keevo/features/team/presentation/provider/employee_provider.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockLocalEmployees extends Mock implements LocalEmployeeDataSource {}

/// J5 — un rapport personnel porte l'id UTILISATEUR du vendeur ; le nom est
/// résolu dans la table locale des employés quand le serveur ne l'a pas mis.
void main() {
  test('maps user id (and employee id) to « Prénom Nom »', () async {
    final local = _MockLocalEmployees();
    when(() => local.getAll(status: any(named: 'status'))).thenAnswer(
      (_) async => [
        EmployeeModel(
          id: 'emp-1',
          userId: 'user-1',
          firstName: 'Jean',
          lastName: 'Kamga',
          storeId: 's1',
          status: 'ACTIVE',
          passwordChangeRequired: false,
          createdAt: DateTime(2025),
        ),
      ],
    );
    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      localEmployeeDataSourceProvider.overrideWithValue(local),
    ]);
    addTearDown(container.dispose);

    final names =
        await container.read(employeeNamesByActorIdProvider.future);

    expect(names['user-1'], 'Jean Kamga');
    expect(names['emp-1'], 'Jean Kamga');
  });

  test('a local DB failure yields an empty map (never blocks the list)',
      () async {
    final local = _MockLocalEmployees();
    when(() => local.getAll(status: any(named: 'status')))
        .thenThrow(StateError('db closed'));
    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      localEmployeeDataSourceProvider.overrideWithValue(local),
    ]);
    addTearDown(container.dispose);

    expect(await container.read(employeeNamesByActorIdProvider.future),
        isEmpty);
  });
}
