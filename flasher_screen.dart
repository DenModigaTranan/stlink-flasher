import 'dart:async';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import 'stlink_flasher.dart';

/// Backup-first flashing flow for an ST-Link on USB OTG.
/// Needs `file_picker` in pubspec.yaml (^8.0.0).
class FlasherScreen extends StatefulWidget {
  const FlasherScreen({super.key});

  @override
  State<FlasherScreen> createState() => _FlasherScreenState();
}

class _FlasherScreenState extends State<FlasherScreen> {
  StreamSubscription<FlashProgress>? _sub;

  bool _connected = false;
  bool _busy = false;
  bool _underReset = false;
  bool _backedUp = false;
  bool _noBackupAck = false;

  TargetInfo? _info;
  String? _fwName;
  Uint8List? _fw;
  FlashProgress? _progress;
  final List<String> _log = [];

  @override
  void initState() {
    super.initState();
    _sub = StLinkFlasher.progress.listen(
      (p) => setState(() => _progress = p),
      onError: (_) {},
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    StLinkFlasher.disconnect().catchError((_) {});
    super.dispose();
  }

  // ---------- helpers ----------

  void _say(String s) {
    if (!mounted) return;
    setState(() => _log.insert(0, s));
  }

  /// Runs [action] with the busy flag set and turns failures into log lines.
  Future<bool> _run(String label, Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _progress = null;
    });
    try {
      await action();
      _say('$label: done');
      return true;
    } on StLinkException catch (e) {
      _say('$label failed: ${e.message}');
      return false;
    } catch (e) {
      _say('$label failed: $e');
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _confirm(String title, String body, String action) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(action)),
        ],
      ),
    );
    return ok ?? false;
  }

  String _hex(int v) => '0x${v.toRadixString(16).toUpperCase()}';

  // ---------- actions ----------

  Future<void> _connect() async {
    final ok = await _run('Connect', () async {
      await StLinkFlasher.connect(underReset: _underReset);
      final info = await StLinkFlasher.targetInfo();
      setState(() => _info = info);
      _say('Target ${_hex(info.devId)}, ${info.flashKb} KB flash'
          '${info.readProtected ? ', READ-PROTECTED' : ''}');
    });
    if (mounted) setState(() => _connected = ok);
  }

  Future<void> _disconnect() async {
    await _run('Disconnect', StLinkFlasher.disconnect);
    if (!mounted) return;
    setState(() {
      _connected = false;
      _info = null;
      _backedUp = false;
    });
  }

  Future<void> _diagnose() async {
    await _run('Diagnostics', () async {
      final steps = await StLinkFlasher.diagnose();
      // _say puts the newest line on top, so add in reverse to read top-down.
      for (final s in steps.reversed) {
        _say('${s.ok ? 'PASS' : 'FAIL'}  ${s.name}: ${s.detail}');
      }
    });
  }

  Future<void> _backup() async {
    await _run('Backup', () async {
      final data = await StLinkFlasher.backup();
      final name = 'scooter_backup_${DateTime.now().millisecondsSinceEpoch}.bin';
      final saved = await FilePicker.platform.saveFile(
        dialogTitle: 'Save firmware backup',
        fileName: name,
        bytes: data,
      );
      if (saved == null) {
        throw const StLinkException('Backup was read but not saved. Pick a location to keep it.');
      }
      if (mounted) setState(() => _backedUp = true);
      _say('Backup saved (${data.length} bytes)');
    });
  }

  Future<void> _pickFirmware() async {
    final res = await FilePicker.platform.pickFiles(withData: true);
    final f = res?.files.single;
    if (f == null || f.bytes == null) return;
    setState(() {
      _fw = f.bytes;
      _fwName = f.name;
    });
    _say('Firmware selected: ${f.name} (${f.bytes!.length} bytes)');
  }

  Future<void> _flash() async {
    final info = _info!;
    final ok = await _confirm(
      'Flash ${_fwName ?? 'firmware'}?',
      'This erases ${((_fw!.length + info.pageSize - 1) ~/ info.pageSize)} flash pages and writes '
          '${_fw!.length} bytes. Keep the cable firm and the phone charged. '
          'An interrupted flash leaves the controller unbootable until reflashed.',
      'Flash',
    );
    if (!ok) return;
    await _run('Flash', () => StLinkFlasher.flash(_fw!));
  }

  Future<void> _removeRdp() async {
    final ok = await _confirm(
      'Remove read protection?',
      'This ERASES the stock firmware permanently and cannot be undone. '
          'Afterwards power-cycle the scooter, then reconnect.',
      'Erase everything',
    );
    if (!ok) return;
    final done = await _run('Remove read protection', () async {
      await StLinkFlasher.removeReadProtection(confirm: true);
    });
    if (done) {
      _say('Power-cycle the scooter now, then connect again.');
      await _disconnect();
    }
  }

  // ---------- UI ----------

  @override
  Widget build(BuildContext context) {
    final info = _info;
    final p = _progress;
    final canFlash = _connected &&
        !_busy &&
        _fw != null &&
        info != null &&
        !info.readProtected &&
        (_backedUp || _noBackupAck);

    return Scaffold(
      appBar: AppBar(title: const Text('ST-Link flasher')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (!_connected) ...[
            CheckboxListTile(
              value: _underReset,
              onChanged: _busy ? null : (v) => setState(() => _underReset = v ?? false),
              title: const Text('Connect under reset'),
              subtitle: const Text('For firmware that disables SWD. Needs NRST wired.'),
              contentPadding: EdgeInsets.zero,
            ),
            FilledButton.icon(
              onPressed: _busy ? null : _connect,
              icon: const Icon(Icons.usb),
              label: const Text('Connect ST-Link'),
            ),
          ] else ...[
            Card(
              child: ListTile(
                title: Text('Chip ${_hex(info!.devId)}  ·  ${info.flashKb} KB flash'),
                subtitle: Text(info.readProtected
                    ? 'Read-protected: backup and flashing are blocked'
                    : 'Readable and writable'),
                trailing: TextButton(onPressed: _busy ? null : _disconnect, child: const Text('Disconnect')),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: _busy ? null : _diagnose,
              icon: const Icon(Icons.bug_report),
              label: const Text('Run diagnostics (read-only)'),
            ),
            const SizedBox(height: 12),
            if (info.readProtected)
              FilledButton.tonalIcon(
                onPressed: _busy ? null : _removeRdp,
                icon: const Icon(Icons.lock_open),
                label: const Text('Remove read protection (erases stock firmware)'),
              )
            else ...[
              Text('1. Back up', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              FilledButton.tonalIcon(
                onPressed: _busy ? null : _backup,
                icon: Icon(_backedUp ? Icons.check_circle : Icons.save_alt),
                label: Text(_backedUp ? 'Backup saved' : 'Read and save flash'),
              ),
              const SizedBox(height: 16),
              Text('2. Choose firmware', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              OutlinedButton.icon(
                onPressed: _busy ? null : _pickFirmware,
                icon: const Icon(Icons.folder_open),
                label: Text(_fwName ?? 'Pick .bin file'),
              ),
              if (!_backedUp)
                CheckboxListTile(
                  value: _noBackupAck,
                  onChanged: _busy ? null : (v) => setState(() => _noBackupAck = v ?? false),
                  title: const Text("Flash without a backup"),
                  subtitle: const Text("I understand the stock firmware can't be restored."),
                  contentPadding: EdgeInsets.zero,
                ),
              const SizedBox(height: 16),
              Text('3. Flash', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              FilledButton.icon(
                onPressed: canFlash ? _flash : null,
                icon: const Icon(Icons.bolt),
                label: const Text('Flash'),
              ),
            ],
          ],
          if (_busy || p != null) ...[
            const SizedBox(height: 20),
            LinearProgressIndicator(value: p == null ? null : p.percent / 100),
            const SizedBox(height: 4),
            Text(p == null ? 'Working…' : '${p.stage} ${p.percent}%'),
          ],
          const SizedBox(height: 20),
          for (final line in _log) Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Text(line)),
        ],
      ),
    );
  }
}
