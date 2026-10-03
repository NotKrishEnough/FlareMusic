import 'dart:async';

import 'package:youtube_explode_dart/youtube_explode_dart.dart' as yt;

/// FlareMusic stream resolver using the same extraction order as DA-Tunes:
/// VISIONOS, Android, then Android SDK-less; prefer MP4 audio and only use a
/// title/artist search fallback when the original video cannot be extracted.
class YouTubeStreamResolver {
  YouTubeStreamResolver() : _youtube = yt.YoutubeExplode();

  final yt.YoutubeExplode _youtube;
  final Map<String, List<_StreamCandidate>> _cache = {};
  final Map<String, Set<String>> _failed = {};
  final Map<String, String> _lastUrl = {};

  static const yt.YoutubeApiClient _visionOsClient = yt.YoutubeApiClient({
    'context': {
      'client': {
        'clientName': 'VISIONOS',
        'clientVersion': '1.02',
        'deviceMake': 'Apple',
        'deviceModel': 'RealityDevice17,1',
        'userAgent':
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) '
            'AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
        'osName': 'visionOS',
        'osVersion': '26.5.23O471',
        'hl': 'en',
        'gl': 'US',
        'utcOffsetMinutes': 0,
      }
    }
  }, 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false');

  static const List<yt.YoutubeApiClient> _clients = [
    _visionOsClient,
    yt.YoutubeApiClient.android,
    yt.YoutubeApiClient.androidSdkless,
  ];

  Future<String> resolve(String videoId) async {
    var candidates = await _getCandidates(videoId);
    final failed = _failed[videoId] ?? <String>{};
    for (final candidate in candidates) {
      if (candidate.expired || failed.contains(candidate.url)) continue;
      _lastUrl[videoId] = candidate.url;
      return candidate.url;
    }

    // Refresh once after a rejected URL. If direct extraction still returns
    // only failed URLs, allow the DA-Tunes title/artist fallback to run.
    _cache.remove(videoId);
    candidates = await _extract(videoId, allowSearchFallback: true);
    for (final candidate in candidates) {
      if (candidate.expired || (_failed[videoId]?.contains(candidate.url) ?? false)) continue;
      _lastUrl[videoId] = candidate.url;
      return candidate.url;
    }
    throw StateError('DA-Tunes extraction found no new audio-only stream for $videoId.');
  }

  Future<void> invalidate(String videoId, {String? failedUrl}) async {
    final url = failedUrl ?? _lastUrl[videoId];
    if (url != null && url.isNotEmpty) {
      _failed.putIfAbsent(videoId, () => <String>{}).add(url);
    }
    _cache.remove(videoId);
  }

  Future<List<_StreamCandidate>> _getCandidates(String videoId) async {
    final cached = _cache[videoId];
    if (cached != null && cached.any((item) => !item.expired)) {
      return cached.where((item) => !item.expired).toList();
    }
    return _extract(videoId, allowSearchFallback: false);
  }

  Future<List<_StreamCandidate>> _extract(
    String videoId, {
    required bool allowSearchFallback,
  }) async {
    final result = <_StreamCandidate>[];
    final seen = <String>{};

    Future<void> addManifest(String id) async {
      for (final client in _clients) {
        try {
          final manifest = await _youtube.videos.streamsClient
              .getManifest(
                id,
                ytClients: [client],
                requireWatchPage: false,
              )
              .timeout(const Duration(seconds: 18));
          final streams = manifest.audioOnly
              .where((stream) => stream.url.toString().startsWith('https://'))
              .toList();
          // Match DA-Tunes: prefer MP4/M4A audio when available, then use
          // the highest bitrate within that set.
          var preferred = streams
              .where((stream) => stream.container.name.toLowerCase() == 'mp4')
              .toList();
          if (preferred.isEmpty) preferred = streams;
          preferred.sort((a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond));
          for (final stream in preferred.take(2)) {
            final url = stream.url.toString();
            if (seen.add(url)) {
              result.add(_StreamCandidate(url, _expiryFromUrl(url)));
            }
          }
          if (result.isNotEmpty) return;
        } catch (_) {
          // Try the next client profile.
        }
      }
    }

    await addManifest(videoId);
    if (result.isEmpty && allowSearchFallback) {
      await _addSearchFallback(videoId, result, seen);
    }
    _cache[videoId] = result;
    return result;
  }

