import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// FlareMusic playback resolver.
///
/// YouTube media URLs are short-lived playback leases. We therefore use
/// several InnerTube playback clients, validate URLs before handing them to
/// the decoder, track failed candidates, and fall back to native extraction.
class YouTubeStreamResolver {
  YouTubeStreamResolver({http.Client? client}) : _http = client ?? http.Client();

  final http.Client _http;
  final YoutubeExplode _youtube = YoutubeExplode();
  static const _native = MethodChannel('flare_music/native_resolver');
  static const _musicApiKey = 'AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30';
  static const _browserUa =
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
      'Chrome/125.0.0.0 Mobile Safari/537.36';

  static const _clients = <_PlayerClient>[
    _PlayerClient(
      name: 'WEB_REMIX',
      id: '67',
      version: '1.20260202.01.00',
      host: 'music.youtube.com',
      origin: 'https://music.youtube.com',
    ),
    _PlayerClient(
      name: 'ANDROID_VR',
      id: '28',
      version: '1.65.10',
      host: 'www.youtube.com',
      origin: 'https://www.youtube.com',
      userAgent:
          'com.google.android.apps.youtube.vr.oculus/1.65.10 '
          '(Linux; U; Android 14) gzip',
    ),
    _PlayerClient(
      name: 'IOS',
      id: '5',
      version: '21.03.2',
      host: 'www.youtube.com',
      origin: 'https://www.youtube.com',
      userAgent:
          'com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_3 like Mac OS X)',
    ),
    _PlayerClient(
      name: 'ANDROID_MUSIC',
      id: '21',
      version: '5.34.51',
      host: 'music.youtube.com',
      origin: 'https://music.youtube.com',
    ),
    _PlayerClient(
      name: 'WEB',
      id: '1',
      version: '2.20260205.01.00',
      host: 'www.youtube.com',
      origin: 'https://www.youtube.com',
    ),
  ];

  final Map<String, List<_Candidate>> _candidateCache = <String, List<_Candidate>>{};
  final Map<String, Set<String>> _failedUrls = <String, Set<String>>{};
  final Map<String, String> _lastResolvedUrl = <String, String>{};
  // Avoid duplicate extraction when the player and preloader request the same
  // track together. Failed clients cool down briefly instead of being retried
  // for every track in a queue.
  final Map<String, Future<List<_Candidate>>> _inFlight = {};
  final Map<String, DateTime> _clientCooldownUntil = {};
  static const _cacheSafetyWindow = Duration(seconds: 60);
  static const _clientCooldown = Duration(minutes: 2);

  Future<String> resolve(String videoId) async {
    final candidates = await _candidatesFor(videoId);
    final failed = _failedUrls[videoId] ?? <String>{};

    for (final candidate in candidates) {
      if (failed.contains(candidate.url)) continue;
      if (_isExpired(candidate)) continue;
      // Validate the signed URL with the same browser identity used by the
      // audio source. YouTube can return an apparently valid player response
      // whose CDN URL is already forbidden; don't hand that URL to just_audio
      // and waste all retry attempts on it.
      final status = await _probeCandidate(candidate.url);
      if (status != null && (status == 401 || status == 403 || status == 404 || status == 410)) {
        _failedUrls.putIfAbsent(videoId, () => <String>{}).add(candidate.url);
        continue;
      }
      _failedUrls.putIfAbsent(videoId, () => <String>{});
      _lastResolvedUrl[videoId] = candidate.url;
      return candidate.url;
    }

    // Refresh once, but never silently clear the failed-URL blacklist: doing
    // so can make every retry return the same rejected URL.
    _candidateCache.remove(videoId);
    final fresh = await _candidatesFor(videoId);
    for (final candidate in fresh) {
      if (_failedUrls[videoId]?.contains(candidate.url) ?? false) continue;
      if (_isExpired(candidate)) continue;
      _lastResolvedUrl[videoId] = candidate.url;
      return candidate.url;
    }
    throw StateError('No new playable YouTube audio stream was found.');
  }

  Future<void> invalidate(String videoId, {String? failedUrl}) async {
    final urlToFail = failedUrl ?? _lastResolvedUrl[videoId];
    if (urlToFail != null && urlToFail.isNotEmpty) {
      _failedUrls.putIfAbsent(videoId, () => <String>{}).add(urlToFail);
    }
    _candidateCache.remove(videoId);
    try {
      if (Platform.isAndroid) {
        await _native.invokeMethod<void>(
          'invalidate',
          <String, dynamic>{'videoId': videoId},
        );
      }
    } catch (_) {}
  }

