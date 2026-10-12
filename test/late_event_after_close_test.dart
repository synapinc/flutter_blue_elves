import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_blue_elves/flutter_blue_elves.dart';
import 'package:flutter_test/flutter_test.dart';

// Native delivers events on the EventChannel asynchronously, so anything it had
// already queued arrives after Dart has closed the matching stream. Those late
// events must be dropped, not thrown (Crashlytics 704544f3: Bad state from
// _StreamController.add in _onToDart).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const codec = StandardMethodCodec();

  setUp(() {
    messenger.setMockMethodCallHandler(
        const MethodChannel('flutter_blue_elves/method'), (call) async => null);
    messenger.setMockMethodCallHandler(
        const MethodChannel('flutter_blue_elves/event'), (call) async => null);
  });

  Future<void> deliver(Map<String, Object?> event) async {
    await messenger.handlePlatformMessage('flutter_blue_elves/event',
        codec.encodeSuccessEnvelope(event), (_) {});
  }

  Map<String, Object?> scanResult(String id) => {
        'eventName': 'scanResult',
        'id': id,
        'name': 'unit',
        'localName': 'unit',
        'macAddress': id,
        'rssi': -50,
        'uuids': <String>[],
        'manufacturerSpecificData': <int, Uint8List>{},
        'scanRecord': Uint8List(0),
      };

  // The singleton's EventChannel listener runs in the zone that created it, so
  // create it once inside a zone that records what _onToDart throws.
  final errors = <Object>[];
  setUpAll(() {
    runZonedGuarded(() => FlutterBlueElves.instance, (e, _) => errors.add(e));
  });

  Future<List<Object>> errorsDuring(Future<void> Function() body) async {
    errors.clear();
    await body();
    await Future<void>.delayed(Duration.zero);
    return List.of(errors);
  }

  test('scan result arriving after stopScan is dropped', () async {
    final errors = await errorsDuring(() async {
      final elves = FlutterBlueElves.instance;
      final sub = elves.startScan(5000).listen((_) {});
      elves.stopScan();
      await deliver(scanResult('AA:BB'));
      await sub.cancel();
    });
    expect(errors, isEmpty);
  });

  test('scan result arriving after scanTimeout is dropped', () async {
    final errors = await errorsDuring(() async {
      final elves = FlutterBlueElves.instance;
      final sub = elves.startScan(5000).listen((_) {});
      await deliver({'eventName': 'scanTimeout'});
      await deliver(scanResult('AA:CC'));
      await sub.cancel();
    });
    expect(errors, isEmpty);
  });
}
