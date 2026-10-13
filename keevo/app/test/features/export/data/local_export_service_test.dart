import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/export/data/local/local_csv_writer.dart';
import 'package:keevo/features/export/data/local/local_export_reader.dart';
import 'package:keevo/features/export/data/local/local_export_service.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/local/local_export_schema.dart';

import '../export_test_helpers.dart';

/// Writer whose page writes fail like a full disk.
class _FailingWriter extends LocalCsvWriter {
  int pages = 0;

  @override
  Future<void> addRows(List<List<Object?>> rows) async {
    pages++;
    throw const FileSystemException('No space left on device');
  }
}

/// v1s-18-5 — reader → worker-isolate writer → file.
void main() {
  late AppDatabase db;
  late Directory temp;

  setUpAll(overrideSqliteForLinuxTests);

  setUp(() {
    db = AppDatabase.forTesting();
    temp = Directory.systemTemp.createTempSync('local_export_service');
  });

  tearDown(() async {
    await db.close();
    if (temp.existsSync()) temp.deleteSync(recursive: true);
  });

  Future<void> seedSales(int n) async {
    final t0 = DateTime.utc(2026, 9, 1);
    await db.batch((b) {
      for (var i = 0; i < n; i++) {
        b.insert(
            db.sales,
            SalesCompanion.insert(
                id: 'sale-${i.toString().padLeft(5, '0')}',
                storeId: 's-1',
                employeeId: 'u-1',
                totalAmount: 1000 + i,
                paymentMode: 'CASH',
                occurredAt: Value(t0.add(Duration(minutes: i))),
                createdAt: t0));
      }
    });
  }

  test('10,000 sales → 10,000 data rows, pages of at most 500', () async {
    await seedSales(10000);
    final progress = <int>[];
    final service = LocalExportService(LocalExportReader(db));
    final file = await service.export(
      datasets: {'sales'},
      filter: const LocalExportFilter(),
      tempDir: temp,
      onProgress: progress.add,
      now: DateTime.utc(2026, 10, 12, 23, 30),
    );
    expect(file.uri.pathSegments.last,
        'keevo-donnees-appareil-ventes-20261013-0030.csv');
    expect(file.parent.parent.path,
        '${temp.path}${Platform.pathSeparator}exports');
    final text = utf8.decode(file.readAsBytesSync().sublist(3));
    final lines = text.split('\r\n')..removeLast();
    expect(lines.first, startsWith('Référence de la vente;Date;Boutique;Vendeur'));
    expect(lines, hasLength(10001));
    expect(lines[1],
        startsWith('Vente du 01/09/2026 à 01:00:00;01/09/2026 01:00:00;'));
    expect(progress, hasLength(20));
    expect(progress.last, 10000);
    for (var i = 1; i < progress.length; i++) {
      expect(progress[i] - progress[i - 1], lessThanOrEqualTo(500));
    }
  }, timeout: const Timeout(Duration(minutes: 2)));

  test('several datasets → zip named « export »', () async {
    await seedSales(3);
    final file = await LocalExportService(LocalExportReader(db)).export(
      datasets: {'sales', 'products', 'unknown'},
      filter: const LocalExportFilter(),
      tempDir: temp,
      now: DateTime.utc(2026, 1, 2, 3, 4),
    );
    expect(file.uri.pathSegments.last,
        'keevo-donnees-appareil-export-20260102-0404.zip');
    expect(file.existsSync(), isTrue);
  });

  test('write failure: storage error, partial folder deleted', () async {
    await seedSales(3);
    final writer = _FailingWriter();
    final service =
        LocalExportService(LocalExportReader(db), writerFactory: () => writer);
    await expectLater(
      service.export(
          datasets: {'sales'},
          filter: const LocalExportFilter(),
          tempDir: temp),
      throwsA(same(ExportException.storage)),
    );
    expect(writer.pages, 1);
    final exports = Directory('${temp.path}/exports');
    expect(exports.existsSync() ? exports.listSync() : const [], isEmpty);
  });

  test('stale exports are purged before a local export', () async {
    await seedSales(1);
    Directory('${temp.path}/exports').createSync(recursive: true);
    // A directory's mtime cannot be set from Dart: a stale entry directly
    // under exports/ stands for an old export folder.
    final stale = File('${temp.path}/exports/stale.csv')
      ..writeAsStringSync('x')
      ..setLastModifiedSync(DateTime.now().subtract(const Duration(hours: 25)));
    final fresh = File('${temp.path}/exports/fresh.csv')
      ..writeAsStringSync('y');

    final file = await LocalExportService(LocalExportReader(db)).export(
      datasets: {'sales'},
      filter: const LocalExportFilter(),
      tempDir: temp,
    );
    expect(stale.existsSync(), isFalse);
    expect(fresh.existsSync(), isTrue);
    expect(file.existsSync(), isTrue);
  });

  test('file name marks « données de cet appareil » in WAT', () {
    expect(localExportFileName('products', DateTime.utc(2026, 10, 12, 22, 59)),
        'keevo-donnees-appareil-produits-20261012-2359.csv');
    expect(localExportFileName(null, DateTime.utc(2026, 10, 12, 23, 0)),
        'keevo-donnees-appareil-export-20261013-0000.zip');
    expect(localExportCatalog, hasLength(16));
  });
}
