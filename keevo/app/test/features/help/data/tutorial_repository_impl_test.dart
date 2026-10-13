import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/features/help/data/datasource/remote_tutorial_datasource.dart';
import 'package:keevo/features/help/data/repository/tutorial_repository_impl.dart';
import 'package:keevo/features/help/domain/model/tutorial.dart';

class _MockRemote extends Mock implements RemoteTutorialDataSource {}

void main() {
  late _MockRemote remote;

  const vendre = Tutorial(
    id: 'vendre',
    module: 'Vendre',
    title: 'Enregistrer une vente',
    durationSeconds: 120,
    url: 'https://youtu.be/x',
  );

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    remote = _MockRemote();
  });

  Future<TutorialRepositoryImpl> repo() async => TutorialRepositoryImpl(
        remote: remote,
        prefs: await SharedPreferences.getInstance(),
        now: () => DateTime(2026, 10, 13, 9),
      );

  test('online → server list, cached for later', () async {
    when(() => remote.fetchTutorials()).thenAnswer((_) async => [vendre]);
    final r = await repo();

    final first = await r.getTutorials();
    expect(first.fromCache, isFalse);
    expect(first.tutorials.single.id, 'vendre');

    when(() => remote.fetchTutorials()).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/x'),
        type: DioExceptionType.connectionError));
    final second = await r.getTutorials();
    expect(second.fromCache, isTrue);
    expect(second.fetchedAt, DateTime(2026, 10, 13, 9));
    expect(second.tutorials.single.url, 'https://youtu.be/x');
  });

  test('server failure without cache → error rethrown', () async {
    when(() => remote.fetchTutorials()).thenThrow(Exception('offline'));
    final r = await repo();

    await expectLater(r.getTutorials(), throwsA(isA<Exception>()));
  });

  test('isAvailable: only absolute http(s) links', () {
    expect(vendre.isAvailable, isTrue);
    expect(const Tutorial(id: 'a', module: 'm', title: 't').isAvailable, isFalse);
    expect(const Tutorial(id: 'a', module: 'm', title: 't', url: 'javascript:x')
        .isAvailable, isFalse);
    expect(const Tutorial(id: 'a', module: 'm', title: 't', url: 'youtube.com/x')
        .isAvailable, isFalse);
  });
}