  Future<List<_Candidate>> _candidatesFor(String videoId) async {
    final cached = _candidateCache[videoId];
    if (cached != null && cached.any((item) => !_isExpired(item))) {
      return cached.where((item) => !_isExpired(item)).toList();
    }
    final pending = _inFlight[videoId];
    if (pending != null) return pending;
    final work = _extractCandidates(videoId);
    _inFlight[videoId] = work;
    try {
      return await work;
    } finally {
      if (identical(_inFlight[videoId], work)) _inFlight.remove(videoId);
    }
  }

  Future<List<_Candidate>> _extractCandidates(String videoId) async {

    final candidates = <_Candidate>[];
    final seen = <String>{};

    Future<void> addCandidate(String? url, {DateTime? expiresAt}) async {
      if (url == null || url.trim().isEmpty) return;
      final normalized = url.trim();
      if (!normalized.startsWith('https://')) return;
      if (!seen.add(normalized)) return;
      if (!_looksLikeMediaUrl(normalized)) return;
      // Do not probe a signed googlevideo URL with a separate HTTP client.
      // The probe can be rejected due to request identity even when the
      // platform decoder can play it, and it can consume the signed URL.
      candidates.add(_Candidate(
        normalized,
        expiresAt ?? _expiryFromUrl(normalized),
      ));
    }

    // Native NewPipe is the primary Android extractor. It handles
    // YouTube's signature/SABR changes and owns its short-lived URL cache.
    if (Platform.isAndroid) {
      try {
        final url = await _native
            .invokeMethod<String>(
              'resolve',
              <String, dynamic>{'videoId': videoId},
            )
            .timeout(const Duration(seconds: 30));
        await addCandidate(url);
      } catch (_) {}
    }

    // Use several first-party InnerTube playback client profiles, following
    // the same fallback idea used by modern Android music clients.
    for (final client in _clients) {
      final cooldown = _clientCooldownUntil[client.name];
      if (cooldown != null && cooldown.isAfter(DateTime.now())) continue;
      try {
        final response = await _player(videoId, client);
        if (response == null) continue;
        final root = jsonDecode(response);
        if (root is! Map) continue;
        final streaming = root['streamingData'];
        if (streaming is! Map) continue;

        final expiresSeconds =
            int.tryParse(streaming['expiresInSeconds']?.toString() ?? '');
        final expiry = expiresSeconds == null
            ? null
            : DateTime.now().add(Duration(seconds: expiresSeconds));

        final rawAdaptive = streaming['adaptiveFormats'];
        final rawFormats = streaming['formats'];
        final formats = <Map>[];
        if (rawAdaptive is List) {
          formats.addAll(rawAdaptive.whereType<Map>());
        }
        if (rawFormats is List) {
          formats.addAll(rawFormats.whereType<Map>());
        }
        formats.sort((a, b) {
          final aAudio = (a['mimeType']?.toString() ?? '').startsWith('audio/') ? 1 : 0;
          final bAudio = (b['mimeType']?.toString() ?? '').startsWith('audio/') ? 1 : 0;
          if (aAudio != bAudio) return bAudio.compareTo(aAudio);
          final aRate = int.tryParse(a['bitrate']?.toString() ?? '') ?? 0;
          final bRate = int.tryParse(b['bitrate']?.toString() ?? '') ?? 0;
          return bRate.compareTo(aRate);
        });

        for (final format in formats) {
          final mime = format['mimeType']?.toString() ?? '';
          if (!mime.startsWith('audio/')) continue;
          final url = format['url']?.toString();
          if (url == null || url.isEmpty) continue;
          await addCandidate(url, expiresAt: expiry);
          if (candidates.length >= 3) break;
        }
        _clientCooldownUntil.remove(client.name);
        if (candidates.length >= 3) break;
      } catch (_) {
        _clientCooldownUntil[client.name] = DateTime.now().add(_clientCooldown);
      }
    }

    // Final cross-platform fallback.
    final ytClients = <YoutubeApiClient>[
      YoutubeApiClient.ios,
      YoutubeApiClient.safari,
      YoutubeApiClient.tv,
      YoutubeApiClient.mediaConnect,
      YoutubeApiClient.androidSdkless,
    ];
    for (final client in ytClients) {
      try {
        final manifest = await _youtube.videos.streamsClient
            .getManifest(
              videoId,
              ytClients: <YoutubeApiClient>[client],
              requireWatchPage: false,
            )
            .timeout(const Duration(seconds: 20));

        final streams = manifest.audioOnly
            .where((stream) => stream.url.toString().startsWith('https://'))
            .toList()
          ..sort((a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond));
        for (final stream in streams.take(2)) {
          await addCandidate(stream.url.toString());
        }
        if (candidates.length >= 4) break;
      } catch (_) {}
    }

    _candidateCache[videoId] = candidates;
    return candidates;
  }

