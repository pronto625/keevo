import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_invoicing/invoicing_module.dart';

/// Story 15.4c — the module packages the app ships, composed by the app
/// shell: their routes go into the router (`app_router.dart`), their
/// extension action handlers into the action registry
/// (`core/extension/extension_actions.dart`).
///
/// Each descriptor library is small; the module's screens and handlers are
/// reached through `deferred as` imports inside its package. A module left
/// out of this list has no route mounted and its action keys are unknown
/// (its slot buttons are not rendered). Whether a registered module is
/// usable by the tenant is decided at runtime by the 15.2 guard and the
/// server-driven slots, not here.
final List<KeevoModuleUi> kRegisteredModules = [invoicingModuleUi];
