import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

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
  bool _phoneValid = false;
  StoreModel? _selectedStore;
  bool _successHandled = false;

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_phoneValid || _completePhone == null) return;
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
              limit: 5,
            );
          }
        },
      );
    });

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Nouvel employé'),
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
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
                decoration: InputDecoration(
                  labelText: 'Prénom',
                  prefixIcon: const Icon(Icons.person_outline),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                textCapitalization: TextCapitalization.words,
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Prénom requis' : null,
              ),
              const SizedBox(height: 16),

              // ── Nom ─────────────────────────────────────────────────
              TextFormField(
                controller: _lastNameController,
                decoration: InputDecoration(
                  labelText: 'Nom',
                  prefixIcon: const Icon(Icons.person_outline),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                textCapitalization: TextCapitalization.words,
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Nom requis' : null,
              ),
              const SizedBox(height: 16),

              // ── Numéro de téléphone ─────────────────────────────────
              IntlPhoneField(
                decoration: InputDecoration(
                  labelText: 'Numéro de téléphone',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                initialCountryCode: 'CM',
                languageCode: 'fr',
                keyboardType: TextInputType.phone,
                onChanged: (PhoneNumber phone) {
                  _completePhone = phone.completeNumber;
                  _phoneValid = true;
                },
                onCountryChanged: (_) {
                  _phoneValid = false;
                  _completePhone = null;
                },
                validator: (_) {
                  if (!_phoneValid) {
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
                  decoration: InputDecoration(
                    labelText: 'Boutique assignée',
                    prefixIcon: const Icon(Icons.storefront_rounded),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
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
              InputDecorator(
                decoration: InputDecoration(
                  labelText: 'Rôle',
                  prefixIcon: const Icon(Icons.badge_outlined),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                child: const Text('Employé'),
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
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.person_add_rounded),
                label: const Text('Créer l\'employé'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
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
