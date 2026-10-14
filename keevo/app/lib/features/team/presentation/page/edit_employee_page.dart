import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/countries.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../../../core/storage/app_constants.dart';
import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/phone_validation.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../domain/model/employee_model.dart';
import '../provider/employee_provider.dart';
import '../widget/set_password_bottom_sheet.dart';

/// Story 14.11 (FR66) — "Modifier l'employé".
///
/// Same structure as [CreateEmployeePage]: Form + controllers pre-filled
/// from [employee], IntlPhoneField, store dropdown, plus a role dropdown
/// (disabled for the connected user) and a security section. Only the
/// fields that changed are sent (partial PATCH); a role change is sent
/// separately after a confirmation, since it logs the employee out.
///
/// The phone field is pre-filled from `employee.phoneNumber` (E.164 split
/// into country + national part); left empty it keeps the current number.
class EditEmployeePage extends ConsumerStatefulWidget {
  final EmployeeModel employee;

  const EditEmployeePage({super.key, required this.employee});

  @override
  ConsumerState<EditEmployeePage> createState() => _EditEmployeePageState();
}

class _EditEmployeePageState extends ConsumerState<EditEmployeePage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _firstNameController;
  late final TextEditingController _lastNameController;

  String? _completePhone;
  String _typedNumber = '';
  bool _phoneValid = false;
  String _initialCountryCode = kDefaultPhoneCountryCode;
  String _initialNationalNumber = '';
  String? _selectedStoreId;
  late String _selectedRole;
  bool _saving = false;
  Object? _error;
  bool _errorDuringRoleChange = false;

  static const _roles = {'EMPLOYEE': 'Employé', 'OWNER': 'Propriétaire'};

  @override
  void initState() {
    super.initState();
    final e = widget.employee;
    _firstNameController = TextEditingController(text: e.firstName);
    _lastNameController = TextEditingController(text: e.lastName);
    _selectedStoreId = e.storeId;
    _selectedRole = _roles.containsKey(e.role) ? e.role : 'EMPLOYEE';
    final split = splitE164(e.phoneNumber);
    if (split != null) {
      _initialCountryCode = split.country.code;
      _initialNationalNumber = split.national;
      _typedNumber = split.national;
      _completePhone = e.phoneNumber;
      _phoneValid = true;
    }
  }

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    super.dispose();
  }

  /// J31: a meaningful French message when the error has none of its own.
  String _errorText(Object error) {
    final text = appErrorMessage(error);
    if (text != kAppGenericErrorMessage) return text;
    return _errorDuringRoleChange
        ? 'Le rôle n\'a pas pu être modifié. Vérifiez votre connexion puis '
            'réessayez.'
        : 'Les modifications n\'ont pas pu être enregistrées. Vérifiez votre '
            'connexion puis réessayez.';
  }

  bool get _isSelf =>
      ref.read(currentUserIdProvider).valueOrNull == widget.employee.userId;

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    // An empty phone keeps the current number; a typed one must be valid.
    if (_typedNumber.isNotEmpty && (!_phoneValid || _completePhone == null)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Saisissez un numéro de téléphone valide'),
        behavior: SnackBarBehavior.floating,
      ));
      return;
    }
    if (_selectedStoreId == null) return;

    final e = widget.employee;
    final firstName = _firstNameController.text.trim();
    final lastName = _lastNameController.text.trim();
    final newFirst = firstName != e.firstName ? firstName : null;
    final newLast = lastName != e.lastName ? lastName : null;
    final newPhone =
        _typedNumber.isNotEmpty && _completePhone != e.phoneNumber
            ? _completePhone
            : null;
    final newStore = _selectedStoreId != e.storeId ? _selectedStoreId : null;
    final profileChanged = newFirst != null ||
        newLast != null ||
        newPhone != null ||
        newStore != null;
    final roleChanged = !_isSelf && _selectedRole != e.role;

    if (!profileChanged && !roleChanged) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Aucune modification à enregistrer'),
        behavior: SnackBarBehavior.floating,
      ));
      return;
    }

    if (roleChanged) {
      final confirmed = await showDisconnectConfirmDialog(
        context,
        title: 'Changer le rôle ?',
        employee: e,
      );
      if (confirmed != true || !mounted) return;
    }

    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      if (profileChanged) {
        await ref.read(updateEmployeeProvider.notifier).updateProfile(
              e.id,
              firstName: newFirst,
              lastName: newLast,
              phoneNumber: newPhone,
              storeId: newStore,
            );
        final s = ref.read(updateEmployeeProvider);
        if (s.hasError) throw s.error!;
      }
      if (roleChanged) {
        await ref
            .read(changeEmployeeRoleProvider.notifier)
            .change(e.id, _selectedRole);
        final s = ref.read(changeEmployeeRoleProvider);
        if (s.hasError) throw s.error!;
      }
    } catch (err, st) {
      // J31: an unexpected failure is logged (debug) — it used to surface
      // only as « Une erreur inattendue… » with nothing to diagnose it.
      debugPrint('[EditEmployee] save failed (role change: $roleChanged, '
          'profile: $profileChanged): $err\n$st');
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = err;
        _errorDuringRoleChange = roleChanged;
      });
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
      content: Text('Employé mis à jour'),
      behavior: SnackBarBehavior.floating,
    ));
    if (context.canPop()) {
      context.pop(true);
    } else {
      context.go('/settings/team');
    }
  }

  Future<void> _setPassword() async {
    final done = await showSetPasswordBottomSheet(
      context: context,
      employee: widget.employee,
    );
    if (done == true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Mot de passe défini'),
        behavior: SnackBarBehavior.floating,
      ));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final storesAsync = ref.watch(storeListNotifierProvider);
    final isSelf =
        ref.watch(currentUserIdProvider).valueOrNull == widget.employee.userId;

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Modifier l\'employé'),
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

              // ── Numéro de téléphone (pré-rempli, vide = inchangé) ───
              IntlPhoneField(
                decoration: const InputDecoration(
                  labelText: 'Numéro de téléphone',
                  helperText: 'Laisser vide pour conserver le numéro actuel',
                ),
                initialCountryCode: _initialCountryCode,
                initialValue: _initialNationalNumber,
                languageCode: 'fr',
                // The package's own length check overrides [validator]'s
                // message for too-short numbers.
                invalidNumberMessage: 'Numéro invalide pour ce pays',
                keyboardType: TextInputType.phone,
                onChanged: (PhoneNumber phone) {
                  _typedNumber = phone.number;
                  _completePhone = phone.completeNumber;
                  _phoneValid = isValidPhoneNumber(phone);
                },
                onCountryChanged: (country) {
                  final phone = PhoneNumber(
                    countryISOCode: country.code,
                    countryCode: '+${country.fullCountryCode}',
                    number: _typedNumber,
                  );
                  _phoneValid = isValidPhoneNumber(phone);
                  _completePhone = _phoneValid ? phone.completeNumber : null;
                },
                validator: (phone) {
                  if (phone == null || phone.number.isEmpty) return null;
                  return isValidPhoneNumber(phone)
                      ? null
                      : 'Numéro invalide pour ce pays';
                },
              ),
              const SizedBox(height: 16),

              // ── Boutique assignée ───────────────────────────────────
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (_, __) =>
                    const Text('Impossible de charger les boutiques'),
                data: (stores) {
                  final active = stores.where((s) => s.isActive).toList();
                  final known = active.any((s) => s.id == _selectedStoreId);
                  return DropdownButtonFormField<String>(
                    initialValue: known ? _selectedStoreId : null,
                    decoration: const InputDecoration(
                      labelText: 'Boutique assignée',
                      prefixIcon: Icon(Icons.storefront_outlined),
                    ),
                    items: active
                        .map((StoreModel s) => DropdownMenuItem(
                              value: s.id,
                              child: Text(s.name),
                            ))
                        .toList(),
                    onChanged: (val) =>
                        setState(() => _selectedStoreId = val),
                    validator: (v) => v == null ? 'Boutique requise' : null,
                  );
                },
              ),
              const SizedBox(height: 16),

              // ── Rôle ────────────────────────────────────────────────
              DropdownButtonFormField<String>(
                initialValue: _selectedRole,
                decoration: InputDecoration(
                  labelText: 'Rôle',
                  prefixIcon: const Icon(Icons.badge_outlined),
                  helperText: isSelf
                      ? 'Vous ne pouvez pas modifier votre propre rôle'
                      : 'Changer le rôle déconnecte l\'employé',
                ),
                items: _roles.entries
                    .map((r) => DropdownMenuItem(
                          value: r.key,
                          child: Text(r.value),
                        ))
                    .toList(),
                onChanged: isSelf
                    ? null
                    : (val) => setState(() => _selectedRole = val ?? 'EMPLOYEE'),
              ),
              const SizedBox(height: 32),

              // ── Error message ───────────────────────────────────────
              if (_error != null) ...[
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
                          _errorText(_error!),
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
                onPressed: _saving ? null : _submit,
                icon: _saving
                    ? SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: cs.onSurfaceVariant,
                        ),
                      )
                    : const Icon(Icons.check_rounded),
                label: const Text('Enregistrer'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  ),
                ),
              ),

              // ── Sécurité ────────────────────────────────────────────
              if (!isSelf) ...[
                const SizedBox(height: 32),
                Text(
                  'Sécurité',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: cs.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 8),
                Container(
                  decoration: BoxDecoration(
                    color: cs.surface,
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                    border: Border.all(color: cs.outlineVariant.withAlpha(90)),
                  ),
                  child: ListTile(
                    leading: Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: AppTheme.softTint(theme.brightness),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Icon(Icons.password_rounded, color: cs.primary),
                    ),
                    title: const Text('Définir le mot de passe'),
                    subtitle: const Text(
                        'L\'employé devra le changer à sa prochaine connexion'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: _saving ? null : _setPassword,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Splits an E.164 number into the intl_phone_field country and the
/// national part (longest dial-code prefix wins). Null when unknown.
({Country country, String national})? splitE164(String? e164) {
  if (e164 == null) return null;
  final digits = e164.replaceAll(RegExp(r'[^0-9]'), '');
  if (digits.isEmpty) return null;
  Country? best;
  for (final c in countries) {
    if (digits.startsWith(c.dialCode) &&
        (best == null || c.dialCode.length > best.dialCode.length)) {
      best = c;
    }
  }
  if (best == null) return null;
  return (country: best, national: digits.substring(best.dialCode.length));
}
