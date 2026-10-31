import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';

import '../modules/registered_modules.dart';

/// Action key (sent by the server in each contribution) → handler, composed
/// from the registered module descriptors (Story 15.4c).
///
/// The label, order and permission of a contribution come from the server
/// only; this registry holds what the app does when the button is tapped. A
/// key missing here (backend newer than the app, module not registered) is
/// not rendered. Handlers of module packages load their code with
/// `deferred as` inside the package.
final Map<String, ExtensionActionHandler> kExtensionActions =
    composeModuleActions(kRegisteredModules);

/// The action registry (overridden in tests).
final extensionActionsProvider = Provider<Map<String, ExtensionActionHandler>>(
  (ref) => kExtensionActions,
);
