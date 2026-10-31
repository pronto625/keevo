import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../domain/model/tutorial.dart';
import '../provider/help_provider.dart';

/// HelpTutorialsPage — « Aide & Tutoriels » (FR7, v1s-14-6), OWNER and
/// EMPLOYEE.
///
/// Lists short French videos (~2 min per module). Videos are hosted outside
/// Keevo and open in YouTube / the browser — the app embeds no player.
/// Tutorials are never mandatory.
class HelpTutorialsPage extends ConsumerWidget {
  const HelpTutorialsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final catalogAsync = ref.watch(tutorialsProvider);

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      appBar: AppBar(
        title: const Text('Aide & Tutoriels'),
        backgroundColor: theme.colorScheme.surface,
        foregroundColor: theme.colorScheme.onSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: catalogAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => AppErrorWidget(
          error: err,
          title: 'Tutoriels indisponibles',
          onRetry: () => ref.invalidate(tutorialsProvider),
        ),
        data: (catalog) => RefreshIndicator(
          onRefresh: () => ref.refresh(tutorialsProvider.future),
          child: _TutorialList(catalog: catalog),
        ),
      ),
    );
  }
}

class _TutorialList extends ConsumerWidget {
  final TutorialCatalog catalog;

  const _TutorialList({required this.catalog});

  Future<void> _open(BuildContext context, WidgetRef ref, Tutorial t) async {
    final messenger = ScaffoldMessenger.of(context);
    final online = await ref.read(connectivityServiceProvider).isOnline();
    if (!online) {
      messenger.showSnackBar(const SnackBar(
        content: Text('Connexion internet requise pour regarder la vidéo.'),
      ));
      return;
    }
    bool opened;
    try {
      opened = await launchUrl(Uri.parse(t.url.trim()),
          mode: LaunchMode.externalApplication);
    } catch (_) {
      opened = false;
    }
    if (!opened) {
      messenger.showSnackBar(const SnackBar(
        content: Text('Impossible d\'ouvrir la vidéo sur cet appareil.'),
      ));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final tutorials = catalog.tutorials;

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        if (catalog.fromCache) ...[
          Row(
            children: [
              Icon(Icons.cloud_off_rounded, size: 16, color: cs.onSurfaceVariant),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  'Hors ligne — liste enregistrée sur l\'appareil',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
        ],
        Text(
          'De courtes vidéos en français (environ 2 minutes) pour prendre '
          'Keevo en main. Elles s\'ouvrent dans YouTube ou votre navigateur.',
          style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: 16),
        if (tutorials.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 48),
            child: Column(
              children: [
                const SoftIconBadge(icon: Icons.ondemand_video_rounded, size: 72),
                const SizedBox(height: 12),
                Text('Les tutoriels arrivent bientôt',
                    style: theme.textTheme.titleMedium),
              ],
            ),
          )
        else
          Container(
            decoration: BoxDecoration(
              color: cs.surface,
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
              border: Border.all(color: cs.outlineVariant.withAlpha(90)),
            ),
            child: Column(
              children: [
                for (var i = 0; i < tutorials.length; i++) ...[
                  if (i > 0) const Divider(indent: 68, height: 1),
                  _TutorialTile(
                    tutorial: tutorials[i],
                    onTap: tutorials[i].isAvailable
                        ? () => _open(context, ref, tutorials[i])
                        : null,
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }
}

class _TutorialTile extends StatelessWidget {
  final Tutorial tutorial;
  final VoidCallback? onTap;

  const _TutorialTile({required this.tutorial, required this.onTap});

  static String _duration(int seconds) {
    if (seconds <= 0) return '';
    final minutes = (seconds / 60).round();
    return minutes <= 1 ? '~1 min' : '~$minutes min';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final available = tutorial.isAvailable;
    final duration = _duration(tutorial.durationSeconds);
    final subtitle = available
        ? [tutorial.module, if (duration.isNotEmpty) duration].join(' · ')
        : 'Bientôt disponible';

    return ListTile(
      enabled: available,
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: SoftIconBadge(
        icon: available
            ? Icons.play_circle_outline_rounded
            : Icons.hourglass_empty_rounded,
        color: available ? null : cs.onSurfaceVariant,
      ),
      title: Text(
        tutorial.title,
        style: const TextStyle(fontWeight: FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(subtitle),
          if (tutorial.description.trim().isNotEmpty)
            Text(
              tutorial.description.trim(),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
            ),
        ],
      ),
      trailing: available
          ? Icon(Icons.open_in_new_rounded,
              size: 18, color: cs.onSurfaceVariant)
          : null,
    );
  }
}
