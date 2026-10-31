import 'package:flutter/material.dart';

import 'app_error_widget.dart';

/// French message when a module's code cannot be loaded.
const kDeferredLoadFailedMessage =
    'Cet écran n\'a pas pu être chargé. Réessayez.';

/// Story 15.3 — builds a widget of a `deferred as` library once [loader]
/// (its `loadLibrary`) has completed: a spinner meanwhile, the French error
/// widget (with retry) if loading fails.
class DeferredWidget extends StatefulWidget {
  final Future<void> Function() loader;
  final WidgetBuilder builder;

  const DeferredWidget(
      {super.key, required this.loader, required this.builder});

  @override
  State<DeferredWidget> createState() => _DeferredWidgetState();
}

class _DeferredWidgetState extends State<DeferredWidget> {
  late Future<void> _loading = widget.loader();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: _loading,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return Scaffold(
            appBar: AppBar(),
            body: const Center(child: CircularProgressIndicator()),
          );
        }
        if (snapshot.hasError) {
          return Scaffold(
            appBar: AppBar(),
            body: AppErrorWidget(
              error: snapshot.error!,
              // The loader's own text is technical (library / URI).
              message: kDeferredLoadFailedMessage,
              onRetry: () => setState(() {
                _loading = widget.loader();
              }),
            ),
          );
        }
        return widget.builder(context);
      },
    );
  }
}
