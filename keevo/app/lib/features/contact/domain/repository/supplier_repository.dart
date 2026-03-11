import '../model/supplier_model.dart';

/// SupplierRepository — abstract port for supplier persistence (Story 2.5).
abstract class SupplierRepository {
  Future<List<SupplierModel>> getAll({bool includeArchived = false});
  Future<List<SupplierModel>> search(String query);
  Future<SupplierModel?> getById(String id);
  Future<SupplierModel> create({
    required String name,
    required String phone,
    String? email,
    List<String> productIds = const [],
  });
  Future<SupplierModel> update({
    required String id,
    String? name,
    String? phone,
    String? email,
    List<String>? productIds,
  });
  Future<void> archive(String id);
  Future<void> syncFromRemote();
}
