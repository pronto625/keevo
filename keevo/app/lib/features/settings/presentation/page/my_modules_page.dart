import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:keevo_core/di/core_providers.dart'
    show currentUserPhoneProvider;
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/utils/support_contact.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';

import '../../../../core/entitlements/entitlements_provider.dart';
import '../../../dashboard/presentation/provider/dashboard_providers.dart'
    show currentTenantNameProvider;
import '../../../team/presentation/util/employee_whatsapp.dart'
    show openWhatsApp;

/// Story 15.6 — Paramètres › Mes modules (owner only, `/settings/modules`).
///
/// Lists the tenant's modules from [entitlementsProvider] (states, French
/// names, subscription status and dates sent by
/// `GET /api/v1/tenants/me/modules`) in groups: « Inclus » (free offer),
/// « Mes modules payants » (subscribed now or before), « Disponibles »
/// (never subscribed) and « À confirmer » (no details known yet: older
/// snapshot read offline, or a state this version does not know). V1 has no self-service activation: the action asks
/// Keevo support on WhatsApp, which activates the module after a Mobile Money
/// payment. Offline, the last known states are shown with a « Hors ligne »
/// note. Never shows a module id, a code or an enum name.
class MyModulesPage extends ConsumerStatefulWidget {
  const MyModulesPage({super.key});

  static const title = 'Mes modules';
  static const includedSection = 'Inclus';
  static const paidSection = 'Mes modules payants';
  static const availableSection = 'Disponibles';
  static const unconfirmedSection = 'À confirmer';
  static const unconfirmedStatus = 'Informations à confirmer';
  static const errorTitle = 'Impossible de charger vos modules';
  static const requestActivationLabel = 'Demander l\'activation';
  static const reactivateLabel = 'Contactez le support pour le réactiver';
  static const offlineFallback = 'Hors ligne — derniers états connus';
  static const emptyMessage =
      'Impossible de charger vos modules. Vérifiez votre connexion puis '
      'tirez vers le bas pour réessayer.';
  static const intro =
      'L\'activation d\'un module se fait avec l\'équipe Keevo, après '
      'paiement par Mobile Money.';

  @override
  ConsumerState<MyModulesPage> createState() => _MyModulesPageState();
}

class _MyModulesPageState extends ConsumerState<MyModulesPage> {
  /// The server could not confirm the states shown (offline / error).
  bool _offline = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _confirm());
  }

  /// Asks the server once when the states shown are not confirmed in this
  /// session (no extra request otherwise).
  Future<void> _confirm() async {
    if (!mounted) return;
    bool offline;
    try {
      final e =
          await ref.read(entitlementsProvider.notifier).ensureServerState();
      offline = e.status != EntitlementsStatus.server;
    } catch (_) {
      offline = true;
    }
    if (mounted) setState(() => _offline = offline);
  }

  /// Pull-to-refresh: `refresh()` keeps the known states on failure, so a
  /// failure is seen as an answer that did not change ([Entitlements.fetchedAt]
  /// unchanged) or states still unconfirmed.
  Future<void> _refresh() async {
    final before = ref.read(entitlementsProvider).value?.fetchedAt;
    await ref.read(entitlementsProvider.notifier).refresh();
    if (!mounted) return;
    final after = ref.read(entitlementsProvider).value;
    setState(() => _offline = refreshFailed(before, after));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(entitlementsProvider);
    final entitlements = async.value;
    final business = ref.watch(currentTenantNameProvider);
    final phone = ref.watch(currentUserPhoneProvider);

    final Widget body;
    if (entitlements == null && async.hasError) {
      body = AppErrorWidget(
        error: async.error!,
        title: MyModulesPage.errorTitle,
        onRetry: () => ref.invalidate(entitlementsProvider),
      );
    } else if (entitlements == null) {
      body = const Center(child: CircularProgressIndicator());
    } else if (!_offline &&
        entitlements.status != EntitlementsStatus.server &&
        groupModules(entitlements).isEmpty) {
      // Claim seed without names / nothing known: the server is being asked.
      body = const Center(
          child: CircularProgressIndicator(key: Key('my-modules-loading')));
    } else {
      final groups = groupModules(entitlements);
      final showOffline = _offline;
      body = RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
          children: [
            if (showOffline) ...[
              _OfflineNote(text: offlineNote(entitlements.fetchedAt)),
              const SizedBox(height: 12),
            ],
            if (groups.isEmpty) ...[
              const SizedBox(height: 48),
              Text(
                MyModulesPage.emptyMessage,
                key: const Key('my-modules-empty'),
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.muted),
              ),
            ] else ...[
              Text(
                MyModulesPage.intro,
                style: TextStyle(fontSize: 13, color: cs.muted),
              ),
              const SizedBox(height: 16),
              for (final section in ModuleSection.values)
                if (groups.of(section).isNotEmpty) ...[
                  _SectionLabel(section.label),
                  const SizedBox(height: 8),
                  for (final m in groups.of(section)) ...[
                    _ModuleCard(
                      module: m,
                      onRequest: m.action == null
                          ? null
                          : () => _contactSupport(m, business, phone),
                    ),
                    const SizedBox(height: 10),
                  ],
                  const SizedBox(height: 14),
                ],
            ],
          ],
        ),
      );
    }

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      appBar: AppBar(
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () =>
              context.canPop() ? context.pop() : context.go('/settings'),
        ),
        title: Text(
          MyModulesPage.title,
          style: TextStyle(
              color: cs.onSurface, fontSize: 18, fontWeight: FontWeight.w700),
        ),
      ),
      body: body,
    );
  }

  Future<void> _contactSupport(
      ModuleRow module, String? business, String? phone) {
    final text = supportMessage(
      moduleName: module.name,
      businessName: business,
      ownerPhone: phone,
      reactivate: module.action == ModuleAction.reactivate,
    );
    return openWhatsApp(
        ScaffoldMessenger.of(context), supportWhatsAppUri(text));
  }
}

