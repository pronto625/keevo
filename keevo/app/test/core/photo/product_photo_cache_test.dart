import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/photo/product_photo_cache.dart';
import 'package:keevo/core/photo/product_photo_files.dart';
import 'package:mocktail/mocktail.dart';

class MockDio extends Mock implements Dio {}

void main() {
  setUpAll(() => registerFallbackValue(Options()));

  late Directory root;
  late MockDio dio;
  late DateTime now;
  late ProductPhotoCache cache;

  const url = '/api/v1/products/p1/photo?v=5';

  setUp(() async {
    root = await Directory.systemTemp.createTemp('photo_cache_test');
    dio = MockDio();
    now = DateTime(2026, 10, 14, 12);
    cache = ProductPhotoCache(
      dio: dio,
      files: ProductPhotoFiles(rootDir: () async => root),
      clock: () => now,
    );
  });

  tearDown(() {
    if (root.existsSync()) root.deleteSync(recursive: true);
  });

  void stubBytes(List<int> bytes) {
    when(() => dio.get<List<int>>(any(), options: any(named: 'options')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: url),
              statusCode: 200,
              data: bytes,
            ));
  }

  test('downloads once with the authenticated client, then reads the copy',
      () async {
    stubBytes([1, 2, 3]);

    final first = await cache.resolve(url);
    final second = await cache.resolve(url);

    expect(first!.readAsBytesSync(), [1, 2, 3]);
    expect(second!.path, first.path);
    final captured = verify(() =>
            dio.get<List<int>>(captureAny(), options: captureAny(named: 'options')))
        .captured;
    expect(captured[0], url);
    expect((captured[1] as Options).responseType, ResponseType.bytes);
  });

  test('offline: null (initials shown), not retried before 30 s', () async {
    when(() => dio.get<List<int>>(any(), options: any(named: 'options')))
        .thenThrow(DioException(
            requestOptions: RequestOptions(path: url),
            type: DioExceptionType.connectionError));

    expect(await cache.resolve(url), isNull);
    expect(await cache.resolve(url), isNull);
    verify(() => dio.get<List<int>>(any(), options: any(named: 'options')))
        .called(1);

    now = now.add(const Duration(seconds: 31));
    stubBytes([4]);
    expect((await cache.resolve(url))!.readAsBytesSync(), [4]);
  });
}
