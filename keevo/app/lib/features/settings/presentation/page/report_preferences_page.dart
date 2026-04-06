import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../identity/presentation/provider/report_preferences_provider.dart';

/// ReportPreferencesPage — Paramètres › Rapports & WhatsApp
///
/// Story 7.5 — Permet au OWNER de configurer:
///   - Rapport fin de journée (activé, heure, canal)
///   - Rapport hebdomadaire (activé, jour, heure, canal)
///   - Rapport inventaire (activé, canal)
///   - Canal alertes stock
/// + bouton test rapport WhatsApp.
class ReportPreferencesPage extends ConsumerStatefulWidget {
  const ReportPreferencesPage({super.key});

  @override
  ConsumerState<ReportPreferencesPage> createState() =>
      _ReportPreferencesPageState();
}

class _ReportPreferencesPageState
    extends ConsumerState<ReportPreferencesPage> {
  @override
  void initState() {
    super.initState();
    // Load current preferences on open
    Future.microtask(
        () => ref.read(reportPreferencesProvider.notifier).load());
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(reportPreferencesProvider);
    final notifier = ref.read(reportPreferencesProvider.notifier);

    // Show feedback snackbars
    ref.listen<ReportPreferencesState>(
        reportPreferencesProvider, (ReportPreferencesState? prev, ReportPreferencesState next) {
      if (next.successMessage != null &&
          next.successMessage != prev?.successMessage) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.successMessage!),
            backgroundColor: AppTheme.success,
            behavior: SnackBarBehavior.floating,
            duration: const Duration(seconds: 3),
          ),
        );
        notifier.clearMessages();
      }
      if (next.error != null && next.error != prev?.error) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.error ?? ''),
            backgroundColor: AppTheme.secondary,
            behavior: SnackBarBehavior.floating,
          ),
        );
        notifier.clearMessages();
      }
    });

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      body: CustomScrollView(
        slivers: [
          // ── Header ───────────────────────────────────────────────────────
          SliverAppBar(
            expandedHeight: 110,
            pinned: true,
            elevation: 0,
            backgroundColor: Colors.transparent,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [
                      AppTheme.primary,
                      AppTheme.primaryGradientEnd,
                    ],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
              ),
              title: const Text(
                'Rapports & WhatsApp',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                ),
              ),
              titlePadding:
                  const EdgeInsets.only(left: 56, bottom: 16),
            ),
            leading: IconButton(
              icon: const Icon(Icons.arrow_back_ios_new_rounded,
                  color: Colors.white),
              onPressed: () => Navigator.pop(context),
            ),
          ),

          if (state.isLoading)
            const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            )
          else if (state.preferences == null)
            const SliverFillRemaining(
              child: Center(
                child: Text(
                  'Impossible de charger les préférences',
                  style: TextStyle(color: Color(0xFF868E96)),
                ),
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  // ── Section 1 : Rapport Fin de Journée ─────────────────
                  _SectionHeader(
                    icon: Icons.nightlight_round,
                    iconColor: AppTheme.primary,
                    title: 'Rapport Fin de Journée',
                  ),
                  const SizedBox(height: 8),
                  _PrefsCard(children: [
                    _SwitchTile(
                      title: 'Activer le rapport EOD',
                      subtitle: 'Reçu automatiquement à la clôture',
                      value: state.preferences!.eodReportEnabled,
                      onChanged: (v) => notifier.updateField(
                          (p) => p.copyWith(eodReportEnabled: v)),
                    ),
                    if (state.preferences!.eodReportEnabled) ...[
                      const _Divider(),
                      _TimePickerTile(
                        title: 'Heure de clôture',
                        value: state.preferences!.eodReportTime,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(eodReportTime: v)),
                      ),
                      const _Divider(),
                      _ChannelDropdown(
                        title: 'Canal de livraison',
                        value: state.preferences!.eodReportChannel,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(eodReportChannel: v)),
                      ),
                    ],
                  ]),

                  const SizedBox(height: 20),

                  // ── Section 2 : Rapport Hebdomadaire ───────────────────
                  _SectionHeader(
                    icon: Icons.calendar_month_rounded,
                    iconColor: const Color(0xFF20C997),
                    title: 'Rapport Hebdomadaire',
                  ),
                  const SizedBox(height: 8),
                  _PrefsCard(children: [
                    _SwitchTile(
                      title: 'Activer le rapport hebdo',
                      subtitle: 'Récapitulatif de la semaine',
                      value: state.preferences!.weeklyReportEnabled,
                      onChanged: (v) => notifier.updateField(
                          (p) => p.copyWith(weeklyReportEnabled: v)),
                    ),
                    if (state.preferences!.weeklyReportEnabled) ...[
                      const _Divider(),
                      _DayPicker(
                        value: state.preferences!.weeklyReportDay,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(weeklyReportDay: v)),
                      ),
                      const _Divider(),
                      _TimePickerTile(
                        title: 'Heure d\'envoi',
                        value: state.preferences!.weeklyReportTime,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(weeklyReportTime: v)),
                      ),
                      const _Divider(),
                      _ChannelDropdown(
                        title: 'Canal de livraison',
                        value: state.preferences!.weeklyReportChannel,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(weeklyReportChannel: v)),
                      ),
                    ],
                  ]),

                  if (state.preferences!.weeklyReportEnabled) ...[
                    const SizedBox(height: 8),
                    SizedBox(
                      width: double.infinity,
                      height: 48,
                      child: OutlinedButton.icon(
                        onPressed: state.weeklyPreviewing
                            ? null
                            : () async {
                                final reportId =
                                    await notifier.previewWeeklyReport();
                                if (reportId != null && mounted) {
                                  context.push('/reports/history/$reportId');
                                }
                              },
                        icon: state.weeklyPreviewing
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2),
                              )
                            : const Icon(Icons.preview_rounded),
                        label: Text(state.weeklyPreviewing
                            ? 'Génération...'
                            : 'Aperçu du rapport hebdomadaire'),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFF20C997),
                          side: const BorderSide(color: Color(0xFF20C997)),
                          shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14)),
                          textStyle: const TextStyle(
                              fontSize: 14, fontWeight: FontWeight.w600),
                        ),
                      ),
                    ),
                  ],

                  const SizedBox(height: 20),

                  // ── Section 3 : Rapport Inventaire ─────────────────────
                  _SectionHeader(
                    icon: Icons.inventory_2_rounded,
                    iconColor: const Color(0xFFF59F00),
                    title: 'Rapport Inventaire',
                  ),
                  const SizedBox(height: 8),
                  _PrefsCard(children: [
                    _SwitchTile(
                      title: 'Activer le rapport inventaire',
                      subtitle: 'Envoyé après chaque inventaire',
                      value: state.preferences!.inventoryReportEnabled,
                      onChanged: (v) => notifier.updateField(
                          (p) => p.copyWith(inventoryReportEnabled: v)),
                    ),
                    if (state.preferences!.inventoryReportEnabled) ...[
                      const _Divider(),
                      _ChannelDropdown(
                        title: 'Canal de livraison',
                        value: state.preferences!.inventoryReportChannel,
                        onChanged: (v) => notifier.updateField(
                            (p) => p.copyWith(inventoryReportChannel: v)),
                      ),
                    ],
                  ]),

                  const SizedBox(height: 20),

                  // ── Section 4 : Alertes Stock ───────────────────────────
                  _SectionHeader(
                    icon: Icons.notifications_active_rounded,
                    iconColor: AppTheme.secondary,
                    title: 'Alertes Stock Bas',
                  ),
                  const SizedBox(height: 8),
                  _PrefsCard(children: [
                    _StockAlertChannelDropdown(
                      value: state.preferences!.stockAlertChannel,
                      onChanged: (v) => notifier.updateField(
                          (p) => p.copyWith(stockAlertChannel: v)),
                    ),
                  ]),

                  const SizedBox(height: 20),

                  // ── Section 5 : Tendances de Ventes ─────────────────────
                  _SectionHeader(
                    icon: Icons.trending_up_rounded,
                    iconColor: const Color(0xFF20C997),
                    title: 'Tendances de Ventes',
                  ),
                  const SizedBox(height: 8),
                  _PrefsCard(children: [
                    _SwitchTile(
                      title: 'Activer les alertes de tendance',
                      subtitle: 'Notifié quand les ventes varient fortement',
                      value: state.preferences!.trendNotificationEnabled,
                      onChanged: (v) => notifier.updateField(
                          (p) => p.copyWith(trendNotificationEnabled: v)),
                    ),
                  ]),

                  const SizedBox(height: 28),

                  // ── Save button ─────────────────────────────────────────
                  SizedBox(
                    width: double.infinity,
                    height: 52,
                    child: ElevatedButton.icon(
                      onPressed: state.isSaving ? null : () => notifier.save(),
                      icon: state.isSaving
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white),
                            )
                          : const Icon(Icons.save_rounded),
                      label: Text(state.isSaving
                          ? 'Enregistrement...'
                          : 'Enregistrer les préférences'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppTheme.primary,
                        foregroundColor: Colors.white,
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14)),
                        textStyle: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600),
                      ),
                    ),
                  ),

                  const SizedBox(height: 16),

                  // ── Test report button ──────────────────────────────────
                  SizedBox(
                    width: double.infinity,
                    height: 52,
                    child: OutlinedButton.icon(
                      onPressed: state.testSending
                          ? null
                          : () => notifier.sendTestReport(),
                      icon: state.testSending
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.send_rounded),
                      label: Text(state.testSending
                          ? 'Envoi en cours...'
                          : 'Envoyer un rapport test'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppTheme.primary,
                        side: const BorderSide(color: AppTheme.primary),
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14)),
                        textStyle: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600),
                      ),
                    ),
                  ),
                ]),
              ),
            ),
        ],
      ),
    );
  }
}

