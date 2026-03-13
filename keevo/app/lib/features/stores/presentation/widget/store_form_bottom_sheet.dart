import 'package:flutter/material.dart';

import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';

/// StoreFormBottomSheet — modal bottom sheet for create or edit store.
///
/// - Create mode: all fields editable; type radio visible.
/// - Edit mode: type is read-only (badge); name/address/phone editable.
///
/// Story 3.1 — Task 20.4.
class StoreFormBottomSheet extends StatefulWidget {
  /// If provided, this opens in edit mode with the store's current values.
  final StoreModel? initialStore;

  /// Called on form submission with (name, type, address?, phone?).
  final Future<void> Function(
      String name, StoreType type, String? address, String? phone) onSubmit;

  const StoreFormBottomSheet({
    super.key,
    this.initialStore,
    required this.onSubmit,
  });

  @override
  State<StoreFormBottomSheet> createState() => _StoreFormBottomSheetState();
}

class _StoreFormBottomSheetState extends State<StoreFormBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _addressController;
  late final TextEditingController _phoneController;
  late StoreType _selectedType;
  bool _loading = false;

  bool get _isEditMode => widget.initialStore != null;

  @override
  void initState() {
    super.initState();
    _nameController =
        TextEditingController(text: widget.initialStore?.name ?? '');
    _addressController =
        TextEditingController(text: widget.initialStore?.address ?? '');
    _phoneController =
        TextEditingController(text: widget.initialStore?.phone ?? '');
    _selectedType = widget.initialStore?.type ?? StoreType.store;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _addressController.dispose();
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _loading = true);
    try {
      await widget.onSubmit(
        _nameController.text.trim(),
        _selectedType,
        _addressController.text.trim().isEmpty
            ? null
            : _addressController.text.trim(),
        _phoneController.text.trim().isEmpty
            ? null
            : _phoneController.text.trim(),
      );
      if (mounted) Navigator.of(context).pop();
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mediaQuery = MediaQuery.of(context);

    return Padding(
      padding: EdgeInsets.only(
        left: 24,
        right: 24,
        top: 24,
        bottom: mediaQuery.viewInsets.bottom + 24,
      ),
      child: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // ── Title ──────────────────────────────────────────────
              Text(
                _isEditMode ? 'Modifier la boutique' : 'Nouvelle boutique',
                style: theme.textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 20),

              // ── Nom ───────────────────────────────────────────────
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(
                  labelText: 'Nom *',
                  hintText: 'Ex: Boutique Centrale',
                  border: OutlineInputBorder(),
                ),
                textCapitalization: TextCapitalization.sentences,
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Le nom est requis';
                  if (v.trim().length < 2) return 'Minimum 2 caractères';
                  if (v.trim().length > 100) return 'Maximum 100 caractères';
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // ── Type (create only) ─────────────────────────────────
              if (_isEditMode) ...[
                Row(
                  children: [
                    Text('Type :',
                        style: theme.textTheme.bodyMedium
                            ?.copyWith(color: theme.colorScheme.outline)),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(8),
                        color: theme.colorScheme.secondaryContainer,
                      ),
                      child: Text(
                        '${_selectedType.icon} ${_selectedType.displayName}',
                        style: theme.textTheme.labelMedium,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
              ] else ...[
                Text('Type', style: theme.textTheme.labelLarge),
                const SizedBox(height: 8),
                _TypeRadioGroup(
                  selected: _selectedType,
                  onChanged: (t) => setState(() => _selectedType = t),
                ),
                const SizedBox(height: 16),
              ],

              // ── Adresse ───────────────────────────────────────────
              TextFormField(
                controller: _addressController,
                decoration: const InputDecoration(
                  labelText: 'Adresse (optionnel)',
                  hintText: 'Ex: Rue des marchés, Yaoundé',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),

              // ── Téléphone ─────────────────────────────────────────
              TextFormField(
                controller: _phoneController,
                decoration: const InputDecoration(
                  labelText: 'Téléphone (optionnel)',
                  hintText: '+237 6XX XXX XXX',
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.phone,
              ),
              const SizedBox(height: 24),

              // ── Submit ────────────────────────────────────────────
              SizedBox(
                width: double.infinity,
                height: 50,
                child: FilledButton(
                  onPressed: _loading ? null : _submit,
                  child: _loading
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : Text(_isEditMode ? 'Enregistrer' : 'Créer'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Radio group for selecting store type (create mode only).
class _TypeRadioGroup extends StatelessWidget {
  final StoreType selected;
  final ValueChanged<StoreType> onChanged;

  const _TypeRadioGroup({
    required this.selected,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: StoreType.values.map((type) {
        return Expanded(
          child: RadioListTile<StoreType>(
            contentPadding: EdgeInsets.zero,
            title: Text('${type.icon} ${type.displayName}'),
            value: type,
            groupValue: selected,
            onChanged: (v) => v != null ? onChanged(v) : null,
          ),
        );
      }).toList(),
    );
  }
}
