import 'dart:ffi';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/inventory/data/datasource/local_stock_transfer_datasource.dart';
import 'package:keevo/features/inventory/domain/model/stock_transfer_model.dart';
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

StockTransferModel _buildTransfer({
  required String id,
  required String sourceStoreId,
  required String destinationStoreId,
  String status = 'COMPLETED',
}) =>
    StockTransferModel(
      id: id,
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      productId: 'prod-001',
      quantity: 5,
      actorId: 'actor-001',
      occurredAt: DateTime(2026, 3, 14),
      status: status,
    );

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    _overrideSqlite3ForLinuxTesting();
  });

  group('LocalStockTransferDataSource (Story 3.3 — Task 13)', () {
    late AppDatabase db;
    late LocalStockTransferDataSource datasource;

    setUp(() {
      db = AppDatabase.forTesting();
      datasource = LocalStockTransferDataSource(db);
    });

    tearDown(() async {
      await db.close();
    });

    test('saveTransfer should persist a transfer to the database', () async {
      final transfer = _buildTransfer(
        id: 'tf-001',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
      );

      await datasource.saveTransfer(transfer);

      final results = await datasource.getHistory(sourceStoreId: 'src-001');
      expect(results, hasLength(1));
      expect(results.first.id, 'tf-001');
      expect(results.first.sourceStoreId, 'src-001');
    });

    test('getHistory filters by sourceStoreId', () async {
      await datasource.saveTransfer(_buildTransfer(
        id: 'tf-001',
        sourceStoreId: 'src-A',
        destinationStoreId: 'dst-001',
      ));
      await datasource.saveTransfer(_buildTransfer(
        id: 'tf-002',
        sourceStoreId: 'src-B',
        destinationStoreId: 'dst-001',
      ));

      final results = await datasource.getHistory(sourceStoreId: 'src-A');
      expect(results, hasLength(1));
      expect(results.first.id, 'tf-001');
    });

    test('getHistory filters by destinationStoreId', () async {
      await datasource.saveTransfer(_buildTransfer(
        id: 'tf-001',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-X',
      ));
      await datasource.saveTransfer(_buildTransfer(
        id: 'tf-002',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-Y',
      ));

      final results =
          await datasource.getHistory(destinationStoreId: 'dst-X');
      expect(results, hasLength(1));
      expect(results.first.id, 'tf-001');
    });

    test('getHistory returns empty list when no match', () async {
      await datasource.saveTransfer(_buildTransfer(
        id: 'tf-001',
        sourceStoreId: 'src-001',
        destinationStoreId: 'dst-001',
      ));

      final results = await datasource.getHistory(sourceStoreId: 'src-999');
      expect(results, isEmpty);
    });
  });
}
