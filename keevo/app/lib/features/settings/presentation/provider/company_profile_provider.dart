import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/company_profile_api.dart';

/// Company identity API (Story 14.2) — overridden in tests.
final companyProfileApiProvider = Provider<CompanyProfileApi>(
  (ref) => CompanyProfileApi(ref.watch(dioProvider)),
);
