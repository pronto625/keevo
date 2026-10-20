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
  DateTime? occurredAt,
}) =>
    StockTransferModel(
      id: id,
      sourceStoreId: sourceStoreId,
      destinationStoreId: destinationStoreId,
      productId: 'prod-001',
      quantity: 5,
      actorId: 'actor-001',
      occurredAt: occurredAt ?? DateTime(2026, 3, 14),
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
  
    // ── v1s-16-23 — store OR filter, status, paging ──────────────────────

    test('getHistory storeId keeps transfers sent AND received by the store',
        () async {
      await datasource.saveTransfer(_buildTransfer(
          id: 'sent', sourceStoreId: 'S', destinationStoreId: 'B'));
      await datasource.saveTransfer(_buildTransfer(
          id: 'recv', sourceStoreId: 'A', destinationStoreId: 'S'));
      await datasource.saveTransfer(_buildTransfer(
          id: 'other', sourceStoreId: 'A', destinationStoreId: 'B'));

      final results = await datasource.getHistory(storeId: 'S');
      expect(results.map((t) => t.id).toSet(), {'sent', 'recv'});
    });

    test('getHistory status keeps one status (pending included offline)',
        () async {
      await datasource.saveTransfer(_buildTransfer(
          id: 'done', sourceStoreId: 'S', destinationStoreId: 'B'));
      await datasource.saveTransfer(_buildTransfer(
          id: 'pending',
          sourceStoreId: 'S',
          destinationStoreId: 'B',
          status: 'PENDING_SYNC'));

      final completed =
          await datasource.getHistory(storeId: 'S', status: 'COMPLETED');
      expect(completed.map((t) => t.id), ['done']);
      final pending =
          await datasource.getHistory(storeId: 'S', status: 'PENDING_SYNC');
      expect(pending.map((t) => t.id), ['pending']);
    });

    test('getHistory pages newest first without overlap', () async {
      for (var i = 0; i < 5; i++) {
        await datasource.saveTransfer(_buildTransfer(
          id: 'tf-$i',
          sourceStoreId: 'S',
          destinationStoreId: 'B',
          occurredAt: DateTime(2026, 1, 1 + i),
        ));
      }
      final p0 = await datasource.getHistory(storeId: 'S', pageSize: 2);
      final p1 =
          await datasource.getHistory(storeId: 'S', page: 1, pageSize: 2);
      final p2 =
          await datasource.getHistory(storeId: 'S', page: 2, pageSize: 2);
      expect(p0.map((t) => t.id), ['tf-4', 'tf-3']);
      expect(p1.map((t) => t.id), ['tf-2', 'tf-1']);
      expect(p2.map((t) => t.id), ['tf-0']);
    });
  
    test('getHistory with equal occurredAt pages by id desc, each id once',
        () async {
      final at = DateTime(2026, 2, 2, 10);
      for (final id in ['tf-c', 'tf-a', 'tf-e', 'tf-b', 'tf-d']) {
        await datasource.saveTransfer(_buildTransfer(
            id: id, sourceStoreId: 'S', destinationStoreId: 'B', occurredAt: at));
      }
      final ids = <String>[];
      for (var page = 0; page < 3; page++) {
        final rows = await datasource.getHistory(
            storeId: 'S', page: page, pageSize: 2);
        ids.addAll(rows.map((t) => t.id));
      }
      expect(ids, ['tf-e', 'tf-d', 'tf-c', 'tf-b', 'tf-a']);
    });
});
}
