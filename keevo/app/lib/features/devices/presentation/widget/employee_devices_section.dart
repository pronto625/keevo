import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/widget/app_error_widget.dart';

import '../provider/device_sessions_provider.dart';
import 'device_session_tile.dart';

/// Story 16.11 — « Appareils » of an employee (owner, employee detail): the
/// devices the employee is signed in on, with « Déconnecter » (lost phone,
/// employee gone). The closed device must sign in again at its next request.
/// Connectivity failure (no network, timeout) — anything else is a server answer.
bool _isOffline(Object e) =>
    e is DioException &&
    (e.type == DioExceptionType.connectionError ||
        e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.receiveTimeout ||
        e.type == DioExceptionType.sendTimeout);

class EmployeeDevicesSection extends ConsumerWidget {
  final String employeeId;

  const EmployeeDevicesSection({super.key, required this.employeeId});

  Future<void> _close(BuildContext context, WidgetRef ref, String sessionId,
      String label) async {
    if (!await confirmDisconnect(context, label: label)) return;
    try {
      await ref
          .read(deviceSessionsDataSourceProvider)
          .closeOfEmployee(employeeId, sessionId);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Appareil déconnecté')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(appErrorMessage(e))));
    }
    ref.invalidate(employeeDeviceSessionsProvider(employeeId));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final sessions = ref.watch(employeeDeviceSessionsProvider(employeeId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Appareils',
          style: theme.textTheme.titleSmall?.copyWith(
            fontWeight: FontWeight.w700,
            color: cs.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 8),
        Container(
          decoration: BoxDecoration(
            color: cs.surface,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: cs.outlineVariant.withAlpha(90)),
          ),
          child: sessions.when(
            loading: () => const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => ListTile(
              leading: Icon(_isOffline(e)
                  ? Icons.cloud_off_rounded
                  : Icons.error_outline_rounded),
              title: Text(_isOffline(e)
                  ? 'Liste indisponible hors connexion'
                  : appErrorMessage(e)),
              trailing: TextButton(
                onPressed: () =>
                    ref.invalidate(employeeDeviceSessionsProvider(employeeId)),
                child: const Text('Réessayer'),
              ),
            ),
            data: (list) => list.isEmpty
                ? const ListTile(
                    leading: Icon(Icons.devices_other_rounded),
                    title: Text('Aucun appareil connecté'),
                  )
                : Column(
                    children: [
                      for (final s in list)
                        DeviceSessionTile(
                          key: ValueKey(s.sessionId),
                          session: s,
                          onDisconnect: () =>
                              _close(context, ref, s.sessionId, s.label),
                        ),
                    ],
                  ),
          ),
        ),
      ],
    );
  }
}
