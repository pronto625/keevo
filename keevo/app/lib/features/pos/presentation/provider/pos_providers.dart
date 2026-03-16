import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_sale_datasource.dart';
import '../../data/datasource/remote_sale_datasource.dart';
import '../../data/repository/sale_repository_impl.dart';
import '../../domain/repository/sale_repository.dart';
import '../../domain/usecase/record_sale_usecase.dart';

/// LocalSaleDataSource DI
final localSaleDataSourceProvider = Provider<LocalSaleDataSource>((ref) {
  return LocalSaleDataSource(ref.watch(appDatabaseProvider));
});

/// RemoteSaleDataSource DI
final remoteSaleDataSourceProvider = Provider<RemoteSaleDataSource>((ref) {
  return RemoteSaleDataSource(ref.watch(dioProvider));
});

/// SaleRepository DI
final saleRepositoryProvider = Provider<SaleRepository>((ref) {
  return SaleRepositoryImpl(
    ref.watch(localSaleDataSourceProvider),
    ref.watch(remoteSaleDataSourceProvider),
    ref.watch(appDatabaseProvider),
  );
});

/// RecordSaleUseCase DI
final recordSaleUseCaseProvider = Provider<RecordSaleUseCase>((ref) {
  return RecordSaleUseCase(ref.watch(saleRepositoryProvider));
});

/// Active employee/user ID — read from FlutterSecureStorage.
final activeEmployeeIdProvider = FutureProvider<String?>((ref) async {
  return ref.watch(currentUserIdProvider.future);
});
