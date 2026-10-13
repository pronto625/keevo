import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/model/employee_activity.dart';
import '../../domain/model/employee_model.dart';
import '../provider/employee_provider.dart';
import '../util/employee_whatsapp.dart';

/// Shows the FR67 activity sheet of [employee] (v1s-14-6, OWNER only):
/// last login, last activity, sales today / this week, session state and —
/// when the employee is idle for 48 h+ — a WhatsApp reminder.
Future<void> showEmployeeActivitySheet({
  required BuildContext context,
  required EmployeeModel employee,
  required String storeName,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => EmployeeActivitySheet(employee: employee, storeName: storeName),
  );
}

class EmployeeActivitySheet extends ConsumerWidget {
  final EmployeeModel employee;
  final String storeName;

  const EmployeeActivitySheet({
    super.key,
    required this.employee,
    required this.storeName,
  });

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final activityAsync = ref.watch(employeeActivityProvider(employee.id));

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: theme.colorScheme.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'Activité de ${employee.firstName} ${employee.lastName}',
              style: theme.textTheme.titleLarge
                  ?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 16),
            activityAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 32),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (err, _) => AppErrorWidget(
                error: err,
                title: 'Activité indisponible',
                onRetry: () =>
                    ref.invalidate(employeeActivityProvider(employee.id)),
              ),
              data: (a) => _ActivityContent(
                activity: a,
                employee: employee,
                storeName: storeName,
                currencyFmt: _currencyFmt,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ActivityContent extends StatelessWidget {
  final EmployeeActivity activity;
  final EmployeeModel employee;
  final String storeName;
  final NumberFormat currencyFmt;

  const _ActivityContent({
    required this.activity,
    required this.employee,
    required this.storeName,
    required this.currencyFmt,
  });

  String _sales(int count, int amount) =>
      '$count vente${count > 1 ? 's' : ''} · ${currencyFmt.format(amount)}';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final now = DateTime.now();
    final phone = employee.phoneNumber?.trim() ?? '';
    final showReminder = activity.inactive && phone.isNotEmpty;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (activity.inactive) ...[
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: cs.warningStrong.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
            ),
            child: Row(
              children: [
                Icon(Icons.schedule_rounded, color: cs.warningStrong, size: 20),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Sans activité depuis plus de 48 h',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: cs.warningStrong,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
        _StatRow(
          icon: Icons.login_rounded,
          label: 'Dernière connexion',
          value: activity.lastLoginAt == null
              ? 'Aucune connexion'
              : formatLastSeen(activity.lastLoginAt, now),
        ),
        _StatRow(
          icon: Icons.history_rounded,
          label: 'Dernière activité',
          value: formatLastSeen(activity.lastActivityAt, now),
        ),
        _StatRow(
          icon: Icons.today_rounded,
          label: 'Aujourd\'hui',
          value: _sales(activity.todaySalesCount, activity.todaySalesAmount),
        ),
        _StatRow(
          icon: Icons.date_range_rounded,
          label: 'Cette semaine',
          value: _sales(activity.weekSalesCount, activity.weekSalesAmount),
        ),
        _StatRow(
          icon: Icons.phone_android_rounded,
          label: 'Session',
          value: activity.activeSession ? 'Connecté sur un appareil' : 'Aucune session active',
        ),
        if (showReminder) ...[
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => openWhatsApp(
              ScaffoldMessenger.of(context),
              whatsAppUri(
                phone,
                buildReminderMessage(
                    firstName: employee.firstName, storeName: storeName),
              ),
            ),
            icon: const Icon(Icons.chat_outlined, size: 18),
            label: const Text('Relancer via WhatsApp'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
            ),
          ),
        ],
      ],
    );
  }
}

class _StatRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _StatRow({required this.icon, required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          SoftIconBadge(icon: icon, size: 36),
          const SizedBox(width: 12),
          Expanded(
            child: Text(label,
                style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: cs.onSurface,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
