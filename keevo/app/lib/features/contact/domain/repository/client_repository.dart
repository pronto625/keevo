import '../model/client_model.dart';

/// ClientRepository — abstract port for client persistence (Story 2.5).
abstract class ClientRepository {
  Future<List<ClientModel>> getAll({bool includeArchived = false});
  Future<List<ClientModel>> search(String query, {bool includeArchived = false});
  Future<ClientModel?> getById(String id);
  Future<ClientModel> create({
    required String name,
    required String phone,
    String? email,
    String? notes,
  });
  Future<ClientModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    String? notes,
  });
  Future<void> archive(String id);

  /// Restores an archived client (sync UNARCHIVE_CLIENT).
  Future<void> unarchive(String id);
  Future<void> syncFromRemote();
}
