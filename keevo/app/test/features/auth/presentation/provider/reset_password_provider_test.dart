import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/domain/exception/auth_exception.dart';
import 'package:keevo/features/auth/domain/repository/auth_repository.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAuthRepository extends Mock implements AuthRepository {}

/// v1s-15-8 (deferred from CR v1s-16-8): regression test for the
/// `FutureOr<void> build()` type fix of [ResetPassword].
void main() {
  late _MockAuthRepository repo;
  late ProviderContainer container;

  Future<void> reset() => container.read(resetPasswordProvider.notifier).reset(
        phoneNumber: '+237600000001',
        code: '123456',
        newPassword: 'secret123',
      );

  setUp(() {
    repo = _MockAuthRepository();
    container = ProviderContainer(overrides: [
      authRepositoryProvider.overrideWithValue(repo),
    ]);
    addTearDown(container.dispose);
    // autoDispose: keep it alive for the whole test.
    final sub = container.listen(resetPasswordProvider, (_, __) {});
    addTearDown(sub.close);
  });

  test('build() starts idle as AsyncData(null)', () {
    final state = container.read(resetPasswordProvider);
    expect(state, isA<AsyncData<void>>());
    expect(state.isLoading, isFalse);
    expect(state.hasError, isFalse);
  });

  test('success: AsyncData, repository called with the exact values', () async {
    when(() => repo.resetPassword(
          phoneNumber: any(named: 'phoneNumber'),
          code: any(named: 'code'),
          newPassword: any(named: 'newPassword'),
        )).thenAnswer((_) async {});

    await reset();

    final state = container.read(resetPasswordProvider);
    expect(state, isA<AsyncData<void>>());
    verify(() => repo.resetPassword(
          phoneNumber: '+237600000001',
          code: '123456',
          newPassword: 'secret123',
        )).called(1);
  });

  test('failure: AsyncError carrying the AuthException, reset() never throws',
      () async {
    when(() => repo.resetPassword(
          phoneNumber: any(named: 'phoneNumber'),
          code: any(named: 'code'),
          newPassword: any(named: 'newPassword'),
        )).thenThrow(const AuthException(
            domainCode: 'INVALID_OR_EXPIRED_CODE', message: 'bad code'));

    await reset();

    final state = container.read(resetPasswordProvider);
    expect(state.hasError, isTrue);
    expect(state.error, isA<AuthException>().having(
        (e) => e.domainCode, 'domainCode', 'INVALID_OR_EXPIRED_CODE'));
  });
}
