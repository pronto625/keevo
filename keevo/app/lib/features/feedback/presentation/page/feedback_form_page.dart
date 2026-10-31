import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import '../../data/service/screenshot_compressor.dart';
import '../../domain/model/feedback_submission.dart';
import '../../domain/model/feedback_type.dart';
import '../provider/feedback_provider.dart';

/// FeedbackFormPage — "Signaler un problème" (Story 14.5, FR92 — AC14/AC15).
///
/// Route: /settings/feedback?from=<path> (OWNER + EMPLOYEE).
/// Full page (no dialog): type selector, description (10–500 chars, live
/// counter), optional screenshot (camera/gallery, compressed to ≤ 500 KB,
/// base64 in the payload), automatic context (screen, app version, platform).
/// Submission is queued in `sync_queue` (`SUBMIT_FEEDBACK`) so it works
/// offline; a SnackBar confirms and the form resets.
class FeedbackFormPage extends ConsumerStatefulWidget {
  /// Path of the screen the user came from, captured at the tap (AC14) —
  /// not the path of this page.
  final String? screenContext;

  const FeedbackFormPage({super.key, this.screenContext});

  @override
  ConsumerState<FeedbackFormPage> createState() => _FeedbackFormPageState();
}

class _FeedbackFormPageState extends ConsumerState<FeedbackFormPage> {
  static const _defaultType = FeedbackType.bug;

  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _descriptionController;
  FeedbackType _type = _defaultType;
  Uint8List? _screenshot;
  bool _processingScreenshot = false;
  bool _submitting = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _descriptionController = TextEditingController()
      ..addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  // ── Screenshot ──────────────────────────────────────────────────────────

  Future<void> _chooseScreenshotSource() async {
    final cs = Theme.of(context).colorScheme;
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            ListTile(
              leading: const SoftIconBadge(
                  icon: Icons.photo_camera_outlined, color: AppTheme.iconBlue),
              title: Text('Prendre une photo',
                  style: TextStyle(color: cs.onSurface)),
              onTap: () => Navigator.pop(ctx, ImageSource.camera),
            ),
            ListTile(
              leading: const SoftIconBadge(
                  icon: Icons.photo_library_outlined,
                  color: AppTheme.iconTeal),
              title: Text('Choisir dans la galerie',
                  style: TextStyle(color: cs.onSurface)),
              onTap: () => Navigator.pop(ctx, ImageSource.gallery),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (source == null || !mounted) return;
    await _pickScreenshot(source);
  }

  Future<void> _pickScreenshot(ImageSource source) async {
    setState(() {
      _processingScreenshot = true;
      _error = null;
    });
    try {
      final picked = await ref.read(imagePickerProvider).pickImage(
            source: source,
            maxWidth: 1280,
            maxHeight: 1280,
            imageQuality: 85,
          );
      if (picked == null) return;
      final compressed =
          await ScreenshotCompressor.compress(await picked.readAsBytes());
      if (!mounted) return;
      setState(() => _screenshot = compressed);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _processingScreenshot = false);
    }
  }

  // ── Submit ──────────────────────────────────────────────────────────────

  String? _validateDescription(String? value) {
    final length = (value ?? '').trim().length;
    if (length < FeedbackSubmission.minDescriptionLength) {
      return 'Décrivez votre retour en au moins '
          '${FeedbackSubmission.minDescriptionLength} caractères';
    }
    if (length > FeedbackSubmission.maxDescriptionLength) {
      return '${FeedbackSubmission.maxDescriptionLength} caractères maximum';
    }
    return null;
  }