// ── Model of the screen (pure, tested) ──────────────────────────────────────

/// The groups of the screen, in display order.
enum ModuleSection {
  included(MyModulesPage.includedSection),
  paid(MyModulesPage.paidSection),
  available(MyModulesPage.availableSection),
  unconfirmed(MyModulesPage.unconfirmedSection);

  final String label;
  const ModuleSection(this.label);
}

/// What the owner can ask support for.
enum ModuleAction {
  /// Paid module not active: « Demander l'activation ».
  requestActivation,

  /// Free module switched off or read-only: « Contactez le support pour le
  /// réactiver ».
  reactivate,
}

/// One line of the screen: French name, French status, optional action.
@immutable
class ModuleRow {
  final String name;
  final String status;
  final ModuleSection section;
  final ModuleAction? action;

  /// Active (ENABLED) or readable during the grace: drawn as « on ».
  final bool active;

  const ModuleRow({
    required this.name,
    required this.status,
    required this.section,
    required this.active,
    this.action,
  });
}

/// The rows grouped by [ModuleSection], each group sorted by name.
class ModuleGroups {
  final Map<ModuleSection, List<ModuleRow>> _rows;
  const ModuleGroups(this._rows);

  List<ModuleRow> of(ModuleSection s) => _rows[s] ?? const [];
  bool get isEmpty => _rows.values.every((l) => l.isEmpty);
}

/// Africa/Douala (WAT, UTC+1, no DST) `dd MMM yyyy` in French.
String formatModuleDate(DateTime at) => DateFormat('dd MMM yyyy', 'fr_FR')
    .format(at.toUtc().add(const Duration(hours: 1)));

/// Whether a pull-to-refresh failed: no states, states still unconfirmed, or
/// the same answer as before it ([before] = its `fetchedAt`).
bool refreshFailed(DateTime? before, Entitlements? after) =>
    after == null ||
    after.status != EntitlementsStatus.server ||
    (before != null && after.fetchedAt == before);

/// « Hors ligne » note naming the date of the states shown.
String offlineNote(DateTime? fetchedAt) => fetchedAt == null
    ? MyModulesPage.offlineFallback
    : 'Hors ligne — états connus au ${formatModuleDate(fetchedAt)}';

/// Builds the screen rows from [e]. A module without a French name is left
/// out (never shown by id).
ModuleGroups groupModules(Entitlements e) {
  final rows = <ModuleSection, List<ModuleRow>>{};
  for (final id in e.states.keys) {
    final name = e.displayNameOf(id);
    if (name == null) continue;
    final row = moduleRow(name, e.states[id]!, e.detailsOf(id));
    (rows[row.section] ??= []).add(row);
  }
  for (final list in rows.values) {
    list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
  }
  return ModuleGroups(rows);
}

