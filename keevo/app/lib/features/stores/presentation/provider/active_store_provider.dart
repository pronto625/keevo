import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';

/// Persists the ID of the store chosen as the active context in Settings.
///
/// `null` means "all stores" — no filter is applied in the catalog or stock
/// views. The value is backed by [SharedPreferences] so it survives restarts.
class ActiveStoreNotifier extends Notifier<String?> {
  @override
  String? build() {
    final prefs = ref.watch(sharedPreferencesProvider);
    return prefs.getString(kActiveStoreIdKey);
  }

  void setActiveStore(String? storeId) {
    final prefs = ref.read(sharedPreferencesProvider);
    if (storeId == null) {
      prefs.remove(kActiveStoreIdKey);
    } else {
      prefs.setString(kActiveStoreIdKey, storeId);
    }
    state = storeId;
  }
}

final activeStoreIdProvider = NotifierProvider<ActiveStoreNotifier, String?>(
  ActiveStoreNotifier.new,
);