  String? _currentScreenContext() {
    if (widget.screenContext != null) return widget.screenContext;
    // Fallback when opened without `?from=`: the current path (never `.name`,
    // routes are unnamed in this router).
    if (GoRouter.maybeOf(context) == null) return null;
    return GoRouterState.of(context).uri.path;
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    final screenshot = _screenshot;
    final submission = FeedbackSubmission(
      type: _type,
      description: _descriptionController.text.trim(),
      appVersion: ref.read(appVersionProvider),
      platform: feedbackPlatform(),
      screenContext: _currentScreenContext(),
      screenshotBase64: screenshot == null ? null : base64Encode(screenshot),
    );
    try {
      await ref.read(feedbackRepositoryProvider).submit(submission);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Merci pour votre retour ! Nous l\'examinerons rapidement.'),
      ));
      _reset();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _reset() {
    // Clear, then reset the validation once the field has rebuilt with the
    // empty text: clearing after a reset re-ran « au moins 10 caractères » on
    // the emptied field (device test), and a reset before the rebuild would
    // restore the previous text.
    _descriptionController.clear();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _formKey.currentState?.reset();
    });
    setState(() {
      _type = _defaultType;
      _screenshot = null;
    });
  }

  // ── UI ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final length = _descriptionController.text.trim().length;
    final busy = _submitting || _processingScreenshot;

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      appBar: AppBar(
        title: const Text('Votre avis'),
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: cs.onSurface,
        ),
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          onPressed: () => context.pop(),
        ),
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ── Type ────────────────────────────────────────────────
              const _SectionLabel('Type de retour'),
              const SizedBox(height: 8),
              Row(
                children: [
                  for (final type in FeedbackType.values) ...[
                    Expanded(
                      child: _TypeCard(
                        type: type,
                        selected: _type == type,
                        onTap: busy ? null : () => setState(() => _type = type),
                      ),
                    ),
                    if (type != FeedbackType.values.last)
                      const SizedBox(width: 8),
                  ],
                ],
              ),
              const SizedBox(height: 24),

              // ── Description ─────────────────────────────────────────
              const _SectionLabel('Description'),
              const SizedBox(height: 8),
              TextFormField(
                controller: _descriptionController,
                enabled: !busy,
                minLines: 5,
                maxLines: 10,
                maxLength: FeedbackSubmission.maxDescriptionLength,
                textCapitalization: TextCapitalization.sentences,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                decoration: InputDecoration(
                  hintText: _type == FeedbackType.bug
                      ? 'Que s\'est-il passé ? Qu\'attendiez-vous ?'
                      : 'Dites-nous tout…',
                  alignLabelWithHint: true,
                  counterText:
                      '$length / ${FeedbackSubmission.maxDescriptionLength}',
                ),
                validator: _validateDescription,
              ),
              const SizedBox(height: 16),

              // ── Screenshot ──────────────────────────────────────────
              const _SectionLabel('Capture d\'écran (optionnel)'),
              const SizedBox(height: 8),
              if (_screenshot != null)
                _ScreenshotPreview(
                  bytes: _screenshot!,
                  onRemove:
                      busy ? null : () => setState(() => _screenshot = null),
                )
              else
                OutlinedButton.icon(
                  onPressed: busy ? null : _chooseScreenshotSource,
                  icon: _processingScreenshot
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.add_photo_alternate_outlined),
                  label: Text(_processingScreenshot
                      ? 'Préparation de l\'image…'
                      : 'Ajouter une capture d\'écran'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusM),
                    ),
                  ),
                ),
              const SizedBox(height: 16),

              // ── Context ─────────────────────────────────────────────
              _ContextLine(
                screenContext: _currentScreenContext(),
                appVersion: ref.watch(appVersionProvider),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                AppErrorInline(error: _error!),
              ],
              const SizedBox(height: 24),

              // ── Submit ──────────────────────────────────────────────
              FilledButton.icon(
                onPressed: busy ? null : _submit,
                icon: _submitting
                    ? SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: cs.onSurfaceVariant,
                        ),
                      )
                    : const Icon(Icons.send_rounded),
                label: const Text('Envoyer'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Envoyé dès que vous êtes en ligne — vous pouvez rédiger '
                'votre retour hors connexion.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12, color: cs.muted),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Private widgets ─────────────────────────────────────────────────────────

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

class _TypeCard extends StatelessWidget {
  final FeedbackType type;
  final bool selected;
  final VoidCallback? onTap;
  const _TypeCard({
    required this.type,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Semantics(
      button: true,
      selected: selected,
      label: type.label,
      child: Material(
        color: selected ? AppTheme.softTint(theme.brightness) : cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
              border: Border.all(
                color: selected ? cs.primary : cs.outlineVariant,
                width: selected ? 1.5 : 1,
              ),
            ),
            child: Column(
              children: [
                Icon(type.icon,
                    size: 24, color: selected ? cs.primary : cs.muted),
                const SizedBox(height: 8),
                Text(
                  type.label,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    color: selected ? cs.primary : cs.onSurface,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ScreenshotPreview extends StatelessWidget {
  final Uint8List bytes;
  final VoidCallback? onRemove;
  const _ScreenshotPreview({required this.bytes, required this.onRemove});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final kb = (bytes.length / 1024).round();
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
        boxShadow: AppTheme.softShadow(cs),
      ),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(AppTheme.radiusS),
            child: Image.memory(
              bytes,
              width: 56,
              height: 56,
              fit: BoxFit.cover,
              gaplessPlayback: true,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'Capture jointe · $kb Ko',
              style: TextStyle(fontSize: 13, color: cs.onSurface),
            ),
          ),
          IconButton(
            tooltip: 'Retirer la capture',
            icon: Icon(Icons.close_rounded, color: cs.muted),
            onPressed: onRemove,
          ),
        ],
      ),
    );
  }
}

class _ContextLine extends StatelessWidget {
  final String? screenContext;
  final String? appVersion;
  const _ContextLine({required this.screenContext, required this.appVersion});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // The raw route and platform code are sent, never shown (no technical
    // data in the UI): « Écran : /settings · ANDROID » became readable text.
    final platform = feedbackPlatform();
    final parts = <String>[
      if (screenContext != null) 'écran actuel',
      if (appVersion != null) 'version $appVersion',
      platform == 'IOS'
          ? 'iPhone'
          : platform == 'ANDROID'
              ? 'Android'
              : 'autre appareil',
    ];
    return Row(
      children: [
        Icon(Icons.info_outline_rounded, size: 14, color: cs.dimmed),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            'Contexte envoyé automatiquement — ${parts.join(' · ')}',
            style: TextStyle(fontSize: 11, color: cs.dimmed),
          ),
        ),
      ],
    );
  }
}
