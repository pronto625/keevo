import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/connectivity_service.dart';
import 'package:keevo/features/help/domain/model/tutorial.dart';
import 'package:keevo/features/help/domain/repository/tutorial_repository.dart';
import 'package:keevo/features/help/presentation/page/help_tutorials_page.dart';
import 'package:keevo/features/help/presentation/provider/help_provider.dart';

class _MockRepo extends Mock implements TutorialRepository {}

class _MockConnectivity extends Mock implements ConnectivityService {}

void main() {
  const channel = MethodChannel('plugins.flutter.io/url_launcher');
  final launched = <String>[];
  late _MockRepo repo;
  late _MockConnectivity connectivity;

  const vendre = Tutorial(
    id: 'vendre',
    module: 'Vendre',
    title: 'Enregistrer une vente',
    durationSeconds: 120,
    description: 'Encaisser une vente en quelques secondes.',
    url: 'https://youtu.be/abc',
  );
  const stock = Tutorial(
    id: 'stock',
    module: 'Catalogue & stock',
    title: 'Gérer son stock',
    durationSeconds: 120,
  );

  setUp(() {
    launched.clear();
    repo = _MockRepo();
    connectivity = _MockConnectivity();
    when(() => connectivity.isOnline()).thenAnswer((_) async => true);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'launch') {
        launched.add((call.arguments as Map)['url'] as String);
        return true;
      }
      return false;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Widget page() => ProviderScope(
        overrides: [
          tutorialRepositoryProvider.overrideWithValue(repo),
          connectivityServiceProvider.overrideWithValue(connectivity),
        ],
        child: const MaterialApp(home: HelpTutorialsPage()),
      );

  testWidgets('lists tutorials with module and duration', (tester) async {
    when(() => repo.getTutorials()).thenAnswer(
        (_) async => const TutorialCatalog(tutorials: [vendre, stock]));
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    expect(find.text('Enregistrer une vente'), findsOneWidget);
    expect(find.text('Vendre · ~2 min'), findsOneWidget);
    expect(find.text('Encaisser une vente en quelques secondes.'),
        findsOneWidget);
    expect(find.textContaining('Hors ligne'), findsNothing);
  });

  testWidgets('shouldOpenTutorialVideoExternally', (tester) async {
    when(() => repo.getTutorials())
        .thenAnswer((_) async => const TutorialCatalog(tutorials: [vendre]));
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Enregistrer une vente'));
    await tester.pumpAndSettle();

    expect(launched, ['https://youtu.be/abc']);
  });

  testWidgets('tutorial without URL: disabled, « Bientôt disponible »',
      (tester) async {
    when(() => repo.getTutorials())
        .thenAnswer((_) async => const TutorialCatalog(tutorials: [stock]));
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    expect(find.text('Bientôt disponible'), findsOneWidget);
    final tile = tester.widget<ListTile>(find.byType(ListTile));
    expect(tile.enabled, isFalse);
    await tester.tap(find.text('Gérer son stock'));
    await tester.pumpAndSettle();
    expect(launched, isEmpty);
  });

  testWidgets('offline with cache: cached list, notice, tap → snackbar',
      (tester) async {
    when(() => connectivity.isOnline()).thenAnswer((_) async => false);
    when(() => repo.getTutorials()).thenAnswer((_) async => TutorialCatalog(
        tutorials: const [vendre],
        fromCache: true,
        fetchedAt: DateTime(2026, 10, 12)));
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    expect(find.textContaining('Hors ligne'), findsOneWidget);
    await tester.tap(find.text('Enregistrer une vente'));
    await tester.pumpAndSettle();

    expect(launched, isEmpty);
    expect(find.text('Connexion internet requise pour regarder la vidéo.'),
        findsOneWidget);
  });

  testWidgets('offline without cache: AppErrorWidget, « Réessayer » refetches',
      (tester) async {
    var calls = 0;
    when(() => repo.getTutorials()).thenAnswer((_) async {
      calls++;
      if (calls == 1) throw Exception('Pas de connexion');
      return const TutorialCatalog(tutorials: [vendre]);
    });
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    expect(find.text('Tutoriels indisponibles'), findsOneWidget);
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();

    expect(calls, 2);
    expect(find.text('Enregistrer une vente'), findsOneWidget);
  });
}
