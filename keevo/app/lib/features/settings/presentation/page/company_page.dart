import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/photo/company_logo_compressor.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart' show appErrorMessage;

import '../../../../core/photo/product_photo_providers.dart';
import '../../domain/model/company_profile.dart';
import '../provider/company_profile_provider.dart';

/// Paramètres › Entreprise (Story 14.2, OWNER only).
///
/// The company designation and logo printed on invoices and export documents:
/// the designation when filled in, else the shop name. Online only — the
/// documents are produced by the server.
class CompanyPage extends ConsumerStatefulWidget {
  const CompanyPage({super.key});

  @override
  ConsumerState<CompanyPage> createState() => _CompanyPageState();
}

class _CompanyPageState extends ConsumerState<CompanyPage> {
  static const offlineMessage =
      'Hors ligne — l\'identité de l\'entreprise se modifie avec une connexion internet.';

  final _designation = TextEditingController();
  bool _loading = true;
  bool _offline = false;
  String? _loadError;
  CompanyProfile? _profile;
  Uint8List? _logo;

  /// What is printed when the designation is blank: the shop name from the
  /// server, « Mon commerce » when the shop has none.
  String? _shopName;
  bool _saving = false;
  bool _logoBusy = false;

  @override
  void initState() {
    super.initState();
    _designation.addListener(() => setState(() {}));
    Future.microtask(_load);
  }

