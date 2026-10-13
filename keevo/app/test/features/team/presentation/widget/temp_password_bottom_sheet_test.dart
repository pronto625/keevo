import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/team/presentation/widget/temp_password_bottom_sheet.dart';

void main() {
  const channel = MethodChannel('plugins.flutter.io/url_launcher');
  final launched = <String>[];
  var launchResult = true;

  setUp(() {
    launched.clear();
    launchResult = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'launch') {
        launched.add((call.arguments as Map)['url'] as String);
        return launchResult;
      }
      return false;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> open(WidgetTester tester, {String? phone}) async {
    await tester.binding.setSurfaceSize(const Size(800, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showTempPasswordBottomSheet(
              context: context,
              employeeName: 'Jean Mbida',
              storeName: 'Boutique A',
              temporaryPassword: 'TmpPass12345',
              invitePhone: phone,
              firstName: 'Jean',
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('shouldOpenWhatsAppShareLinkForInvite — wa.me link with first '
      'name, store and phone, never the password', (tester) async {
    await open(tester, phone: '+237690000001');

    expect(find.text('TmpPass12345'), findsOneWidget);
    expect(find.textContaining('jamais inclus dans l\'invitation'),
        findsOneWidget);

    await tester.tap(find.text('Inviter via WhatsApp'));
    await tester.pumpAndSettle();

    expect(launched, hasLength(1));
    final uri = Uri.parse(launched.single);
    expect('${uri.scheme}://${uri.host}${uri.path}',
        'https://wa.me/237690000001');
    final text = uri.queryParameters['text']!;
    expect(text, contains('Jean'));
    expect(text, contains('Boutique A'));
    expect(text, contains('+237690000001'));
    expect(text, isNot(contains('TmpPass12345')));
    expect(launched.single, isNot(contains('TmpPass12345')));
  });

  testWidgets('launch failure → snackbar', (tester) async {
    launchResult = false;
    await open(tester, phone: '+237690000001');

    await tester.tap(find.text('Inviter via WhatsApp'));
    await tester.pumpAndSettle();

    expect(find.text('Impossible d\'ouvrir WhatsApp sur cet appareil.'),
        findsOneWidget);
  });

  testWidgets('no phone → no invite button', (tester) async {
    await open(tester);
    expect(find.text('Inviter via WhatsApp'), findsNothing);
  });
}
