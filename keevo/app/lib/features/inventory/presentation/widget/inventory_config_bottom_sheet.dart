import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../catalog/presentation/provider/category_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../provider/inventory_session_provider.dart';

/// InventoryConfigBottomSheet — configure and start an inventory session.
///
/// Allows selecting:
/// - Target store (dropdown)
/// - Scope: FULL or PARTIAL
/// - Categories (multi-select chips, only for PARTIAL)
///
/// Story 6.1.
class InventoryConfigBottomSheet extends ConsumerStatefulWidget {
  const InventoryConfigBottomSheet({super.key});

  @override
  ConsumerState<InventoryConfigBottomSheet> createState() =>
      _InventoryConfigBottomSheetState();
}

class _InventoryConfigBottomSheetState
    extends ConsumerState<InventoryConfigBottomSheet> {
  String? _selectedStoreId;
  String _scope = 'FULL';
  final Set<String> _selectedCategoryIds = {};

  @override
  void initState() {
    super.initState();
    // Pre-select active store if available
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final activeStoreId = ref.read(activeStoreIdProvider);
      if (activeStoreId != null) {
        setState(() => _selectedStoreId = activeStoreId);
      }
    });
  }

  bool get _isValid {
    if (_selectedStoreId == null) return false;
    if (_scope == 'PARTIAL' && _selectedCategoryIds.isEmpty) return false;
    return true;
  }

  Future<void> _startSession() async {
    if (!_isValid) return;
    try {
      await ref.read(createSessionNotifierProvider.notifier).create(
            storeId: _selectedStoreId!,
            scope: _scope,
            categoryIds:
                _scope == 'PARTIAL' ? _selectedCategoryIds.toList() : null,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erreur: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final storesAsync = ref.watch(storeListNotifierProvider);
    final categoriesAsync = ref.watch(categoriesProvider);
    final createState = ref.watch(createSessionNotifierProvider);

    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: MediaQuery.of(context).viewInsets.bottom + 16,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Header ──
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: theme.colorScheme.outline.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'Nouvelle session d\'inventaire',
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 24),

            // ── Store selector ──
            Text('Boutique', style: theme.textTheme.labelLarge),
            const SizedBox(height: 8),
            storesAsync.when(
              data: (stores) => DropdownButtonFormField<String>(
                initialValue: _selectedStoreId,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  hintText: 'Sélectionner une boutique',
                ),
                items: stores
                    .where((s) => s.isActive)
                    .map((s) => DropdownMenuItem(
                          value: s.id,
                          child: Text(s.name),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _selectedStoreId = v),
              ),
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text('Erreur: $e'),
            ),
            const SizedBox(height: 16),

            // ── Scope selector ──
            Text('Périmètre', style: theme.textTheme.labelLarge),
            const SizedBox(height: 8),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'FULL', label: Text('Complet')),
                ButtonSegment(value: 'PARTIAL', label: Text('Partiel')),
              ],
              selected: {_scope},
              onSelectionChanged: (set) => setState(() {
                _scope = set.first;
                if (_scope == 'FULL') _selectedCategoryIds.clear();
              }),
            ),
            const SizedBox(height: 16),

            // ── Category chips (PARTIAL only) ──
            if (_scope == 'PARTIAL') ...[
              Text('Catégories', style: theme.textTheme.labelLarge),
              const SizedBox(height: 8),
              categoriesAsync.when(
                data: (categories) => Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: categories
                      .map((cat) => FilterChip(
                            label: Text(cat.name),
                            selected:
                                _selectedCategoryIds.contains(cat.id),
                            onSelected: (selected) {
                              setState(() {
                                if (selected) {
                                  _selectedCategoryIds.add(cat.id);
                                } else {
                                  _selectedCategoryIds.remove(cat.id);
                                }
                              });
                            },
                          ))
                      .toList(),
                ),
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => Text('Erreur: $e'),
              ),
              const SizedBox(height: 16),
            ],

            // ── Start button ──
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed:
                    _isValid && createState is! AsyncLoading ? _startSession : null,
                icon: createState is AsyncLoading
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.play_arrow),
                label: const Text('Démarrer l\'inventaire'),
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

/// Show the inventory config bottom sheet.
Future<bool?> showInventoryConfigBottomSheet(BuildContext context) {
  return showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (_) => const InventoryConfigBottomSheet(),
  );
}
