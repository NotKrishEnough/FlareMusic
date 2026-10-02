import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

class YouTubeCookieAuth {
  static const _storageKey = 'flare.youtube.music.cookies';
  static const _origin = 'https://music.youtube.com';
  static const _clientVersion = '1.20260304.03.00';
  static const _apiKey = 'AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30';

  final FlutterSecureStorage _storage = const FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  String? _cookies;
  String? get cookies => _cookies;
  bool get isLoggedIn => _cookies != null && _cookies!.isNotEmpty;

  /// Headers for authenticated YouTube/YouTube Music InnerTube requests.
  /// Cookie values are never logged or exposed to the UI.
  Future<Map<String, String>> authHeaders() async {
    if (!isLoggedIn) return const {};
    return _authHeaders(_cookies!);
  }

  Future<void> restore() async {
    _cookies = await _storage.read(key: _storageKey);
  }

  Future<bool> loginWithCookies(String cookieHeader) async {
    final clean = cookieHeader.trim();
    if (clean.isEmpty) return false;
    final valid = await _verify(clean);
    if (!valid) return false;
    await _storage.write(key: _storageKey, value: clean);
    _cookies = clean;
    return true;
  }

  Future<bool> verifyCurrent() async {
    if (!isLoggedIn) return false;
    final valid = await _verify(_cookies!);
    if (!valid) {
      await logout();
    }
    return valid;
  }

  Future<void> logout() async {
    _cookies = null;
    await _storage.delete(key: _storageKey);
  }

  Future<bool> _verify(String cookies) async {
    try {
      final headers = _authHeaders(cookies);
      final response = await http.post(
        Uri.parse('$_origin/youtubei/v1/browse?key=$_apiKey&prettyPrint=false'),
        headers: headers,
        body: jsonEncode({
          'context': {
            'client': {
              'clientName': 'WEB_REMIX',
              'clientVersion': _clientVersion,
              'hl': 'en',
              'gl': 'US',
            },
          },
          'browseId': 'FEmusic_home',
        }),
      ).timeout(const Duration(seconds: 12));
      if (response.statusCode != 200) return false;
      final body = jsonDecode(response.body);
      if (body is! Map || body.containsKey('error')) return false;

      final tracking = body['responseContext']?['serviceTrackingParams'];
      if (tracking is List) {
        for (final service in tracking) {
          final params = service is Map ? service['params'] : null;
          if (params is List) {
            for (final item in params) {
              if (item is Map && item['key'] == 'logged_in' && item['value'] == '1') {
                return true;
              }
            }
          }
        }
      }

      return body.toString().contains('musicAccountMenuRenderer') ||
          body.toString().contains('activeAccountHeaderRenderer');
    } catch (_) {
      return false;
    }
  }

  Map<String, String> _authHeaders(String cookies) {
    final map = <String, String>{};
    for (final part in cookies.split(';')) {
      final i = part.indexOf('=');
      if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim();
    }

    final headers = <String, String>{
      'Cookie': cookies,
      'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36',
      'x-youtube-client-name': '67',
      'x-youtube-client-version': _clientVersion,
      'x-origin': _origin,
      'Content-Type': 'application/json',
    };

    final sapisid = map['SAPISID'] ?? map['__Secure-3PAPISID'];
    if (sapisid != null && sapisid.isNotEmpty) {
      final timestamp = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      final digest = sha1.convert(utf8.encode('$timestamp $sapisid $_origin'));
      headers['Authorization'] = 'SAPISIDHASH ${timestamp}_${digest.toString()}';
    }
    return headers;
  }
}
