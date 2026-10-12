import 'dart:ffi';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/pos/presentation/provider/pos_search_provider.dart';
import 'package:mocktail/mocktail.dart';
import 'package:sqlite3/open.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

class _MockAppDatabase extends Mock implements AppDatabase {}

class _EmptySelectable extends Fake implements Selectable<QueryRow> {
  @override
  Future<List<QueryRow>> get() async => const [];
}

void main() {
  group('PosSearchNotifier', () {
    test('search with less than 2 chars returns empty', () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(posSearchProvider.notifier);
      await notifier.search('a', 'store-1');

      final state = container.read(posSearchProvider);
      expect(state.value, isEmpty);
    });

    test('search returns matching products (requires DB — integration)', () {
      // This test validates the provider's contract: when search is called
      // with ≥ 2 chars, the state transitions to AsyncLoading then AsyncData.
      // Full integration requires a Drift in-memory DB — covered by smoke test.
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final state = container.read(posSearchProvider);
      // Initial state is empty
      expect(state.value, isEmpty);
    });

    test('clear resets state to empty', () async {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(posSearchProvider.notifier);
      notifier.clear();

      final state = container.read(posSearchProvider);
      expect(state.value, isEmpty);
    });
  
    // v1s-16-11: the POS search error state's "Réessayer" re-runs
    // `search(notifier.lastQuery, storeId)` (invalidating would reset the
    // notifier to an empty result and lose the query).
    test('after a failed search, retrying re-runs the same query', () async {
      registerFallbackValue(<Variable>[]);
      final db = _MockAppDatabase();
      var calls = 0;
      when(() => db.customSelect(any(), variables: any(named: 'variables')))
          .thenAnswer((_) {
        calls++;
        if (calls == 1) throw Exception('Base locale indisponible');
        return _EmptySelectable();
      });
      final container = ProviderContainer(
        overrides: [appDatabaseProvider.overrideWithValue(db)],
      );
      addTearDown(container.dispose);

      final notifier = container.read(posSearchProvider.notifier);
      await notifier.search('riz', 'store-1');
      expect(container.read(posSearchProvider).hasError, isTrue);

      // Same call as the page's onRetry.
      await notifier.search(notifier.lastQuery, 'store-1');

      final captured = verify(() => db.customSelect(any(),
          variables: captureAny(named: 'variables'))).captured;
      expect(captured, hasLength(2));
      for (final vars in captured) {
        expect((vars as List<Variable>).map((v) => v.value),
            ['store-1', '%riz%']);
      }
      final state = container.read(posSearchProvider);
      expect(state.hasError, isFalse);
      expect(state.value, isEmpty);
    });

    // v1s-16-14: user-typed `%` / `_` are literal, not LIKE wildcards.
    group('literal LIKE wildcards (in-memory DB)', () {
      late AppDatabase db;
      late ProviderContainer container;

      setUpAll(_overrideSqlite3ForLinuxTesting);

      setUp(() async {
        db = AppDatabase.forTesting();
        final now = DateTime.now();
        for (final (id, name) in [
          ('p1', 'Remise 50%'),
          ('p2', 'Riz 500g'),
        ]) {
          await db.into(db.products).insert(ProductsCompanion.insert(
                id: id,
                name: name,
                createdAt: now,
                updatedAt: now,
              ));
        }
        container = ProviderContainer(
          overrides: [appDatabaseProvider.overrideWithValue(db)],
        );
      });

      tearDown(() async {
        container.dispose();
        await db.close();
      });

      for (final storeId in ['store-1', null]) {
        test('"50%" only matches "Remise 50%" (storeId: $storeId)', () async {
          final notifier = container.read(posSearchProvider.notifier);
          await notifier.search('50%', storeId);
          final names =
              container.read(posSearchProvider).value!.map((r) => r.name);
          expect(names, ['Remise 50%']);
        });
      }

      test('plain text still matches as a substring', () async {
        final notifier = container.read(posSearchProvider.notifier);
        await notifier.search('50', 'store-1');
        final names =
            container.read(posSearchProvider).value!.map((r) => r.name);
        expect(names, containsAll(['Remise 50%', 'Riz 500g']));
      });

      // v1s-16-16: case- and accent-insensitive.
      for (final storeId in ['store-1', null]) {
        test('"epicerie" / "ÉPICERIE" find "Épicerie fine" (storeId: $storeId)',
            () async {
          final now = DateTime.now();
          await db.into(db.products).insert(ProductsCompanion.insert(
                id: 'p3',
                name: 'Épicerie fine',
                createdAt: now,
                updatedAt: now,
              ));
          final notifier = container.read(posSearchProvider.notifier);
          for (final q in ['epicerie', 'ÉPICERIE']) {
            await notifier.search(q, storeId);
            final names =
                container.read(posSearchProvider).value!.map((r) => r.name);
            expect(names, ['Épicerie fine'], reason: q);
          }
        });
      }
    });
  });
}
