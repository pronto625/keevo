import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/features/contact/domain/model/client_model.dart';
import 'package:keevo/features/contact/domain/repository/client_repository.dart';
import 'package:keevo/features/contact/presentation/page/client_form_page.dart';
import 'package:keevo/features/contact/presentation/provider/contact_provider.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

class _MockClientRepository extends Mock implements ClientRepository {}

final _now = DateTime(2026, 1, 1);
final _awa = ClientModel(
    id: 'c1',
    name: 'Awa',
    phone: '+237690000001',
    createdAt: _now,
    updatedAt: _now);

/// Story 15.4b — the form only reads the auto-dispose client list notifier
/// (nobody listens to it here): under Riverpod 3 its save must still succeed
/// past the server await instead of failing on a disposed ref.
void main() {
  late _MockClientRepository repo;

  setUp(() {
    repo = _MockClientRepository();
    when(() => repo.getAll(includeArchived: any(named: 'includeArchived')))
        .thenAnswer((_) async => [_awa]);
  });

  Future<void> pumpForm(WidgetTester tester, {ClientModel? existing}) async {
    // Tall phone screen: the whole form (and its save button) is on screen.
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(
        path: '/',
        builder: (_, __) => const Scaffold(body: Text('Liste')),
        routes: [
          GoRoute(
            path: 'form',
            builder: (_, __) => ClientFormPage(existing: existing),
          ),
        ],
      ),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [clientRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp.router(routerConfig: router),
    ));
    router.push('/form');
    await tester.pumpAndSettle();
  }

  testWidgets('create: the client is saved and the form closes',
      (tester) async {
    when(() => repo.create(
          name: any(named: 'name'),
          phone: any(named: 'phone'),
          email: any(named: 'email'),
          notes: any(named: 'notes'),
        )).thenAnswer((_) =>
        Future.delayed(const Duration(milliseconds: 200), () => _awa));
    await pumpForm(tester);

    await tester.enterText(
        find.widgetWithText(TextFormField, 'Nom complet *'), 'Awa');
    await tester.enterText(
        find.widgetWithText(TextFormField, 'Téléphone *'), '+237690000001');
    await tester.tap(find.text('Ajouter le client'));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();

    verify(() => repo.create(
          name: 'Awa',
          phone: any(named: 'phone'),
          email: null,
          notes: null,
        )).called(1);
    expect(find.text('Client ajouté'), findsOneWidget);
    expect(find.text('Liste'), findsOneWidget); // the form closed
    expect(tester.takeException(), isNull);
  });

  testWidgets('edit: the change is saved and the form closes',
      (tester) async {
    when(() => repo.update(
          id: any(named: 'id'),
          name: any(named: 'name'),
          phone: any(named: 'phone'),
          email: any(named: 'email'),
          notes: any(named: 'notes'),
        )).thenAnswer((_) =>
        Future.delayed(const Duration(milliseconds: 200), () => _awa));
    await pumpForm(tester, existing: _awa);

    await tester.enterText(
        find.widgetWithText(TextFormField, 'Nom complet *'), 'Awa Diallo');
    await tester.tap(find.text('Enregistrer les modifications'));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();

    verify(() => repo.update(
          id: 'c1',
          name: 'Awa Diallo',
          phone: any(named: 'phone'),
          email: null,
          notes: null,
        )).called(1);
    expect(find.text('Client mis à jour'), findsOneWidget);
    expect(find.text('Liste'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