  Future<void> _addSearchFallback(
    String originalId,
    List<_StreamCandidate> output,
    Set<String> seen,
  ) async {
    try {
      final original = await _youtube.videos.get(originalId).timeout(const Duration(seconds: 10));
      final query = '${original.author} ${original.title}'
          .replaceAll(RegExp(r'\s+'), ' ')
          .trim();
      if (query.isEmpty) return;
      final results = await _youtube.search.searchContent(query).timeout(const Duration(seconds: 12));
      final videos = results.whereType<yt.SearchVideo>().toList();
      final selected = _rankFallback(
        videos,
        originalId: originalId,
        title: original.title,
        artist: original.author,
        duration: original.duration ?? Duration.zero,
      );
      if (selected == null) return;

      for (final client in _clients) {
        try {
          final manifest = await _youtube.videos.streamsClient
              .getManifest(
                selected.id.value,
                ytClients: [client],
                requireWatchPage: false,
              )
              .timeout(const Duration(seconds: 18));
          var streams = manifest.audioOnly
              .where((stream) => stream.url.toString().startsWith('https://'))
              .toList();
          final mp4 = streams.where((stream) => stream.container.name.toLowerCase() == 'mp4').toList();
          if (mp4.isNotEmpty) streams = mp4;
          streams.sort((a, b) => b.bitrate.bitsPerSecond.compareTo(a.bitrate.bitsPerSecond));
          for (final stream in streams.take(2)) {
            final url = stream.url.toString();
            if (seen.add(url)) output.add(_StreamCandidate(url, _expiryFromUrl(url)));
          }
          if (output.isNotEmpty) return;
        } catch (_) {}
      }
    } catch (_) {
      // Keep the original extraction error as the final user-visible failure.
    }
  }

  yt.SearchVideo? _rankFallback(
    List<yt.SearchVideo> candidates, {
    required String originalId,
    required String title,
    required String artist,
    required Duration duration,
  }) {
    final cleanTitle = _normalize(title);
    final cleanArtist = _normalize(artist.replaceAll(' - Topic', '').replaceAll('VEVO', ''));
    if (cleanTitle.isEmpty || cleanArtist.isEmpty) return null;

    yt.SearchVideo? best;
    var bestScore = -999999;
    for (final candidate in candidates) {
      if (candidate.id.value == originalId) continue;
      final candidateTitle = _normalize(candidate.title);
      final candidateArtist = _normalize(candidate.author.replaceAll(' - Topic', '').replaceAll('VEVO', ''));
      final artistMatch = candidateArtist == cleanArtist ||
          candidateArtist.contains(cleanArtist) ||
          cleanArtist.contains(candidateArtist);
      final titleMatch = candidateTitle == cleanTitle ||
          candidateTitle.contains(cleanTitle) ||
          cleanTitle.contains(candidateTitle);
      if (!artistMatch || !titleMatch) continue;

      var score = 0;
      score += candidateArtist == cleanArtist ? 2000 : 1000;
      score += candidateTitle == cleanTitle ? 2000 : 1000;
      final candidateDuration = _parseDuration(candidate.duration);
      final diff = (candidateDuration.inSeconds - duration.inSeconds).abs();
      if (duration > Duration.zero) {
        if (diff <= 3) {
          score += 1200;
        } else if (diff <= 8) {
          score += 600;
        } else if (diff > 30) {
          score -= 1500;
        }
      }
      final lowerTitle = candidate.title.toLowerCase();
      if (lowerTitle.contains('remix') && !title.toLowerCase().contains('remix')) score -= 3000;
      if (lowerTitle.contains('cover') || lowerTitle.contains('karaoke') ||
          lowerTitle.contains('instrumental') || lowerTitle.contains('fan-made')) {
        score -= 3000;
      }
      if (candidate.author.endsWith(' - Topic')) score += 1500;
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return bestScore >= 400 ? best : null;
  }

  String _normalize(String value) => value
      .toLowerCase()
      .replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '')
      .replaceAll('&', 'and')
      .replaceAll(RegExp(r'[^a-z0-9]'), '');

  Duration _parseDuration(String? value) {
    if (value == null || value.isEmpty) return Duration.zero;
    final parts = value.split(':').map(int.tryParse).toList();
    if (parts.any((part) => part == null)) return Duration.zero;
    if (parts.length == 3) {
      return Duration(hours: parts[0]!, minutes: parts[1]!, seconds: parts[2]!);
    }
    if (parts.length == 2) {
      return Duration(minutes: parts[0]!, seconds: parts[1]!);
    }
    return Duration.zero;
  }

  DateTime? _expiryFromUrl(String url) {
    try {
      final seconds = int.tryParse(Uri.parse(url).queryParameters['expire'] ?? '');
      return seconds == null ? null : DateTime.fromMillisecondsSinceEpoch(seconds * 1000);
    } catch (_) {
      return null;
    }
  }

  Future<void> close() async {
    _cache.clear();
    _failed.clear();
    _lastUrl.clear();
    _youtube.close();
  }
}

class _StreamCandidate {
  const _StreamCandidate(this.url, this.expiresAt);
  final String url;
  final DateTime? expiresAt;
  bool get expired => expiresAt != null && !expiresAt!.isAfter(DateTime.now());
}
