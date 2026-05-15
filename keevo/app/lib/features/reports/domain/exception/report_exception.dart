/// Domain exception thrown by the reports feature.
class ReportException implements Exception {
  final String domainCode;
  final String message;

  const ReportException({required this.domainCode, required this.message});

  @override
  String toString() => 'ReportException[$domainCode]: $message';
}
