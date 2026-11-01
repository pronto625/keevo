import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/devices/data/remote_device_sessions_datasource.dart';
import 'package:keevo/features/devices/domain/device_session.dart';
import 'package:keevo/features/devices/presentation/page/connected_devices_page.dart';
import 'package:keevo/features/devices/presentation/provider/device_sessions_provider.dart';
import 'package:keevo/features/devices/presentation/widget/device_session_tile.dart';
import 'package:keevo/features/devices/presentation/widget/employee_devices_section.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockDataSource extends Mock implements RemoteDeviceSessionsDataSource {}

class _MockDio extends Mock implements Dio {}

/// Story 16.11 — « Appareils connectés ».
void main() {
  late _MockDataSource ds;
  late List<DeviceSession> sessions;

  setUp(() {
    ds = _MockDataSource();
    sessions = [
      DeviceSession(
          sessionId: 'other',
          label: 'Chrome · Linux',
          lastUsedAt: DateTime.utc(2026, 10, 30, 13, 5)),
      DeviceSession(
          sessionId: 'me',
          label: 'Xiaomi Redmi Note 12 · Android 14',
          lastUsedAt: DateTime.utc(2026, 11, 1, 9),
          current: true),
    ];
    when(() => ds.mine()).thenAnswer((_) async => sessions);
    when(() => ds.closeMine(any())).thenAnswer((_) async {
      sessions = sessions.where((s) => s.current).toList();
    });
    when(() => ds.closeMyOthers()).thenAnswer((_) async {
      sessions = sessions.where((s) => s.current).toList();
      return 1;
    });
  });

  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [deviceSessionsDataSourceProvider.overrideWithValue(ds)],
      child: const MaterialApp(home: ConnectedDevicesPage()),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('lists my devices, this one first, with the last activity in Douala time',
      (tester) async {
    await pump(tester);

    expect(find.text('Appareils connectés'), findsOneWidget);
    final labels = tester
        .widgetList<ListTile>(find.byType(ListTile))
        .map((t) => (t.title as Text).data)
        .toList();
    expect(labels, ['Xiaomi Redmi Note 12 · Android 14', 'Chrome · Linux']);
    expect(find.text('Cet appareil · Dernière activité le 01/11/2026 à 10:00'),
        findsOneWidget);
    expect(find.text('Dernière activité le 30/10/2026 à 14:05'), findsOneWidget);
    // The current device has no « Déconnecter » button; no id is ever shown.
    expect(find.text('Déconnecter'), findsOneWidget);
    expect(find.textContaining('other'), findsNothing);
  });

  testWidgets('« Déconnecter » asks first, closes the session and says so',
      (tester) async {
    await pump(tester);

    await tester.tap(find.text('Déconnecter'));
    await tester.pumpAndSettle();
    expect(find.text('Déconnecter cet appareil ?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Déconnecter'));
    await tester.pumpAndSettle();

    verify(() => ds.closeMine('other')).called(1);
    expect(find.text('Appareil déconnecté'), findsOneWidget);
    expect(find.text('Chrome · Linux'), findsNothing);
  });

  testWidgets('« Déconnecter tous les autres appareils »', (tester) async {
    await pump(tester);

    await tester.tap(find.text('Déconnecter tous les autres appareils'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Déconnecter'));
    await tester.pumpAndSettle();

    verify(() => ds.closeMyOthers()).called(1);
    expect(find.text('Toutes les autres sessions fermées'), findsOneWidget);
  });

  testWidgets('offline: a French message, never a technical one', (tester) async {
    when(() => ds.mine()).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/v1/auth/sessions'),
        type: DioExceptionType.connectionError));
    await pump(tester);

    expect(find.text('Connectez-vous à internet pour voir vos appareils.'),
        findsOneWidget);
  });

  test('the data source reads the wrapped list and never needs an id to display', () async {
    final dio = _MockDio();
    when(() => dio.get<Map<String, dynamic>>('/api/v1/auth/sessions'))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/auth/sessions'),
              data: {
                'data': [
                  {
                    'sessionId': 's1',
                    'deviceLabel': null,
                    'lastUsedAt': '2026-11-01T08:00:00Z',
                    'current': true,
                  }
                ]
              },
            ));

    final list = await RemoteDeviceSessionsDataSource(dio).mine();

    expect(list.single.label, 'Appareil inconnu');
    expect(list.single.current, isTrue);
    expect(deviceLastUsedLabel(list.single.lastUsedAt),
        'Dernière activité le 01/11/2026 à 09:00');
  });

  group('EmployeeDevicesSection errors', () {
    Future<void> pumpSection(WidgetTester tester, Object error) async {
      await tester.pumpWidget(ProviderScope(
        retry: noProviderRetry,
        overrides: [
          employeeDeviceSessionsProvider.overrideWith((ref, id) async => throw error),
        ],
        child: const MaterialApp(
            home: Scaffold(body: EmployeeDevicesSection(employeeId: 'e1'))),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('offline → « hors connexion »', (tester) async {
      await pumpSection(tester, DioException(
          requestOptions: RequestOptions(path: '/x'),
          type: DioExceptionType.connectionError));
      expect(find.text('Liste indisponible hors connexion'), findsOneWidget);
    });

    testWidgets('server refusal → its French message, not « hors connexion »',
        (tester) async {
      final req = RequestOptions(path: '/api/v1/employees/e1/sessions');
      await pumpSection(tester, DioException(
          requestOptions: req,
          type: DioExceptionType.badResponse,
          response: Response(requestOptions: req, statusCode: 404, data: {
            'error': 'Employé introuvable',
            'domainCode': 'EMPLOYEE_NOT_FOUND',
          })));
      expect(find.text('Employé introuvable'), findsOneWidget);
      expect(find.text('Liste indisponible hors connexion'), findsNothing);
    });
  });
}
