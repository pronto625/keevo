import 'package:connectivity_plus/connectivity_plus.dart';

import 'connectivity_service.dart';

/// ConnectivityServiceImpl — Production implementation using connectivity_plus.
class ConnectivityServiceImpl implements ConnectivityService {
  final Connectivity _connectivity;

  ConnectivityServiceImpl({Connectivity? connectivity})
      : _connectivity = connectivity ?? Connectivity();

  @override
  Future<bool> isOnline() async {
    final results = await _connectivity.checkConnectivity();
    return _hasConnection(results);
  }

  @override
  Stream<bool> get onlineStream => _connectivity.onConnectivityChanged
      .map((results) => _hasConnection(results));

  static bool _hasConnection(List<ConnectivityResult> results) => results.any(
        (r) =>
            r == ConnectivityResult.mobile ||
            r == ConnectivityResult.wifi ||
            r == ConnectivityResult.ethernet,
      );
}