// ── Reusable widgets ─────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String title;

  const _SectionHeader(
      {required this.icon, required this.iconColor, required this.title});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 18, color: iconColor),
        const SizedBox(width: 8),
        Text(
          title,
          style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: iconColor,
              letterSpacing: 0.4),
        ),
      ],
    );
  }
}

class _PrefsCard extends StatelessWidget {
  final List<Widget> children;

  const _PrefsCard({required this.children});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
              color: Colors.black.withAlpha(13),
              blurRadius: 8,
              offset: const Offset(0, 2)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: children,
      ),
    );
  }
}

class _Divider extends StatelessWidget {
  const _Divider();

  @override
  Widget build(BuildContext context) => const Divider(
        height: 1,
        thickness: 1,
        indent: 16,
        endIndent: 16,
        color: Color(0xFFF1F3F5),
      );
}

class _SwitchTile extends StatelessWidget {
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  const _SwitchTile(
      {required this.title,
      required this.subtitle,
      required this.value,
      required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title:
            Text(title, style: const TextStyle(fontWeight: FontWeight.w500)),
        subtitle: Text(subtitle,
            style: const TextStyle(fontSize: 12, color: Color(0xFF868E96))),
        value: value,
        onChanged: onChanged,
        activeColor: AppTheme.primary,
      ),
    );
  }
}

