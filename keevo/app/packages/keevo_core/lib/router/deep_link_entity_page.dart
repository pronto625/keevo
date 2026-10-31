import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// N3 — an edit route opened from a deep link (notification, push) carries
/// only the id, not the entity in `extra`. This page loads the entity from
/// the local database and opens its form; if it is not on the device, it
/// shows the list page with a French snackbar instead of silently falling
/// back to the list.
class DeepLinkEntityPage<T extends Object> extends ConsumerStatefulWidget {
  /// Loads the entity from the local database (null when absent).
  final Future<T?> Function(WidgetRef ref) load;

  /// The form opened on the loaded entity.
  final Widget Function(T entity) formBuilder;

  /// Shown when the entity is not on the device (usually the list page).
  final Widget fallback;

  /// French message of the snackbar shown with [fallback].
  final String notFoundMessage;

  const DeepLinkEntityPage({
    required this.load,
    required this.formBuilder,
    required this.fallback,
    required this.notFoundMessage,
    super.key,
  });

  @override
  ConsumerState<DeepLinkEntityPage<T>> createState() =>
      _DeepLinkEntityPageState<T>();
}

class _DeepLinkEntityPageState<T extends Object>
    extends ConsumerState<DeepLinkEntityPage<T>> {
  late final Future<T?> _future = _safeLoad();
  bool _notified = false;

  Future<T?> _safeLoad() async {
    try {
      return await widget.load(ref);
    } catch (_) {
      return null; // unreadable local row = not available on this device
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<T?>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        final entity = snapshot.data;
        if (entity != null) return widget.formBuilder(entity);
        if (!_notified) {
          _notified = true;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            ScaffoldMessenger.maybeOf(context)?.showSnackBar(
              SnackBar(
                content: Text(widget.notFoundMessage),
                behavior: SnackBarBehavior.floating,
              ),
            );
          });
        }
        return widget.fallback;
      },
    );
  }
}
