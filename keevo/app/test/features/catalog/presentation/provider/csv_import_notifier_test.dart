import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/catalog/domain/model/csv_import_result.dart';
import 'package:keevo/features/catalog/domain/repository/product_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/csv_import_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/product_provider.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';

class _MockProductRepository extends Mock implements ProductRepository {}

/// The real notifier, from a file already picked (the file picker itself is a
/// platform plugin).
class _PickedCsvImportNotifier extends CsvImportNotifier {
  _PickedCsvImportNotifier(this.picked);

  final CsvImportFilePicked picked;

  @override
  CsvImportState build() => picked;
}

class _NoActiveStore extends ActiveStoreNotifier {
  @override
  String? build() => null;
}

void main() {
  setUpAll(() => registerFallbackValue(Uint8List(0)));

  test('after a failed import, retrying sends the same file again and succeeds',
      () async {
    final repo = _MockProductRepository();
    final bytes = Uint8List.fromList('nom,prix\nRiz,2500\n'.codeUnits);
    final picked = CsvImportFilePicked(
      bytes: bytes,
      fileName: 'produits.csv',
      detectedHeaders: const ['nom', 'prix'],
    );
    var calls = 0;
    when(() => repo.importCsv(
          csvBytes: any(named: 'csvBytes'),
          fileName: any(named: 'fileName'),
          columnMapping: any(named: 'columnMapping'),
          storeId: any(named: 'storeId'),
        )).thenAnswer((_) async {
      calls++;
      if (calls == 1) throw Exception('offline');
      return const CsvImportResult(imported: 1, skipped: 0);
    });

    final container = ProviderContainer(retry: noProviderRetry, overrides: [
      productRepositoryProvider.overrideWithValue(repo),
      activeStoreIdProvider.overrideWith(_NoActiveStore.new),
      csvImportNotifierProvider.overrideWith(() => _PickedCsvImportNotifier(picked)),
    ]);
    addTearDown(container.dispose);
    container.listen(csvImportNotifierProvider, (_, __) {});
    final notifier = container.read(csvImportNotifierProvider.notifier);
    const mapping = {'nom': 'name', 'prix': 'price'};

    await notifier.runImport(mapping: mapping);
    final afterFailure = container.read(csvImportNotifierProvider);
    expect(afterFailure, isA<CsvImportError>());
    // The file is kept so the mapping step stays on screen for a retry.
    expect((afterFailure as CsvImportError).picked, same(picked));

    await notifier.runImport(mapping: mapping);
    expect(container.read(csvImportNotifierProvider), isA<CsvImportSuccess>());
    final sent = verify(() => repo.importCsv(
          csvBytes: captureAny(named: 'csvBytes'),
          fileName: 'produits.csv',
          columnMapping: mapping,
          storeId: any(named: 'storeId'),
        )).captured;
    expect(sent, [bytes, bytes]);
  });
}
