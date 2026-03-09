/// CategoryModel — Domain model for hierarchical categories.
///
/// Supports parent-child relationships via parentId (null = root category).
/// Mapped from backend CategoryResponseDto.
class CategoryModel {
  final String id;
  final String name;
  final String? parentId;  // null = root category, UUID = subcategory
  final bool isActive;
  final bool isCustom;  // false = template-seeded, true = merchant-created
  final DateTime createdAt;
  final DateTime updatedAt;

  const CategoryModel({
    required this.id,
    required this.name,
    this.parentId,  // null for root categories
    required this.isActive,
    required this.isCustom,
    required this.createdAt,
    required this.updatedAt,
  });

  /// Factory from backend API response
  factory CategoryModel.fromJson(Map<String, dynamic> json) {
    return CategoryModel(
      id: json['id'] as String,
      name: json['name'] as String,
      parentId: json['parentId'] as String?,
      isActive: json['isActive'] as bool,
      isCustom: json['isCustom'] as bool,
      createdAt: DateTime.parse(json['createdAt'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
    );
  }

  /// Check if this is a root category
  bool get isRoot => parentId == null;

  /// Display name for dropdowns
  @override
  String toString() => name;

  @override
  bool operator ==(Object other) =>
      other is CategoryModel && other.id == id;

  @override
  int get hashCode => id.hashCode;
}
