# Extension slots — integration guide

Story 15.3. An **extension point** is a place in one module's screen where other
modules may add actions (UX-DR3, MOD-FR10). Example: `pos.checkout.post-sale-actions`,
declared by `pos`, filled by `invoicing` with « Facturer ».

**Ownership decision: server-driven.** The backend `ExtensionRegistry` is the single
source of a contribution's label (French), order and permission. The app never
defines a label itself: it only maps the contribution's `action` key to a handler.

## 1. Declare a point (owning module)

In the owner's manifest `keevo/backend/src/main/resources/keevo/modules/<id>/module.json`:

```json
"extensionPoints": { "declares": ["pos.checkout.post-sale-actions"], "fills": [] }
```

Point ids: `<module>.<screen>.<slot>`, lowercase. A point may be shared by
several screens of the same purpose: `pos.checkout.post-sale-actions` is the
post-sale actions point, rendered on the checkout success screen and on the sale
detail screen (history) with the same context. An undeclared point answers 404
« Point d'extension inconnu » on the endpoint below.

## 2. Contribute (contributing module, backend)

1. List the point in the contributor's manifest `extensionPoints.fills`.
2. Add a singleton bean implementing `com.keevo.shared.module.Extension`, annotated
   `@ContributesExtension(module = "<contributor id>", point = "<point id>")`:
   - `id()` stable key (never shown), `label()` French text of the button,
     `order()` ascending position, `action()` client action key
     (`<module>.<verb>-<object>`, e.g. `invoicing.open-sale-invoice`);
   - `permission()` a permission declared in the contributor manifest's
     `permissions` (or `null` for everyone). The endpoint drops the contribution
     for a caller without it.
3. The boot fails (message naming class, module and point) on: unknown module,
   point not in `fills`, `fills` entry without a bean, duplicate id, blank
   id/label/action, undeclared permission.

The contribution is listed only while the declaring module is readable and the
contributing module is ENABLED for the tenant.

Endpoint used by the app: `GET /api/v1/tenants/me/extensions/{pointId}` (OWNER /
EMPLOYEE) → `data: [{id, label, order, action}]`.

### Pitfall: `@Bean` method `List<Extension>` parameter

Contribution beans are **not autowire candidates**
(`shared/module/infrastructure/ModuleBeanAutowireExclusion.java`), so that no module
can call another's contribution directly and skip the tenant check. A consequence
that does not fail: a `@Bean` method (or constructor) outside `shared.module` taking a
`List<Extension>` parameter silently receives an **empty list**. Never inject
contributions; inject `ExtensionRegistry` and call
`contributions(pointId, tenantSchema)`.

## 3. Handle the action (app)

1. Register the action key in `keevo/app/lib/core/extension/extension_actions.dart`
   (`kExtensionActions`). The handler signature is
   `Future<void> Function(BuildContext, WidgetRef, Map<String, Object?> args)`.
2. Load the module code lazily: import the handler's library with
   `deferred as`, call `loadLibrary()` in the handler, then the real function.
   A module screen mounted in `app_router.dart` uses the same `deferred as`
   import wrapped in `DeferredWidget` (`core/widget/deferred_widget.dart`).
   On Android/iOS, `deferred as` does not split the code without Flutter
   deferred components configured: today the deferral is structural (module
   code reached only through `loadLibrary`), ready for the package extraction
   of Story 15.4c. A handler failure (e.g. `loadLibrary`) shows a French
   snackbar; a second tap while an action runs is ignored.
3. Module screens opened by a handler must be declared in the module manifest's
   `routes` (and so in `core/router/module_routes.dart`) — the 15.2 guard applies.

An action key the app does not know (backend newer than the app) is not rendered.

## 4. Render the slot (owning screen, app)

```dart
ExtensionSlot(
  pointId: kPosCheckoutPostSaleActions,
  args: {'saleId': sale.id},
)
```

- One button per contribution, ordered by `order`, labelled by the server.
- Nothing while loading, on failure (offline, 404, 5xx) or when no module
  contributes — the screen is never blocked and no error is shown.
- Contents are cached per session (`extensionContributionsProvider`, family by
  point id): one successful request per point; rebuilt when any module state of
  `entitlementsProvider` changes; dropped on sign-out and on a new sign-in. A
  failure is not cached: the slot's next render asks again.
- Contributions are deduplicated by id and ordered by `order` then id; one sent
  without `order` comes last.
- `visibleExtensionContributions(ref, pointId)` tells a screen whether the slot
  shows anything (the checkout success page uses it to cancel its automatic
  return to the POS and show « Nouvelle vente »).

## Existing points

| Point | Declared by | Context args | Contributions |
|-------|-------------|--------------|---------------|
| `pos.checkout.post-sale-actions` (checkout success + sale detail) | pos | `saleId` | invoicing « Facturer » → `invoicing.open-sale-invoice` (`INVOICE_VIEW`) |
