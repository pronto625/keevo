import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/remote_device_sessions_datasource.dart';
import '../../domain/device_session.dart';

final deviceSessionsDataSourceProvider =
    Provider<RemoteDeviceSessionsDataSource>(
        (ref) => RemoteDeviceSessionsDataSource(ref.watch(dioProvider)));

/// My signed-in devices (current first). Online only: the list lives on the
/// server.
final myDeviceSessionsProvider =
    FutureProvider.autoDispose<List<DeviceSession>>((ref) async {
  final sessions = await ref.watch(deviceSessionsDataSourceProvider).mine();
  return [...sessions]..sort((a, b) => a.current == b.current
      ? 0
      : a.current
          ? -1
          : 1);
});

/// An employee's signed-in devices (owner).
final employeeDeviceSessionsProvider = FutureProvider.autoDispose
    .family<List<DeviceSession>, String>((ref, employeeId) {
  return ref.watch(deviceSessionsDataSourceProvider).ofEmployee(employeeId);
});
