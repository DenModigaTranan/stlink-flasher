import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';

class StLinkDevice {
  final String name;
  final int vid;
  final int pid;
  const StLinkDevice(this.name, this.vid, this.pid);
}

class TargetInfo {
  final int coreId;
  final int devId;
  final int revId;
  final int flashKb;
  final int pageSize;
  final bool readProtected;

  const TargetInfo({
    required this.coreId,
    required this.devId,
    required this.revId,
    required this.flashKb,
    required this.pageSize,
    required this.readProtected,
  });

  factory TargetInfo.fromMap(Map m) => TargetInfo(
        coreId: m['coreId'] as int,
        devId: m['devId'] as int,
        revId: m['revId'] as int,
        flashKb: m['flashKb'] as int,
        pageSize: m['pageSize'] as int,
        readProtected: m['readProtected'] as bool,
      );

  @override
  String toString() =>
      'devId=0x${devId.toRadixString(16)} rev=0x${revId.toRadixString(16)} '
      'flash=${flashKb}KB rdp=$readProtected';
}

class DiagStep {
  final String name;
  final bool ok;
  final String detail;
  const DiagStep(this.name, this.ok, this.detail);
}

class FlashProgress {
  /// erase | write | verify | read | unlock | reset
  final String stage;
  final int percent;
  const FlashProgress(this.stage, this.percent);
}

class StLinkException implements Exception {
  final String message;
  const StLinkException(this.message);
  @override
  String toString() => message;
}

/// Dart side of the Android ST-Link plugin (see android/StLinkFlasherPlugin.kt).
class StLinkFlasher {
  static const _method = MethodChannel('stlink_flasher');
  static const _events = EventChannel('stlink_flasher/progress');

  static Stream<FlashProgress> get progress => _events
      .receiveBroadcastStream()
      .map((e) => FlashProgress(e['stage'] as String, e['percent'] as int));

  static Future<T> _call<T>(String method, [Map<String, dynamic>? args]) async {
    try {
      final r = await _method.invokeMethod<T>(method, args);
      return r as T;
    } on PlatformException catch (e) {
      throw StLinkException(e.message ?? e.code);
    }
  }

  static Future<List<StLinkDevice>> listDevices() async {
    final l = await _call<List<dynamic>>('listDevices');
    return l
        .map((d) => StLinkDevice(d['name'] as String, d['vid'] as int, d['pid'] as int))
        .toList();
  }

  /// Asks for USB permission if needed, then opens the probe and enters SWD mode.
  ///
  /// [underReset] holds the target in reset while attaching. Use it when the scooter
  /// firmware disables the SWD pins; it needs the ST-Link NRST pin wired to the controller.
  static Future<void> connect({String? deviceName, bool underReset = false}) =>
      _call<Map<dynamic, dynamic>>('connect', {
        'name': deviceName,
        'underReset': underReset,
      });

  static Future<TargetInfo> targetInfo() async =>
      TargetInfo.fromMap(await _call<Map<dynamic, dynamic>>('targetInfo'));

  /// Non-destructive bring-up check (never writes flash). Run this first on new hardware.
  static Future<List<DiagStep>> diagnose() async {
    final l = await _call<List<dynamic>>('diagnose');
    return l
        .map((d) => DiagStep(d['name'] as String, d['ok'] as bool, d['detail'] as String))
        .toList();
  }

  /// Reads flash into memory. Defaults to the full flash size. Fails if the chip is read-protected.
  static Future<Uint8List> backup({int? length}) =>
      _call<Uint8List>('backup', {'length': length});

  static Future<void> flash(
    Uint8List image, {
    bool verify = true,
    bool skipImageCheck = false,
  }) =>
      _call<bool>('flash', {
        'data': image,
        'verify': verify,
        'skipImageCheck': skipImageCheck,
      });

  static Future<void> eraseAll() => _call<bool>('eraseAll');

  /// Destroys the stock firmware. Power-cycle the target afterwards.
  static Future<void> removeReadProtection({required bool confirm}) =>
      _call<bool>('removeReadProtection', {'confirm': confirm});

  static Future<void> disconnect() => _call<bool>('disconnect');
}