class _TimePickerTile extends StatelessWidget {
  final String title;
  final String value;
  final ValueChanged<String> onChanged;

  const _TimePickerTile(
      {required this.title, required this.value, required this.onChanged});

  String _format(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:00';

  TimeOfDay _parse(String s) {
    final parts = s.split(':');
    return TimeOfDay(
        hour: int.tryParse(parts[0]) ?? 20,
        minute: int.tryParse(parts[1]) ?? 0);
  }

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      title: Text(title,
          style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 14)),
      trailing: Text(
        value.substring(0, 5), // HH:mm display only
        style: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w600,
            color: AppTheme.primary),
      ),
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: _parse(value),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true),
            child: child!,
          ),
        );
        if (picked != null) onChanged(_format(picked));
      },
    );
  }
}

class _DayPicker extends StatelessWidget {
  final int value; // 0=Lun, 6=Dim
  final ValueChanged<int> onChanged;

  static const _days = [
    'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche'
  ];

  const _DayPicker({required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      title: const Text('Jour d\'envoi',
          style: TextStyle(fontWeight: FontWeight.w500, fontSize: 14)),
      trailing: DropdownButton<int>(
        value: value,
        underline: const SizedBox.shrink(),
        items: List.generate(
          7,
          (i) => DropdownMenuItem(value: i, child: Text(_days[i])),
        ),
        onChanged: (v) => v != null ? onChanged(v) : null,
        style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: AppTheme.primary),
      ),
    );
  }
}

class _ChannelDropdown extends StatelessWidget {
  final String title;
  final String value;
  final ValueChanged<String> onChanged;

  const _ChannelDropdown(
      {required this.title, required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      title: Text(title,
          style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 14)),
      trailing: DropdownButton<String>(
        value: value,
        underline: const SizedBox.shrink(),
        items: const [
          DropdownMenuItem(value: 'WHATSAPP', child: Text('WhatsApp')),
          DropdownMenuItem(value: 'IN_APP_ONLY', child: Text('In-App uniquement')),
        ],
        onChanged: (v) => v != null ? onChanged(v) : null,
        style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: AppTheme.primary),
      ),
    );
  }
}

class _StockAlertChannelDropdown extends StatelessWidget {
  final String value;
  final ValueChanged<String> onChanged;

  const _StockAlertChannelDropdown(
      {required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      title: const Text('Canal des alertes',
          style: TextStyle(fontWeight: FontWeight.w500, fontSize: 14)),
      subtitle: const Text('Comment recevoir les alertes de stock bas',
          style: TextStyle(fontSize: 12, color: Color(0xFF868E96))),
      trailing: DropdownButton<String>(
        value: value,
        underline: const SizedBox.shrink(),
        items: const [
          DropdownMenuItem(value: 'PUSH', child: Text('Notification')),
          DropdownMenuItem(value: 'WHATSAPP', child: Text('WhatsApp')),
          DropdownMenuItem(value: 'BOTH', child: Text('Les deux')),
        ],
        onChanged: (v) => v != null ? onChanged(v) : null,
        style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: AppTheme.primary),
      ),
    );
  }
}
