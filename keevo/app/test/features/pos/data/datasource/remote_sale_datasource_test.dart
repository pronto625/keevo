import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/pos/data/datasource/remote_sale_datasource.dart';
import 'package:mocktail/mocktail.dart';

class MockDio extends Mock implements Dio {}

/// v1s-13-13 — the correction request must carry the correctionId the backend
/// derives the ADJUSTMENT movement ids from (one history row after the pull).
void main() {
  late MockDio dio;
  late RemoteSaleDataSource ds;

  setUp(() {
    dio = MockDio();
    ds = RemoteSaleDataSource(dio);
    when(() => dio.post<dynamic>(any(), data: any(named: 'data'))).thenAnswer(
        (_) async => Response<dynamic>(
            requestOptions: RequestOptions(path: '/x'), statusCode: 200));
  });

  Map<String, dynamic> postedBody() => verify(() => dio.post<dynamic>(
          '/api/v1/sales/sale-1/correct',
          data: captureAny(named: 'data')))
      .captured
      .single as Map<String, dynamic>;

  test('correctSale posts the correctionId when given', () async {
    await ds.correctSale('sale-1', 'Erreur de quantité', {'item-1': 2},
        correctionId: 'corr-1');

    expect(postedBody(), {
      'justification': 'Erreur de quantité',
      'itemQuantities': {'item-1': 2},
      'correctionId': 'corr-1',
    });
  });

  test('correctSale omits the correctionId key when null', () async {
    await ds.correctSale('sale-1', 'Erreur de quantité', {'item-1': 2});

    final body = postedBody();
    expect(body.containsKey('correctionId'), isFalse);
    expect(body, {
      'justification': 'Erreur de quantité',
      'itemQuantities': {'item-1': 2},
    });
  });
}
