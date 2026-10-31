import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:keevo/core/photo/product_photo_providers.dart';
import 'package:keevo/core/widget/product_photo.dart';
import 'package:keevo_core/photo/product_photo_cache.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// Resolves server URLs from a fixed map — no network, no path_provider.
class _FakeCache extends ProductPhotoCache {
  _FakeCache(this.files) : super(dio: Dio());

  final Map<String, File?> files;
  final requested = <String>[];

  @override
  Future<File?> resolve(String url) async {
    requested.add(url);
    return files[url];
  }
}

void main() {
  late Directory dir;
  late File photo;

  setUpAll(() {
    dir = Directory.systemTemp.createTempSync('product_photo_widget');
    photo = File('${dir.path}/p.jpg')
      ..writeAsBytesSync(img.encodeJpg(img.Image(width: 4, height: 4)));
  });

  tearDownAll(() => dir.deleteSync(recursive: true));

  const initials = Text('SA');

  Widget host(Widget child, {ProductPhotoCache? cache}) => ProviderScope(
        retry: noProviderRetry,
        overrides: [
          if (cache != null) productPhotoCacheProvider.overrideWithValue(cache),
        ],
        child: MaterialApp(
            home: Scaffold(body: SizedBox(width: 60, height: 60, child: child))),
      );

  testWidgets('no photo → initials', (tester) async {
    await tester.pumpWidget(
        host(const ProductPhoto(photoUrl: null, placeholder: initials)));
    expect(find.text('SA'), findsOneWidget);
    expect(find.byType(Image), findsNothing);
  });

  testWidgets('photo taken on this device (local file) → shown from the file',
      (tester) async {
    await tester.pumpWidget(
        host(ProductPhoto(photoUrl: photo.path, placeholder: initials)));
    final image = tester.widget<Image>(find.byType(Image));
    expect((image.image as FileImage).file.path, photo.path);
    expect(find.text('SA'), findsNothing);
  });

  testWidgets('local file gone → initials', (tester) async {
    await tester.pumpWidget(host(const ProductPhoto(
        photoUrl: '/nowhere/gone.jpg', placeholder: initials)));
    expect(find.text('SA'), findsOneWidget);
  });

  testWidgets('server photo → its local copy (downloaded once, cached)',
      (tester) async {
    const url = '/api/v1/products/p1/photo?v=3';
    final cache = _FakeCache({url: photo});
    await tester.pumpWidget(host(
        const ProductPhoto(photoUrl: url, placeholder: initials),
        cache: cache));
    await tester.pumpAndSettle();

    expect(cache.requested, [url]);
    final image = tester.widget<Image>(find.byType(Image));
    expect((image.image as FileImage).file.path, photo.path);
  });

  testWidgets('server photo not available (offline) → initials',
      (tester) async {
    const url = '/api/v1/products/p1/photo?v=3';
    await tester.pumpWidget(host(
        const ProductPhoto(photoUrl: url, placeholder: initials),
        cache: _FakeCache({})));
    await tester.pumpAndSettle();

    expect(find.text('SA'), findsOneWidget);
    expect(find.byType(Image), findsNothing);
  });
}
