import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/utils/support_contact.dart';

import '../../features/dashboard/presentation/provider/dashboard_providers.dart'
    show currentTenantNameProvider;
import '../../features/settings/presentation/page/my_modules_page.dart'
    show supportMessage;
import '../../features/team/presentation/util/employee_whatsapp.dart'
    show openWhatsApp;
import '../router/module_routes.dart';
import 'entitlements_provider.dart';

/// Douala time (WAT, UTC+1, no daylight saving): the zone of the server's
/// licence messages, so the banner and the WhatsApp message give one date.
const _watOffset = Duration(hours: 1);

/// Story 16.1 (MOD-FR13) — text of the read-only banner: the end of the grace
/// (`graceUntil` of `GET /api/v1/tenants/me/modules`, shown as a Douala date)
/// when the server sent it — owners only — else no date.
String readOnlyBannerText(DateTime? graceUntil) => graceUntil == null
    ? 'Module en lecture seule'
    : 'Module en lecture seule jusqu\'au '
        '${DateFormat('dd/MM/yyyy').format(graceUntil.toUtc().add(_watOffset))}';

/// WhatsApp text of the banner's « Réactiver »: the « Mes modules » message
/// naming the module, or a neutral one when its name is not known.
String reactivationMessage(
    {String? moduleName, String? businessName, String? ownerPhone}) {
  if (moduleName != null && moduleName.trim().isNotEmpty) {
    return supportMessage(
        moduleName: moduleName,
        businessName: businessName,
        ownerPhone: ownerPhone,
        reactivate: true);
  }
  final b = businessName?.trim() ?? '';
  final p = ownerPhone?.trim() ?? '';
  final business = (b.isEmpty ? 'mon commerce' : 'mon commerce « $b »') +
      (p.isEmpty ? '' : ' (téléphone $p)');
  return 'Bonjour, je souhaite réactiver un module en lecture seule pour '
      '$business sur Keevo. Merci de m\'indiquer la marche à suivre.';
}

/// Story 16.1 — the persistent banner of a module whose subscription ended and
/// whose 7-day read-only grace runs (state READ_ONLY): reads work, writes are
/// refused by the server. The owner gets a « Réactiver » button asking Keevo
/// support on WhatsApp (same message as « Mes modules »); an employee sees the
/// banner only. Nothing when the module is not READ_ONLY. Never shows the
/// module id.
class ModuleReadOnlyBanner extends ConsumerWidget {
  const ModuleReadOnlyBanner({super.key, required this.moduleId});

  final String moduleId;

  static const reactivateLabel = 'Réactiver';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final entitlements = ref.watch(entitlementsProvider).value;
    if (entitlements == null ||
        entitlements.states[moduleId] != kModuleReadOnly) {
      return const SizedBox.shrink();
    }
    final owner = ref.watch(currentUserRoleProvider) == 'OWNER';
    final cs = Theme.of(context).colorScheme;
    final text =
        readOnlyBannerText(entitlements.detailsOf(moduleId)?.graceUntil);
    return Material(
      key: const Key('module-read-only-banner'),
      color: cs.warningContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
        child: Row(children: [
          Icon(Icons.lock_clock_outlined,
              size: 20, color: cs.onWarningContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Text(text,
                  style: TextStyle(color: cs.onSurface, fontSize: 13)),
            ),
          ),
          if (owner)
            TextButton(
              key: const Key('module-read-only-reactivate'),
              onPressed: () => _contactSupport(context, ref, entitlements),
              child: const Text(reactivateLabel),
            ),
        ]),
      ),
    );
  }

  Future<void> _contactSupport(
      BuildContext context, WidgetRef ref, Entitlements entitlements) {
    final message = reactivationMessage(
      moduleName: entitlements.displayNameOf(moduleId),
      businessName: ref.read(currentTenantNameProvider),
      ownerPhone: ref.read(currentUserPhoneProvider),
    );
    return openWhatsApp(
        ScaffoldMessenger.of(context), supportWhatsAppUri(message));
  }
}

/// Path of the screen [router] shows now (pushed routes included), null
/// before the first navigation.
String? currentRouterPath(GoRouter router) {
  final delegate = router.routerDelegate;
  if (delegate.currentConfiguration.isEmpty) return null;
  return delegate.state.uri.path;
}

/// Story 16.1 — puts [ModuleReadOnlyBanner] above every screen of a module in
/// read-only grace: wraps the app's navigator (`MaterialApp.router.builder`),
/// follows [router]'s location and the module states. The banner keeps clear
/// of the status bar, and the screen below no longer pads for it twice (same
/// layout as the shell banners).
class ModuleReadOnlyLayout extends ConsumerStatefulWidget {
  const ModuleReadOnlyLayout(
      {super.key, required this.router, required this.child});

  final GoRouter router;
  final Widget child;

  @override
  ConsumerState<ModuleReadOnlyLayout> createState() =>
      _ModuleReadOnlyLayoutState();
}

class _ModuleReadOnlyLayoutState extends ConsumerState<ModuleReadOnlyLayout> {
  String? _module;

  @override
  void initState() {
    super.initState();
    widget.router.routerDelegate.addListener(_onRoute);
    _module = _moduleNow();
  }

  @override
  void didUpdateWidget(ModuleReadOnlyLayout old) {
    super.didUpdateWidget(old);
    if (old.router != widget.router) {
      old.router.routerDelegate.removeListener(_onRoute);
      widget.router.routerDelegate.addListener(_onRoute);
      _module = _moduleNow();
    }
  }

  @override
  void dispose() {
    widget.router.routerDelegate.removeListener(_onRoute);
    super.dispose();
  }

  String? _moduleNow() {
    final path = currentRouterPath(widget.router);
    return path == null ? null : moduleOfPath(path);
  }

  void _onRoute() {
    if (SchedulerBinding.instance.schedulerPhase ==
        SchedulerPhase.persistentCallbacks) {
      // notified while a frame is being built: apply after it
      SchedulerBinding.instance.addPostFrameCallback((_) => _apply());
    } else {
      _apply();
    }
  }

  void _apply() {
    if (!mounted) return;
    final module = _moduleNow();
    if (module != _module) setState(() => _module = module);
  }

  @override
  Widget build(BuildContext context) {
    final module = _module;
    final readOnly = module != null &&
        ref.watch(entitlementsProvider).value?.states[module] ==
            kModuleReadOnly;
    // Same tree shape with or without the banner: the navigator below keeps
    // its state (its route stack) when the banner appears or goes.
    return Column(
      children: [
        if (readOnly)
          // the status bar area takes the banner's colour (no black strip
          // above it) with readable status icons
          AnnotatedRegion<SystemUiOverlayStyle>(
            value: Theme.of(context).brightness == Brightness.dark
                ? SystemUiOverlayStyle.light
                : SystemUiOverlayStyle.dark,
            child: ColoredBox(
              color: Theme.of(context).colorScheme.warningContainer,
              child: SafeArea(
                  bottom: false,
                  child: ModuleReadOnlyBanner(moduleId: module)),
            ),
          )
        else
          const SizedBox.shrink(),
        Expanded(
          child: MediaQuery.removePadding(
              context: context, removeTop: readOnly, child: widget.child),
        ),
      ],
    );
  }
}
