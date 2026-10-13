import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../../../core/storage/app_constants.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../settings/presentation/widget/plan_limit_bottom_sheet.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../provider/employee_provider.dart';
import '../widget/temp_password_bottom_sheet.dart';

/// CreateEmployeePage — Story 3.5, AC1.
///
/// Form with: Prénom, Nom, Numéro de téléphone (IntlPhoneField),
/// Boutique assignée (dropdown). On success shows TempPasswordBottomSheet.
class CreateEmployeePage extends ConsumerStatefulWidget {
  const CreateEmployeePage({super.key});

  @override
  ConsumerState<CreateEmployeePage> createState() => _CreateEmployeePageState();
}

class _CreateEmployeePageState extends ConsumerState<CreateEmployeePage> {
  final _formKey = GlobalKey<FormState>();
  final _firstNameController = TextEditingController();
  final _lastNameController = TextEditingController();

  String? _completePhone;
  String _typedNumber = '';
  bool _phoneValid = false;
  StoreModel? _selectedStore;
  bool _successHandled = false;

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    super.dispose();
  }

  // Chiffres uniquement et longueur conforme au pays : un champ vidé ou
  // contenant des séparateurs ne doit pas passer pour valide.
  static bool _isValidPhone(PhoneNumber phone) {
    if (!RegExp(r'^\d+$').hasMatch(phone.number)) return false;
    try {
      return phone.isValidNumber();
    } catch (_) {
      return false;
    }
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_phoneValid || _completePhone == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Saisissez un numéro de téléphone valide'),
        behavior: SnackBarBehavior.floating,
      ));
      return;
    }
    if (_selectedStore == null) return;

    await ref.read(createEmployeeProvider.notifier).create(
          firstName: _firstNameController.text.trim(),
          lastName: _lastNameController.text.trim(),
          phoneNumber: _completePhone!,
          storeId: _selectedStore!.id,
        );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final createState = ref.watch(createEmployeeProvider);
    final storesAsync = ref.watch(storeListNotifierProvider);

    // Listen for success → show temp password bottom sheet
    ref.listen(createEmployeeProvider, (prev, next) {
      next.whenOrNull(
        data: (result) {
          if (result == null) return;
          if (_successHandled) return;
          _successHandled = true;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            showTempPasswordBottomSheet(
              context: context,
              employeeName:
                  '${result.employee.firstName} ${result.employee.lastName}',
              storeName: _selectedStore?.name ?? '',
              temporaryPassword: result.temporaryPassword,
              invitePhone: result.employee.phoneNumber ?? _completePhone,
              firstName: result.employee.firstName,
            ).then((_) {
              if (!mounted) return;
              // B2: use go() fallback when pop() is unavailable (e.g. GoRouter
              // stack was reset during the bottom-sheet async gap).
              if (context.canPop()) {
                context.pop();
              } else {
                context.go('/settings/team');
              }
            });
          });
        },
        error: (error, _) {
          if (error is Exception &&
              error.toString().contains('PLAN_LIMIT_EXCEEDED')) {
            showPlanLimitBottomSheet(
              context: context,
              entity: 'employees',
            );
          }
        },
      );
    });

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Nouvel employé'),
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ── Prénom ──────────────────────────────────────────────
              TextFormField(
                controller: _firstNameController,
                decoration: const InputDecoration(
                  labelText: 'Prénom',
                  prefixIcon: Icon(Icons.person_outline),
                ),
                textCapitalization: TextCapitalization.words,
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Prénom requis' : null,
              ),
              const SizedBox(height: 16),

              // ── Nom ─────────────────────────────────────────────────
              TextFormField(
                controller: _lastNameController,
                decoration: const InputDecoration(
                  labelText: 'Nom',
                  prefixIcon: Icon(Icons.person_outline),
                ),
                textCapitalization: TextCapitalization.words,
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Nom requis' : null,
              ),
              const SizedBox(height: 16),

              // ── Numéro de téléphone ─────────────────────────────────
              IntlPhoneField(
                decoration: const InputDecoration(
                  labelText: 'Numéro de téléphone',
                ),
                initialCountryCode: kDefaultPhoneCountryCode,
                languageCode: 'fr',
                keyboardType: TextInputType.phone,
                onChanged: (PhoneNumber phone) {
                  _typedNumber = phone.number;
                  _completePhone = phone.completeNumber;
                  _phoneValid = _isValidPhone(phone);
                },
                // Changer de pays ne déclenche pas onChanged : on rejuge le
                // numéro déjà saisi avec le nouveau pays, sinon un numéro
                // valide serait refusé à l'envoi tant qu'il n'est pas retapé.
                onCountryChanged: (country) {
                  final phone = PhoneNumber(
                    countryISOCode: country.code,
                    countryCode: '+${country.fullCountryCode}',
                    number: _typedNumber,
                  );
                  _phoneValid = _isValidPhone(phone);
                  _completePhone = _phoneValid ? phone.completeNumber : null;
                },
                // Appelé avant onChanged : on juge le numéro reçu, pas
                // l'état de la frappe précédente.
                validator: (phone) {
                  if (phone == null || !_isValidPhone(phone)) {
                    return 'Numéro invalide pour ce pays';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // ── Boutique assignée ───────────────────────────────────
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (_, __) =>
                    const Text('Impossible de charger les boutiques'),
                data: (stores) => DropdownButtonFormField<StoreModel>(
                  initialValue: _selectedStore,
                  decoration: const InputDecoration(
                    labelText: 'Boutique assignée',
                    prefixIcon: Icon(Icons.storefront_outlined),
                  ),
                  items: stores
                      .map((s) => DropdownMenuItem(
                            value: s,
                            child: Text(s.name),
                          ))
                      .toList(),
                  onChanged: (val) => setState(() => _selectedStore = val),
                  validator: (v) => v == null ? 'Boutique requise' : null,
                ),
              ),
              const SizedBox(height: 12),

              // ── Rôle (readonly for now) ─────────────────────────────
              const InputDecorator(
                decoration: InputDecoration(
                  labelText: 'Rôle',
                  prefixIcon: Icon(Icons.badge_outlined),
                ),
                child: Text('Employé'),
              ),
              const SizedBox(height: 32),

              // ── Error message ───────────────────────────────────────
              if (createState.hasError) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline,
                          color: cs.onErrorContainer, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _errorMessage(createState.error),
                          style: TextStyle(color: cs.onErrorContainer),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],

              // ── Submit button ───────────────────────────────────────
              FilledButton.icon(
                onPressed: createState.isLoading ? null : _submit,
                icon: createState.isLoading
                    ? SizedBox(
                        width: 18,
                        height: 18,
                        // Le bouton est désactivé pendant le chargement : un
                        // spinner blanc y serait invisible.
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: cs.onSurfaceVariant,
                        ),
                      )
                    : const Icon(Icons.person_add_rounded),
                label: const Text('Créer l\'employé'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _errorMessage(Object? error) {
    if (error != null && error.toString().contains('PHONE_ALREADY_REGISTERED')) {
      return 'Ce numéro de téléphone est déjà enregistré';
    }
    if (error != null && error.toString().contains('PLAN_LIMIT_EXCEEDED')) {
      return 'Limite du plan atteinte';
    }
    return 'Une erreur est survenue, réessayez';
  }
}
