import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/settings/data/datasource/company_profile_api.dart';
import 'package:mocktail/mocktail.dart';

class MockDio extends Mock implements Dio {}

/// Story 14.2 — `/api/v1/account/company`: envelope unwrapping, designation,
/// multipart logo upload, logo download.
void main() {
  late MockDio dio;
  late CompanyProfileApi api;

  const path = '/api/v1/account/company';

  setUpAll(() {
    registerFallbackValue(Options());
  });

  setUp(() {
    dio = MockDio();
    api = CompanyProfileApi(dio);
  });

  Response<Map<String, dynamic>> ok(Map<String, dynamic> data) => Response(
        requestOptions: RequestOptions(path: path),
        statusCode: 200,
        data: {'success': true, 'data': data},
      );

  test('fetch unwraps data, incl. hasLogo and shopName', () async {
    when(() => dio.get<Map<String, dynamic>>(path)).thenAnswer((_) async => ok({
          'designation': 'Tech Centre SARL',
          'displayName': 'Tech Centre SARL',
          'shopName': 'Boutique Awa',
          'hasLogo': true,
        }));
    final p = await api.fetch();
    expect(p.designation, 'Tech Centre SARL');
    expect(p.displayName, 'Tech Centre SARL');
    expect(p.shopName, 'Boutique Awa');
    expect(p.hasLogo, isTrue);
  });

  test('blank designation / shopName → null; designation is sent trimmed',
      () async {
    Object? sent;
    when(() => dio.put<Map<String, dynamic>>(path, data: any(named: 'data')))
        .thenAnswer((inv) async {
      sent = inv.namedArguments[#data];
      return ok({
        'designation': '  ',
        'displayName': 'Mon commerce',
        'shopName': null,
        'hasLogo': false,
      });
    });
    final p = await api.updateDesignation('  Tech  ');
    expect(sent, {'designation': 'Tech'});
    expect(p.designation, isNull);
    expect(p.shopName, isNull);
    expect(p.hasLogo, isFalse);
  });

  test('uploadLogo sends a multipart part « file » with an image type',
      () async {
    Object? sent;
    when(() => dio.put<Map<String, dynamic>>('$path/logo',
        data: any(named: 'data'))).thenAnswer((inv) async {
      sent = inv.namedArguments[#data];
      return ok({'designation': null, 'displayName': 'Boutique Awa', 'hasLogo': true});
    });
    final p = await api.uploadLogo(Uint8List.fromList([1, 2, 3]), png: true);
    expect(p.hasLogo, isTrue);
    final form = sent as FormData;
    expect(form.files, hasLength(1));
    expect(form.files.single.key, 'file');
    expect(form.files.single.value.filename, 'logo.png');
    expect(form.files.single.value.contentType.toString(), 'image/png');

    await api.uploadLogo(Uint8List.fromList([1]), png: false);
    final jpeg = sent as FormData;
    expect(jpeg.files.single.value.contentType.toString(), 'image/jpeg');
  });

  test('fetchLogo: bytes; 404 → null; other errors rethrown', () async {
    when(() => dio.get<List<int>>('$path/logo', options: any(named: 'options')))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '$path/logo'),
              statusCode: 200,
              data: [7, 8],
            ));
    expect(await api.fetchLogo(), Uint8List.fromList([7, 8]));

    DioException error(int status) => DioException(
          requestOptions: RequestOptions(path: '$path/logo'),
          response: Response(
              requestOptions: RequestOptions(path: '$path/logo'),
              statusCode: status),
          type: DioExceptionType.badResponse,
        );
    when(() => dio.get<List<int>>('$path/logo', options: any(named: 'options')))
        .thenThrow(error(404));
    expect(await api.fetchLogo(), isNull);
    when(() => dio.get<List<int>>('$path/logo', options: any(named: 'options')))
        .thenThrow(error(503));
    expect(api.fetchLogo(), throwsA(isA<DioException>()));
  });

  test('deleteLogo unwraps the profile', () async {
    when(() => dio.delete<Map<String, dynamic>>('$path/logo')).thenAnswer(
        (_) async => ok({'displayName': 'Boutique Awa', 'hasLogo': false}));
    final p = await api.deleteLogo();
    expect(p.hasLogo, isFalse);
    expect(p.designation, isNull);
  });
}
