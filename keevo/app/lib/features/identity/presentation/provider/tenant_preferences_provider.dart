import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/services/api_service.dart';
import '../../data/repository/tenant_preferences_repository_impl.dart';
import '../../domain/model/tenant_preferences_model.dart';
import '../../domain/repository/tenant_preferences_repository.dart';
import '../../../auth/presentation/provider/auth_provider.dart';

/// Provider for TenantPreferencesRepository with authenticated API service
final tenantPreferencesRepositoryProvider = Provider<TenantPreferencesRepository>((ref) {
  // Use authenticated API service from auth module when available
  ApiService apiService;
  try {
    final dio = ref.read(dioProvider);
    apiService = DioApiService(dio: dio);
  } catch (e) {
    // Fallback to default if not authenticated
    apiService = StubApiService();
  }
  return TenantPreferencesRepositoryImpl(apiService);
});

/// Provider for current tenant preferences
final tenantPreferencesProvider = FutureProvider<TenantPreferencesModel?>((ref) async {
  final repository = ref.watch(tenantPreferencesRepositoryProvider);
  return await repository.getCurrentTenantPreferences();
});