  @override
  void dispose() {
    _designation.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadError = null;
      _offline = false;
    });
    final online = await ref.read(connectivityServiceProvider).isOnline();
    if (!mounted) return;
    if (!online) {
      setState(() {
        _loading = false;
        _offline = true;
      });
      return;
    }
    try {
      final api = ref.read(companyProfileApiProvider);
      final profile = await api.fetch();
      final logo = profile.hasLogo ? await api.fetchLogo() : null;
      if (!mounted) return;
      setState(() {
        _apply(profile);
        _designation.text = profile.designation ?? '';
        _logo = logo;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadError = appErrorMessage(e);
      });
    }
  }

  void _apply(CompanyProfile profile) {
    _profile = profile;
    _shopName = profile.shopName ??
        (profile.designation == null ? profile.displayName : 'Mon commerce');
  }

  /// « Nom affiché » preview: the typed designation, else the shop name.
  String get _preview {
    final typed = _designation.text.trim();
    if (typed.isNotEmpty) return typed;
    return _shopName ?? 'Mon commerce';
  }

  bool get _dirty =>
      _profile != null && _designation.text.trim() != (_profile!.designation ?? '');

  Future<bool> _ensureOnline() async {
    final online = await ref.read(connectivityServiceProvider).isOnline();
    if (!online && mounted) _snack(offlineMessage, error: true);
    return online;
  }

  Future<void> _save() async {
    if (_saving || !await _ensureOnline()) return;
    setState(() => _saving = true);
    try {
      final profile = await ref
          .read(companyProfileApiProvider)
          .updateDesignation(_designation.text);
      if (!mounted) return;
      setState(() {
        _apply(profile);
        _designation.text = profile.designation ?? '';
      });
      _snack('Identité de l\'entreprise enregistrée');
    } catch (e) {
      if (mounted) _snack(appErrorMessage(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _pickLogo() async {
    if (_logoBusy || !await _ensureOnline()) return;
    final XFile? picked;
    try {
      picked = await ref.read(productPhotoPickerProvider).pickImage(
            source: ImageSource.gallery,
            maxWidth: CompanyLogoCompressor.pickerMaxDimension,
            maxHeight: CompanyLogoCompressor.pickerMaxDimension,
            imageQuality: CompanyLogoCompressor.pickerQuality,
          );
    } catch (_) {
      if (mounted) _snack('Impossible d\'ouvrir la galerie', error: true);
      return;
    }
    if (picked == null || !mounted) return;
    setState(() => _logoBusy = true);
    try {
      final CompressedLogo logo;
      try {
        logo = await ref
            .read(companyLogoCompressorProvider)
            .compress(await picked.readAsBytes());
      } catch (_) {
        if (mounted) {
          _snack('Cette image ne peut pas être utilisée, choisissez-en une autre',
              error: true);
        }
        return;
      }
      final profile = await ref
          .read(companyProfileApiProvider)
          .uploadLogo(logo.bytes, png: logo.png);
      if (!mounted) return;
      setState(() {
        _apply(profile);
        _logo = logo.bytes;
      });
      _snack('Logo enregistré');
    } catch (e) {
      if (mounted) _snack(appErrorMessage(e), error: true);
    } finally {
      if (mounted) setState(() => _logoBusy = false);
    }
  }

  Future<void> _removeLogo() async {
    if (_logoBusy || !await _ensureOnline()) return;
    if (!mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Supprimer le logo ?'),
        content: const Text(
            'Il n\'apparaîtra plus sur vos factures ni sur vos exports.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Annuler'),
          ),
          TextButton(
            key: const Key('company-logo-delete-confirm'),
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: AppTheme.errorColor),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _logoBusy = true);
    try {
      final profile = await ref.read(companyProfileApiProvider).deleteLogo();
      if (!mounted) return;
      setState(() {
        _apply(profile);
        _logo = null;
      });
      _snack('Logo supprimé');
    } catch (e) {
      if (mounted) _snack(appErrorMessage(e), error: true);
    } finally {
      if (mounted) setState(() => _logoBusy = false);
    }
  }

  void _snack(String message, {bool error = false}) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(message),
      backgroundColor: error ? AppTheme.errorStrong : AppTheme.successStrong,
      behavior: SnackBarBehavior.floating,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surfaceContainerLowest,
      appBar: AppBar(
        title: const Text('Entreprise'),
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: _body(cs),
    );
  }

  Widget _body(ColorScheme cs) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_offline || _loadError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(_offline ? Icons.cloud_off_rounded : Icons.error_outline,
                  size: 40, color: cs.onSurfaceVariant),
              const SizedBox(height: 12),
              Text(
                _offline ? offlineMessage : _loadError!,
                key: const Key('company-load-message'),
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: 12),
              TextButton(
                key: const Key('company-retry'),
                onPressed: _load,
                child: const Text('Réessayer'),
              ),
            ],
          ),
        ),
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
      children: [
        Text(
          'Ce nom et ce logo apparaissent sur vos factures et vos exports.',
          style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13),
        ),
        const SizedBox(height: 16),
        TextField(
          key: const Key('company-designation'),
          controller: _designation,
          maxLength: 150,
          textCapitalization: TextCapitalization.words,
          decoration: const InputDecoration(
            labelText: 'Désignation de l\'entreprise',
            hintText: 'Ex. : Tech Centre SARL',
            helperText: 'Laissez vide pour utiliser le nom de votre boutique',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'Nom affiché : $_preview',
          key: const Key('company-display-preview'),
          style: TextStyle(
              color: cs.onSurface, fontWeight: FontWeight.w600, fontSize: 14),
        ),
        const SizedBox(height: 12),
        FilledButton(
          key: const Key('company-save'),
          onPressed: _dirty && !_saving ? _save : null,
          child: Text(_saving ? 'Enregistrement…' : 'Enregistrer'),
        ),
        const SizedBox(height: 28),
        Text('Logo',
            style: TextStyle(
                color: cs.onSurface, fontWeight: FontWeight.w700, fontSize: 15)),
        const SizedBox(height: 8),
        Container(
          height: 120,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: cs.surface,
            borderRadius: BorderRadius.circular(AppTheme.radiusM),
            border: Border.all(color: cs.outlineVariant),
          ),
          child: _logoBusy
              ? const CircularProgressIndicator()
              : _logo != null
                  ? Padding(
                      padding: const EdgeInsets.all(12),
                      child: Image.memory(_logo!,
                          key: const Key('company-logo-image'),
                          fit: BoxFit.contain),
                    )
                  : Text('Aucun logo',
                      key: const Key('company-logo-empty'),
                      style: TextStyle(color: cs.onSurfaceVariant)),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                key: const Key('company-logo-pick'),
                onPressed: _logoBusy ? null : _pickLogo,
                icon: const Icon(Icons.image_outlined),
                label: Text(_logo != null ? 'Remplacer' : 'Choisir un logo'),
              ),
            ),
            if (_logo != null) ...[
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  key: const Key('company-logo-remove'),
                  onPressed: _logoBusy ? null : _removeLogo,
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('Supprimer'),
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: 6),
        Text('PNG ou JPEG — l\'image est réduite automatiquement.',
            style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12)),
      ],
    );
  }
}
