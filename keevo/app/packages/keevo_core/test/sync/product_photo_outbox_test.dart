import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value, Variable;
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/photo/product_photo_compressor.dart';
import 'package:keevo_core/photo/product_photo_files.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/product_photo_outbox.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

class MockDio extends Mock implements Dio {}

class _FakeCompressor extends ProductPhotoCompressor {
  const _FakeCompressor();

  @override
  Future<Uint8List> compress(Uint8List source) async =>
      Uint8List.fromList([9, 9, 9]);
}

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

/// FR21 — product photos in the offline outbox: queued on the device,
/// uploaded when online, retried offline, never blocking other operations.
void main() {
  setUpAll(() {
    _overrideSqlite3ForLinuxTesting();
    registerFallbackValue(Options());
  });

  late AppDatabase db;
  late MockDio dio;
  late Directory root;
  late ProductPhotoFiles files;
  late SharedPreferences prefs;
  late ProductPhotoOutbox outbox;

  const productId = 'prod-1';
  const serverUrl = '/api/v1/products/prod-1/photo?v=1700000000000';

  setUp(() async {
    db = AppDatabase.forTesting();
    dio = MockDio();
    root = await Directory.systemTemp.createTemp('photo_outbox_test');
    files = ProductPhotoFiles(rootDir: () async => root);
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    outbox = ProductPhotoOutbox(
      database: db,
      dio: dio,
      files: files,
      prefs: prefs,
      compressor: const _FakeCompressor(),
    );
    await db.customStatement(
      'INSERT INTO products (id, name, price, buy_price, stock_quantity, '
      'archived, status, created_at, updated_at) '
      "VALUES (?, 'Savon', 500, 300, 0, 0, 'ACTIVE', ?, ?)",
      [productId, '2026-10-01T10:00:00.000Z', '2026-10-01T10:00:00.000Z'],
    );
  });

  tearDown(() async {
    await db.close();
    if (root.existsSync()) root.deleteSync(recursive: true);
  });

  Future<String?> photoUrl() async => (await db
          .customSelect('SELECT photo_url FROM products WHERE id = ?',
              variables: [Variable<String>(productId)])
          .getSingle())
      .read<String?>('photo_url');

  Future<List<SyncQueueData>> queue() => db.select(db.syncQueue).get();

  void stubUploadOk() {
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/photo'),
              statusCode: 200,
              data: {
                'data': {'photoUrl': serverUrl}
              },
            ));
  }

  DioException httpError(int status) => DioException(
        requestOptions: RequestOptions(path: '/photo'),
        response: Response(
          requestOptions: RequestOptions(path: '/photo'),
          statusCode: status,
          data: {'domainCode': 'PRODUCT_PHOTO_TOO_LARGE'},
        ),
        type: DioExceptionType.badResponse,
      );

  test('enqueueUpload: local file becomes the photo, one UPLOAD op queued',
      () async {
    final first = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, first);
    final second = await files.savePending(productId, Uint8List.fromList([2]));
    await outbox.enqueueUpload(productId, second);

    expect(await photoUrl(), second);
    final ops = await queue();
    expect(ops, hasLength(1), reason: 'the newer photo supersedes the older');
    expect(ops.single.operation, ProductPhotoOutbox.uploadOperation);
    expect(ops.single.entityId, productId);
    expect(jsonDecode(ops.single.payload)['localPath'], second);
    expect(File(first).existsSync(), isFalse,
        reason: 'the superseded thumbnail is deleted');
  });

  test(
      'processPending: multipart upload, photoUrl becomes the server URL, '
      'the thumbnail is kept as its local copy, op removed', () async {
    stubUploadOk();
    final path = await files.savePending(productId, Uint8List.fromList([7, 7]));
    await outbox.enqueueUpload(productId, path);

    await outbox.processPending();

    final captured = verify(() => dio.post<Map<String, dynamic>>(
        captureAny(), data: captureAny(named: 'data'))).captured;
    expect(captured[0], '/api/v1/products/$productId/photo');
    final form = captured[1] as FormData;
    expect(form.files.single.key, 'file');
    expect(form.files.single.value.contentType.toString(), 'image/jpeg');
    expect(await photoUrl(), serverUrl);
    expect(await queue(), isEmpty);
    final cached = await files.cacheFileFor(serverUrl);
    expect(cached.readAsBytesSync(), [7, 7]);
  });

  test('offline: the op stays queued with one more attempt, photo unchanged',
      () async {
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenThrow(DioException(
            requestOptions: RequestOptions(path: '/photo'),
            type: DioExceptionType.connectionError));
    final path = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, path);

    await outbox.processPending();

    final ops = await queue();
    expect(ops, hasLength(1));
    expect(ops.single.retryCount, 1);
    expect(await photoUrl(), path);
    expect(File(path).existsSync(), isTrue);
  });

  test('waits for the product creation to be on the server', () async {
    stubUploadOk();
    await db.into(db.syncQueue).insert(SyncQueueCompanion.insert(
          id: 'create-1',
          operation: 'CREATE_PRODUCT',
          payload: '{}',
          createdAt: DateTime(2026, 10, 1),
          entityId: const Value(productId),
        ));
    final path = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, path);

    await outbox.processPending();

    verifyNever(
        () => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')));
    expect(await queue(), hasLength(2));
  });

  test('definitive refusal (413): op dropped, photo kept on this device',
      () async {
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenThrow(httpError(413));
    final path = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, path);

    await outbox.processPending();

    expect(await queue(), isEmpty);
    expect(await photoUrl(), path);
  });

  test('server error (503): kept for a later retry', () async {
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenThrow(httpError(503));
    final path = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, path);

    await outbox.processPending();

    expect(await queue(), hasLength(1));
  });

  test(
      'a newer photo taken during the upload wins: the uploaded one is not '
      'adopted', () async {
    final path = await files.savePending(productId, Uint8List.fromList([1]));
    await outbox.enqueueUpload(productId, path);
    when(() => dio.post<Map<String, dynamic>>(any(), data: any(named: 'data')))
        .thenAnswer((_) async {
      await db.customStatement(
          "UPDATE products SET photo_url = '/newer.jpg' WHERE id = ?",
          [productId]);
      return Response(
        requestOptions: RequestOptions(path: '/photo'),
        statusCode: 200,
        data: {
          'data': {'photoUrl': serverUrl}
        },
      );
    });

    await outbox.processPending();

    expect(await photoUrl(), '/newer.jpg');
  });

  test('enqueueDelete: initials at once, DELETE sent on the next push',
      () async {
    await db.customStatement(
        'UPDATE products SET photo_url = ? WHERE id = ?', [serverUrl, productId]);
    when(() => dio.delete<dynamic>(any())).thenAnswer((_) async => Response(
        requestOptions: RequestOptions(path: '/photo'), statusCode: 200));

    await outbox.enqueueDelete(productId);
    expect(await photoUrl(), isNull);
    expect((await queue()).single.operation, ProductPhotoOutbox.deleteOperation);

    await outbox.processPending();

    verify(() => dio.delete<dynamic>('/api/v1/products/$productId/photo'))
        .called(1);
    expect(await queue(), isEmpty);
  });

  test(
      'migration: a photo only on the phone is compressed and queued once; '
      'server photos and missing files are left alone', () async {
    final legacy = File('${root.path}/legacy_$productId.jpg')
      ..writeAsBytesSync([1, 2, 3, 4]);
    await db.customStatement(
        'UPDATE products SET photo_url = ? WHERE id = ?', [legacy.path, productId]);
    await db.customStatement(
      'INSERT INTO products (id, name, price, buy_price, stock_quantity, '
      'archived, status, created_at, updated_at, photo_url) '
      "VALUES ('prod-2', 'Riz', 1, 1, 0, 0, 'ACTIVE', ?, ?, ?), "
      "('prod-3', 'Huile', 1, 1, 0, 0, 'ACTIVE', ?, ?, ?)",
      [
        '2026-10-01T10:00:00.000Z', '2026-10-01T10:00:00.000Z', serverUrl,
        '2026-10-01T10:00:00.000Z', '2026-10-01T10:00:00.000Z', '/gone/x.jpg',
      ],
    );

    await outbox.migrateLegacyPhotosOnce();
    await outbox.migrateLegacyPhotosOnce();

    final ops = await queue();
    expect(ops, hasLength(1));
    expect(ops.single.entityId, productId);
    final newPath = jsonDecode(ops.single.payload)['localPath'] as String;
    expect(File(newPath).readAsBytesSync(), [9, 9, 9],
        reason: 'the legacy photo is re-encoded as a thumbnail');
    expect(await photoUrl(), newPath);
    expect(legacy.existsSync(), isFalse);
    expect(prefs.getBool(ProductPhotoOutbox.migrationDoneKey), isTrue);
  });
}
