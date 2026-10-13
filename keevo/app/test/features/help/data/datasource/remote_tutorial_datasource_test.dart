import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/help/data/datasource/remote_tutorial_datasource.dart';

class MockDio extends Mock implements Dio {}

/// v1s-14-6 — FR7 tutorial catalog GET path and `data` envelope parsing.
void main() {
  test('fetchTutorials GETs /api/v1/help/tutorials and parses data', () async {
    final dio = MockDio();
    when(() => dio.get<Map<String, dynamic>>('/api/v1/help/tutorials'))
        .thenAnswer((_) async => Response(
              requestOptions: RequestOptions(path: '/api/v1/help/tutorials'),
              statusCode: 200,
              data: {
                'success': true,
                'data': [
                  {
                    'id': 'vendre',
                    'module': 'Vendre',
                    'title': 'Enregistrer une vente',
                    'description': 'Encaisser une vente.',
                    'durationSeconds': 120,
                    'url': 'https://youtu.be/abc',
                  },
                  {
                    'id': 'stock',
                    'module': 'Catalogue & stock',
                    'title': 'Gérer son stock',
                    'description': '',
                    'durationSeconds': 110,
                    'url': '',
                  },
                ],
              },
            ));

    final list = await RemoteTutorialDataSource(dio: dio).fetchTutorials();

    expect(list.map((t) => t.id), ['vendre', 'stock']);
    expect(list.first.module, 'Vendre');
    expect(list.first.description, 'Encaisser une vente.');
    expect(list.first.durationSeconds, 120);
    expect(list.first.isAvailable, isTrue);
    expect(list.last.isAvailable, isFalse);
  });
}