  Future<String?> _player(String videoId, _PlayerClient client) async {
    final endpoint =
        'https://${client.host}/youtubei/v1/player'
        '?key=$_musicApiKey&prettyPrint=false';
    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'User-Agent': client.userAgent,
      'Origin': client.origin,
      'Referer': '${client.origin}/',
      'X-Youtube-Client-Name': client.id,
      'X-Youtube-Client-Version': client.version,
    };
    final body = <String, dynamic>{
      'context': <String, dynamic>{
        'client': <String, dynamic>{
          'clientName': client.name,
          'clientVersion': client.version,
          'hl': 'en',
          'gl': 'US',
        },
      },
      'videoId': videoId,
      'contentCheckOk': true,
      'racyCheckOk': true,
      'playbackContext': <String, dynamic>{
        'contentPlaybackContext': <String, dynamic>{
          'html5Preference': 'HTML5_PREF_WANTS',
        },
      },
    };
    if (client.name == 'WEB_REMIX' || client.name == 'ANDROID_MUSIC') {
      body['context']['client']['musicAppInfo'] = <String, dynamic>{
        'musicActivityMasterSwitch': 'MUSIC_ACTIVITY_MASTER_SWITCH_ENABLED',
        'musicLocationMasterSwitch': 'MUSIC_LOCATION_MASTER_SWITCH_ENABLED',
      };
    }
    final response = await _http
        .post(Uri.parse(endpoint), headers: headers, body: jsonEncode(body))
        .timeout(const Duration(seconds: 15));
    if (response.statusCode < 200 || response.statusCode >= 300) return null;
    return response.body;
  }

  /// Makes a one-byte range request to catch expired/forbidden CDN URLs
  /// before handing them to the player. Returns null when the probe itself
  /// cannot establish a status, so transient probe failures don't block play.
  Future<int?> _probeCandidate(String url) async {
    try {
      final request = http.Request('GET', Uri.parse(url))
        ..followRedirects = true
        ..maxRedirects = 5
        ..headers.addAll(<String, String>{
          'User-Agent': _browserUa,
          'Referer': 'https://www.youtube.com/',
          'Origin': 'https://www.youtube.com',
          'Accept': '*/*',
          'Range': 'bytes=0-0',
        });
      final response = await _http.send(request).timeout(const Duration(seconds: 8));
      final status = response.statusCode;
      await response.stream.listen((_) {}).cancel();
      return status;
    } catch (_) {
      return null;
    }
  }

  bool _looksLikeMediaUrl(String url) =>
      url.contains('googlevideo.com') || url.contains('youtube.com');

  bool _isExpired(_Candidate candidate) {
    final expiry = candidate.expiresAt;
    return expiry != null &&
        !expiry.isAfter(DateTime.now().add(_cacheSafetyWindow));
  }

  DateTime? _expiryFromUrl(String url) {
    try {
      final value = int.tryParse(Uri.parse(url).queryParameters['expire'] ?? '');
      if (value == null) return null;
      return DateTime.fromMillisecondsSinceEpoch(value * 1000);
    } catch (_) {
      return null;
    }
  }

  Future<void> preload(String videoId) async {
    try {
      await resolve(videoId);
    } catch (_) {}
  }

  Future<void> close() async {
    _candidateCache.clear();
    _failedUrls.clear();
    _lastResolvedUrl.clear();
    _inFlight.clear();
    _clientCooldownUntil.clear();
    _http.close();
    _youtube.close();
  }
}

class _Candidate {
  const _Candidate(this.url, this.expiresAt);
  final String url;
  final DateTime? expiresAt;
}

class _PlayerClient {
  const _PlayerClient({
    required this.name,
    required this.id,
    required this.version,
    required this.host,
    required this.origin,
    this.userAgent = YouTubeStreamResolver._browserUa,
  });
  final String name;
  final String id;
  final String version;
  final String host;
  final String origin;
  final String userAgent;
}