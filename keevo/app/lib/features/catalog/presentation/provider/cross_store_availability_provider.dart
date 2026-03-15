import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../domain/model/cross_store_availability_model.dart';
import 'stock_provider.dart';

part 'cross_store_availability_provider.g.dart';

/// Fetches cross-store availability for [productId] (Story 3.4).
///
/// Online-first: the repository handles the 3 s timeout + local fallback.
@riverpod
Future<CrossStoreAvailabilityModel> crossStoreAvailability(
  CrossStoreAvailabilityRef ref,
  String productId,
) {
  return ref.watch(stockRepositoryProvider).getCrossStoreAvailability(productId);
}
