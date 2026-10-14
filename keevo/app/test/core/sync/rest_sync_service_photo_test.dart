import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value, Variable;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/photo/product_photo_files.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/core/sync/product_photo_outbox.dart';
import 'package:keevo/core/sync/rest_sync_service.dart';
import 'package:keevo/features/catalog/data/datasource/remote_product_datasource.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockDio extends Mock implements Dio {}

class MockRemoteProductDataSource extends Mock
    implements RemoteProductDataSource {}

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

/// FR21 — photo operations inside the sync cycle: kept out of the JSON
/// batches, sent after them, and the pull merge of `photoUrl`.
void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    registerFallbackValue(Options());
  });

  late AppDatabase db;
  late MockDio dio;
  late Directory root;
  late ProductPhotoFiles files;
  late RestSyncService sync;

  const serverUrl = '/api/v1/products/prod-1/photo?v=1700000000000';

  setUp(() async {
    db = AppDatabase.forTesting();
    dio = MockDio();
    root = await Directory.systemTemp.createTemp('rest_sync_photo_test');
    files = ProductPhotoFiles(rootDir: () async => root);
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues(
        {ProductPhotoOutbox.migrationDoneKey: true});
    final prefs = await SharedPreferences.getInstance();
    sync = RestSyncService(
      database: db,
      remoteProducts: MockRemoteProductDataSource(),
      dio: dio,
      secureStorage: const FlutterSecureStorage(),
      prefs: prefs,
      photoOutbox: ProductPhotoOutbox(
          database: db, dio: dio, files: files, prefs: prefs),
    );
  });

  tearDown(() async {
    await db.close();
    if (root.existsSync()) root.deleteSync(recursive: true);
  });

  Future<void> insertProduct(String id, {String? photoUrl}) =>
      db.customStatement(
        'INSERT INTO products (id, name, price, buy_price, stock_quantity, '
        'archived, status, created_at, updated_at, photo_url) '
        "VALUES (?, ?, 500, 300, 0, 0, 'ACTIVE', ?, ?, ?)",
        [
          id, 'Produit $id', '2026-10-01T10:00:00.000Z',
          '2026-10-01T10:00:00.000Z', photoUrl,
        ],
      );

  Future<String?> photoOf(String id) async => (await db
          .customSelect('SELECT photo_url FROM products WHERE id = ?',
              variables: [Variable<String>(id)])
          .getSingle())
      .read<String?>('photo_url');

  group('push', () {
    test(
        'the photo op is not in the JSON batch and is uploaded after it '
        '(product creation first)', () async {
      await insertProduct('prod-1');
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'create-1',
            operation: 'CREATE_PRODUCT',
            payload: '{"name":"Savon"}',
            createdAt: DateTime(2026, 10, 1, 9),
            entityId: const Value('prod-1'),
          ));
      final path =
          await files.savePending('prod-1', Uint8List.fromList([1, 2]));
      await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
            id: 'photo-1',
            operation: ProductPhotoOutbox.uploadOperation,
            payload: jsonEncode({'productId': 'prod-1', 'localPath': path}),
            createdAt: DateTime(2026, 10, 1, 10),
            entityId: const Value('prod-1'),
          ));
      await db.customStatement(
          'UPDATE products SET photo_url = ? WHERE id = ?', [path, 'prod-1']);

      final calls = <String>[];
      when(() => dio.post<dynamic>(any(), data: any(named: 'data')))
          .thenAnswer((inv) async {
        calls.add('batch');
        final body = inv.namedArguments[#data] as Map<String, dynamic>;
        final ops = body['operations'] as List;
        expect(ops.map((o) => o['operationType']), ['CREATE_PRODUCT']);
        return Response(
          requestOptions: RequestOptions(path: '/api/v1/sync/push'),
          statusCode: 200,
          data: {
            'data': {
              'results': [
                {
                  'operationId': 'create-1',
                  'status': 'APPLIED',
                  'serverEntityId': 'prod-1',
                  'reason': null,
                  'conflictData': null,
                }
              ]
            }
          },
        );
      });
      when(() =>
              dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
          .thenAnswer((_) async {
        calls.add('photo');
        return Response(
          requestOptions: RequestOptions(path: '/photo'),
          statusCode: 200,
          data: {
            'data': {'photoUrl': serverUrl}
          },
        );
      });

      await sync.push();

      expect(calls, ['batch', 'photo']);
      expect(await photoOf('prod-1'), serverUrl);
      expect(await db.select(db.syncQueue).get(), isEmpty);
    });

    test('only photo ops queued: no JSON batch, photo still sent', () async {
      await insertProduct('prod-1');
      final path = await files.savePending('prod-1', Uint8List.fromList([1]));
      await ProductPhotoOutbox(database: db, dio: dio, files: files)
          .enqueueUpload('prod-1', path);
      when(() =>
              dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: '/photo'),
                statusCode: 200,
                data: {
                  'data': {'photoUrl': serverUrl}
                },
              ));

      await sync.push();

      verifyNever(() => dio.post<dynamic>(any(), data: any(named: 'data')));
      expect(await photoOf('prod-1'), serverUrl);
    });
  });

  group('pull merge of photoUrl', () {
    void stubPull(List<Map<String, dynamic>> products) {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            queryParameters: any(named: 'queryParameters'),
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: '/api/v1/sync/pull'),
            statusCode: 200,
            data: {
              'data': {
                'serverTimestamp': '2026-10-14T12:00:00.000Z',
                'entities': {'products': products},
                'counts': <String, dynamic>{},
              }
            },
          ));
    }

    Map<String, dynamic> serverProduct(String id, String? photoUrl,
            {String name = 'Nom serveur'}) =>
        {
          'id': id,
          'name': name,
          'price': 700,
          'buyPrice': 300,
          'transportCost': 0,
          'stockQuantity': 0,
          'photoUrl': photoUrl,
          'archived': false,
          'status': 'ACTIVE',
          'createdAt': '2026-10-01T10:00:00.000Z',
          'updatedAt': '2026-10-14T10:00:00.000Z',
        };

    test('another device\'s photo arrives through the pull', () async {
      await insertProduct('prod-1');
      stubPull([serverProduct('prod-1', serverUrl)]);

      await sync.pull();

      expect(await photoOf('prod-1'), serverUrl);
    });

    test('a photo deleted on another device is removed here', () async {
      await insertProduct('prod-1', photoUrl: '/api/v1/products/prod-1/photo?v=1');
      stubPull([serverProduct('prod-1', null)]);

      await sync.pull();

      expect(await photoOf('prod-1'), isNull);
    });

    test('a photo taken here and not uploaded yet is kept', () async {
      await insertProduct('prod-1', photoUrl: '/data/user/0/x/product_images/p.jpg');
      stubPull([serverProduct('prod-1', null)]);

      await sync.pull();

      expect(await photoOf('prod-1'), '/data/user/0/x/product_images/p.jpg');
    });

    test(
        'a queued photo change wins over the server photo, but the rest of '
        'the product is still updated', () async {
      await insertProduct('prod-1');
      await ProductPhotoOutbox(database: db, dio: dio, files: files)
          .enqueueDelete('prod-1');
      stubPull([serverProduct('prod-1', serverUrl, name: 'Nouveau nom')]);

      await sync.pull();

      final row = await (db.select(db.products)
            ..where((t) => t.id.equals('prod-1')))
          .getSingle();
      expect(row.photoUrl, isNull);
      expect(row.name, 'Nouveau nom');
    });
  });
}