/// French status, group and action of one module ([state] = `ENABLED` |
/// `READ_ONLY` | `DISABLED`; [details] null for an older snapshot). Missing
/// details or an unknown state give a neutral « à confirmer » row, no action
/// (never a guessed group).
ModuleRow moduleRow(String name, String state, ModuleDetails? details) {
  const known = {kModuleEnabled, kModuleReadOnly, kModuleDisabled};
  if (details == null || !known.contains(state)) {
    return ModuleRow(
      name: name,
      status: MyModulesPage.unconfirmedStatus,
      section: ModuleSection.unconfirmed,
      active: false,
    );
  }
  final d = details;
  String until(DateTime? at) =>
      at == null ? '' : ' jusqu\'au ${formatModuleDate(at)}';

  if (d.isIncluded) {
    return switch (state) {
      kModuleEnabled => ModuleRow(
          name: name,
          status: 'Inclus',
          section: ModuleSection.included,
          active: true,
        ),
      kModuleReadOnly => ModuleRow(
          name: name,
          status: 'Lecture seule',
          section: ModuleSection.included,
          active: true,
          action: ModuleAction.reactivate,
        ),
      _ => ModuleRow(
          name: name,
          status: 'Non activé',
          section: ModuleSection.included,
          active: false,
          action: ModuleAction.reactivate,
        ),
    };
  }
  if (state == kModuleReadOnly) {
    return ModuleRow(
      name: name,
      status: 'Expiré — lecture seule${until(d.graceUntil)}',
      section: ModuleSection.paid,
      active: true,
      action: ModuleAction.requestActivation,
    );
  }
  if (state == kModuleEnabled) {
    final trial = d.status == kSubscriptionTrial;
    return ModuleRow(
      name: name,
      status: '${trial ? 'Essai' : 'Actif'}${until(d.expiresAt)}',
      section: ModuleSection.paid,
      active: true,
    );
  }
  if (d.wasSubscribed) {
    // Ended subscription → « Expiré »; switched off (stored DISABLED, or a
    // TRIAL / ACTIVE row whose state is DISABLED) → « Désactivé ».
    return ModuleRow(
      name: name,
      status: d.status == kSubscriptionExpired ? 'Expiré' : 'Désactivé',
      section: ModuleSection.paid,
      active: false,
      action: ModuleAction.requestActivation,
    );
  }
  return ModuleRow(
    name: name,
    status: 'Non activé',
    section: ModuleSection.available,
    active: false,
    action: ModuleAction.requestActivation,
  );
}

/// WhatsApp message to Keevo support naming the business, the owner's phone
/// number and the module.
String supportMessage({
  required String moduleName,
  String? businessName,
  String? ownerPhone,
  bool reactivate = false,
}) {
  final b = businessName?.trim() ?? '';
  final p = ownerPhone?.trim() ?? '';
  final business = (b.isEmpty ? 'mon commerce' : 'mon commerce « $b »') +
      (p.isEmpty ? '' : ' (téléphone $p)');
  final verb = reactivate ? 'réactiver' : 'activer';
  return 'Bonjour, je souhaite $verb le module « $moduleName » pour '
      '$business sur Keevo. Merci de m\'indiquer la marche à suivre.';
}

// ── Widgets ─────────────────────────────────────────────────────────────────

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 2),
      child: Text(
        text.toUpperCase(),
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: cs.muted,
        ),
      ),
    );
  }
}

class _OfflineNote extends StatelessWidget {
  final String text;
  const _OfflineNote({required this.text});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      key: const Key('my-modules-offline'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: cs.warningContainer,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
      ),
      child: Row(children: [
        Icon(Icons.cloud_off_rounded, size: 18, color: cs.onWarningContainer),
        const SizedBox(width: 10),
        Expanded(
          child: Text(text,
              style: TextStyle(fontSize: 13, color: cs.onWarningContainer)),
        ),
      ]),
    );
  }
}

class _ModuleCard extends StatelessWidget {
  final ModuleRow module;
  final VoidCallback? onRequest;

  const _ModuleCard({required this.module, this.onRequest});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final color = module.active ? cs.successStrong : cs.muted;
    return Container(
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            SoftIconBadge(
              icon: module.active
                  ? Icons.check_circle_outline_rounded
                  : Icons.extension_outlined,
              color: module.active ? cs.successStrong : cs.primary,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    module.name,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: cs.onSurface,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    module.status,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: color,
                    ),
                  ),
                ],
              ),
            ),
          ]),
          if (onRequest != null) ...[
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: module.action == ModuleAction.reactivate
                  ? TextButton.icon(
                      onPressed: onRequest,
                      icon: const Icon(Icons.chat_outlined, size: 18),
                      label: const Text(MyModulesPage.reactivateLabel),
                    )
                  : FilledButton.tonalIcon(
                      onPressed: onRequest,
                      icon: const Icon(Icons.chat_outlined, size: 18),
                      label: const Text(MyModulesPage.requestActivationLabel),
                    ),
            ),
          ],
        ],
      ),
    );
  }
}
