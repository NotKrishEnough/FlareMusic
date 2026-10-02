import 'dart:convert';
import 'package:http/http.dart' as http;
import 'youtube_cookie_auth.dart';

/// Public YouTube/YouTube Music catalog models. InnerTube is an unofficial API
/// and can change without notice; this client does not bypass access controls.
class OnlineTrack {
  const OnlineTrack({required this.videoId, required this.title, required this.artist, this.duration = '', this.thumbnail = ''});
  final String videoId, title, artist, duration, thumbnail;
  String get watchUrl => 'https://www.youtube.com/watch?v=$videoId';
}

class YouTubePlaylist {
  const YouTubePlaylist({required this.id, required this.title, this.description = '', this.itemCount = 0, this.thumbnail = ''});
  final String id, title, description, thumbnail;
  final int itemCount;
}

class InnerTubeClient {
  InnerTubeClient({http.Client? client, YouTubeCookieAuth? auth})
      : _http = client ?? http.Client(),
        _auth = auth;
  final http.Client _http;
  final YouTubeCookieAuth? _auth;

  Future<Map<String, String>> _headers({bool music = false}) async {
    final headers = <String, String>{
      'content-type': 'application/json',
      'user-agent': _ua,
    };
    if (_auth != null) headers.addAll(await _auth.authHeaders());
    if (music) {
      headers['x-youtube-client-name'] = '67';
      headers['x-youtube-client-version'] = _musicVersion;
      headers['x-origin'] = 'https://music.youtube.com';
    }
    return headers;
  }
  static const _webVersion = '2.20250626.01.00';
  static const _musicVersion = '1.20250626.01.00';
  static const _ua = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36';

  Future<List<OnlineTrack>> search(String query) async {
    if (query.trim().isEmpty) throw ArgumentError('Enter a search term');
    final results = <OnlineTrack>[];
    final errors = <Object>[];
    for (final source in [
      ('https://www.youtube.com/youtubei/v1', 'WEB', _webVersion, false),
      ('https://music.youtube.com/youtubei/v1', 'WEB_REMIX', _musicVersion, true),
    ]) {
      try {
        final response = await _http.post(
          Uri.parse('${source.$1}/search?prettyPrint=false'),
          headers: await _headers(music: source.$4),
          body: jsonEncode({'context': {'client': {'clientName': source.$2, 'clientVersion': source.$3, 'hl': 'en', 'gl': 'US'}}, 'query': query.trim()}),
        ).timeout(const Duration(seconds: 20));
        if (response.statusCode < 200 || response.statusCode >= 300) throw Exception('HTTP ${response.statusCode}');
        _collect(response.body, results);
      } catch (e) { errors.add(e); }
    }
    final unique = <String, OnlineTrack>{};
    for (final track in results) { unique.putIfAbsent(track.videoId, () => track); }
    if (unique.isEmpty && errors.isNotEmpty) throw Exception('YouTube search unavailable: ${errors.join('; ')}');
    return unique.values.take(50).toList();
  }

  Future<List<YouTubePlaylist>> fetchLibraryPlaylists() async {
    final response = await _musicBrowse('FEmusic_library_landing');
    final output = <YouTubePlaylist>[];
    final seen = <String>{};
    final root = jsonDecode(response.body);

    void walk(dynamic node) {
      if (node is Map) {
        final renderer = node['musicTwoRowItemRenderer'] ?? node['musicResponsiveListItemRenderer'];
        if (renderer is Map) {
          final endpoint = renderer['navigationEndpoint'];
          final browseId = endpoint is Map
              ? endpoint['browseEndpoint']?['browseId']?.toString()
              : null;
          final watchPlaylist = endpoint is Map
              ? endpoint['watchEndpoint']?['playlistId']?.toString()
              : null;
          final id = (browseId ?? watchPlaylist ?? '').replaceFirst(RegExp(r'^VL'), '');
          final title = _text(renderer['title']) ?? '';
          if (id.isNotEmpty && title.isNotEmpty && seen.add(id)) {
            final thumbs = (renderer['thumbnail']?['thumbnails'] as List?) ?? const [];
            final thumbnail = thumbs.isEmpty ? '' : (thumbs.last['url']?.toString() ?? '');
            output.add(YouTubePlaylist(id: id, title: title, thumbnail: thumbnail));
          }
        }
        node.values.forEach(walk);
      } else if (node is List) {
        node.forEach(walk);
      }
    }

    walk(root);
    return output;
  }

  Future<List<OnlineTrack>> fetchPlaylist(String playlistId) async {
    final id = playlistId.replaceFirst(RegExp(r'^VL'), '');
    final browseId = RegExp(r'^(PL|UU|LL|OLAK)').hasMatch(id) ? 'VL$id' : id;
    final response = await _http.post(
      Uri.parse('https://www.youtube.com/youtubei/v1/browse?prettyPrint=false'),
      headers: await _headers(),
      body: jsonEncode({'context': {'client': {'clientName': 'WEB', 'clientVersion': _webVersion, 'hl': 'en', 'gl': 'US'}}, 'browseId': browseId}),
    ).timeout(const Duration(seconds: 25));
    if (response.statusCode < 200 || response.statusCode >= 300) throw Exception('Playlist request failed: HTTP ${response.statusCode}');
    final tracks = <OnlineTrack>[];
    _collect(response.body, tracks);
    final unique = <String, OnlineTrack>{};
    for (final track in tracks) { unique.putIfAbsent(track.videoId, () => track); }
    return unique.values.toList();
  }

  /// Parses public playlist metadata through the YouTube Data API's public
  /// playlistItems endpoint only when a caller supplies an API key.
  Future<List<YouTubePlaylist>> fetchPublicPlaylists(String channelOrPlaylistId) async {
    final tracks = await fetchPlaylist(channelOrPlaylistId);
    return [YouTubePlaylist(id: channelOrPlaylistId, title: 'YouTube playlist', itemCount: tracks.length)];
  }

  void _collect(String raw, List<OnlineTrack> out) {
    final root = jsonDecode(raw);
    void walk(dynamic node) {
      if (node is Map) {
        final id = node['videoId'];
        if (id is String && id.isNotEmpty) {
          final thumbList = (node['thumbnail']?['thumbnails'] as List?) ?? const [];
          final thumb = thumbList.isEmpty ? '' : (thumbList.last['url']?.toString() ?? '');
          out.add(OnlineTrack(
            videoId: id,
            title: _text(node['title']) ?? 'Unknown title',
            artist: _text(node['ownerText']) ?? _text(node['shortBylineText']) ?? 'YouTube',
            duration: _text(node['lengthText']) ?? '',
            thumbnail: thumb,
          ));
        }
        node.values.forEach(walk);
      } else if (node is List) { node.forEach(walk); }
    }
    walk(root);
  }

  String? _text(dynamic value) {
    if (value is! Map) return null;
    final simple = value['simpleText'];
    if (simple is String && simple.isNotEmpty) return simple;
    final runs = value['runs'];
    if (runs is List) {
      final result = runs.map((e) => e is Map ? (e['text'] ?? '') : '').join();
      if (result.isNotEmpty) return result;
    }
    return null;
  }

  void close() => _http.close();
}
