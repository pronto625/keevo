import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo/core/entitlements/entitlements.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';

/// Story 15.1 — [EntitlementsNotifier] with the device and the network
/// replaced: the seed is [seed], the server answer comes from [fetch] (throw to
/// simulate offline). Caching / retry logic stays the real one.
class FakeEntitlementsNotifier extends EntitlementsNotifier {
  final Future<Map<String, String>> Function() fetch;
  final Entitlements seed;
  int fetchCalls = 0;

  FakeEntitlementsNotifier(this.fetch, {this.seed = Entitlements.unknown});

  @override
  Future<Entitlements> loadSeed() async => seed;

  @override
  Future<bool> hasSession() async => true;

  @override
  Future<Map<String, String>> fetchStates() {
    fetchCalls++;
    return fetch();
  }

  @override
  Future<void> onServerAnswer(Entitlements fresh) async {}
}

/// Override answering [states] from the server.
Override entitlementsOverride(Map<String, String> states) =>
    entitlementsProvider
        .overrideWith(() => FakeEntitlementsNotifier(() async => states));
