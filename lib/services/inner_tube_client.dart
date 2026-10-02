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
  static const _musicVersion = '1.20260304.03.00';
  static const _ua = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36';
  static const _musicApiKey = 'AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30';

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

  Future<String?> fetchLyrics(String videoId) async {
    final response = await _http.post(
      Uri.parse('https://music.youtube.com/youtubei/v1/next?key=$_musicApiKey&prettyPrint=false'),
      headers: await _headers(music: true),
      body: jsonEncode({
        'context': {
          'client': {
            'clientName': 'WEB_REMIX',
            'clientVersion': _musicVersion,
            'hl': 'en',
            'gl': 'US',
          },
        },
        'videoId': videoId,
        'enablePersistentPlaylistPanel': true,
        'isAudioOnly': true,
        'tunerSettingValue': 'AUTOMIX_SETTING_NORMAL',
      }),
    ).timeout(const Duration(seconds: 20));
    if (response.statusCode < 200 || response.statusCode >= 300) return null;

    final root = jsonDecode(response.body);
    String? lyricsBrowseId;

    void findLyrics(dynamic node) {
      if (lyricsBrowseId != null) return;
      if (node is Map) {
        final tab = node['tabRenderer'];
        if (tab is Map) {
          final endpoint = tab['endpoint'];
          if (endpoint is Map) {
            final browse = endpoint['browseEndpoint'];
            if (browse is Map) {
              final id = browse['browseId']?.toString();
              if (id != null && id.startsWith('MPLYt')) {
                lyricsBrowseId = id;
                return;
              }
            }
          }
        }
        node.values.forEach(findLyrics);
      } else if (node is List) {
        node.forEach(findLyrics);
      }
    }

    findLyrics(root);
    if (lyricsBrowseId == null) return null;

    final lyricsResponse = await _http.post(
      Uri.parse('https://music.youtube.com/youtubei/v1/browse?key=$_musicApiKey&prettyPrint=false'),
      headers: await _headers(music: true),
      body: jsonEncode({
        'context': {
          'client': {
            'clientName': 'WEB_REMIX',
            'clientVersion': _musicVersion,
            'hl': 'en',
            'gl': 'US',
          },
        },
        'browseId': lyricsBrowseId,
      }),
    ).timeout(const Duration(seconds: 20));
    if (lyricsResponse.statusCode < 200 || lyricsResponse.statusCode >= 300) return null;

    final lyricsRoot = jsonDecode(lyricsResponse.body);
    String? lyrics;

    void findLyricsText(dynamic node) {
      if (lyrics != null) return;
      if (node is Map) {
        final shelf = node['musicDescriptionShelfRenderer'];
        if (shelf is Map) {
          final description = _text(shelf['description']);
          if (description != null && description.trim().isNotEmpty) {
            lyrics = description.trim();
            return;
          }
        }
        node.values.forEach(findLyricsText);
      } else if (node is List) {
        node.forEach(findLyricsText);
      }
    }

    findLyricsText(lyricsRoot);
    return lyrics;
  }

  Future<List<OnlineTrack>> fetchHome() async {
    final response = await _musicBrowse('FEmusic_home');
    return _uniqueTracks(response.body);
  }

  Future<List<OnlineTrack>> fetchLikedSongs() async {
    final response = await _musicBrowse('FEmusic_liked_videos');
    return _uniqueTracks(response.body);
  }

  Future<http.Response> _musicBrowse(String browseId) async {
    if (_auth == null || !_auth.isLoggedIn) {
      throw StateError('Sign in with YouTube Music first.');
    }
    final response = await _http.post(
      Uri.parse('https://music.youtube.com/youtubei/v1/browse?key=$_musicApiKey&prettyPrint=false'),
      headers: await _headers(music: true),
      body: jsonEncode({
        'context': {
          'client': {
            'clientName': 'WEB_REMIX',
            'clientVersion': _musicVersion,
            'hl': 'en',
            'gl': 'US',
          },
        },
        'browseId': browseId,
      }),
    ).timeout(const Duration(seconds: 20));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception('YouTube Music request failed: HTTP ${response.statusCode}');
    }
    return response;
  }

  List<OnlineTrack> _uniqueTracks(String raw) {
    final tracks = <OnlineTrack>[];
    _collect(raw, tracks);
    final unique = <String, OnlineTrack>{};
    for (final track in tracks) {
      unique.putIfAbsent(track.videoId, () => track);
    }
    return unique.values.take(100).toList();
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
          final browseEndpoint = endpoint is Map ? endpoint['browseEndpoint'] : null;
          final browseId = browseEndpoint is Map ? browseEndpoint['browseId']?.toString() : null;
          final watchEndpoint = endpoint is Map ? endpoint['watchEndpoint'] : null;
          final watchPlaylist = watchEndpoint is Map ? watchEndpoint['playlistId']?.toString() : null;
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

    String? textFrom(dynamic value) => _text(value);

    String? thumbnailFrom(Map node) {
      final direct = node['thumbnail'];
      if (direct is Map) {
        final list = direct['thumbnails'];
        if (list is List && list.isNotEmpty) {
          final last = list.last;
          if (last is Map && last['url'] != null) {
            return _highResThumbnail(last['url'].toString());
          }
        }
      }
      return null;
    }

    String? musicFlexText(Map renderer, int index) {
      final columns = renderer['flexColumns'];
      if (columns is! List || index >= columns.length) return null;
      final column = columns[index];
      if (column is! Map) return null;
      final flex = column['musicResponsiveListItemFlexColumnRenderer'];
      if (flex is! Map) return null;
      return textFrom(flex['text']);
    }

    void walk(dynamic node) {
      if (node is Map) {
        final responsive = node['musicResponsiveListItemRenderer'];
        if (responsive is Map) {
          final data = responsive['playlistItemData'];
          final endpoint = responsive['navigationEndpoint'];
          final watch = endpoint is Map ? endpoint['watchEndpoint'] : null;
          final videoId = data is Map && data['videoId'] != null
              ? data['videoId'].toString()
              : watch is Map && watch['videoId'] != null
                  ? watch['videoId'].toString()
                  : null;
          if (videoId != null && videoId.isNotEmpty) {
            final title = musicFlexText(responsive, 0) ?? 'Unknown title';
            final artist = musicFlexText(responsive, 1) ?? 'YouTube Music';
            final duration = musicFlexText(responsive, 2) ?? '';
            final thumb = thumbnailFrom(responsive) ?? '';
            out.add(OnlineTrack(
              videoId: videoId,
              title: title,
              artist: artist,
              duration: duration,
              thumbnail: thumb,
            ));
          }
        }

        final twoRow = node['musicTwoRowItemRenderer'];
        if (twoRow is Map) {
          final endpoint = twoRow['navigationEndpoint'];
          final watch = endpoint is Map ? endpoint['watchEndpoint'] : null;
          final videoId = watch is Map ? watch['videoId']?.toString() : null;
          if (videoId != null && videoId.isNotEmpty) {
            out.add(OnlineTrack(
              videoId: videoId,
              title: textFrom(twoRow['title']) ?? 'Unknown title',
              artist: textFrom(twoRow['subtitle']) ?? 'YouTube Music',
              thumbnail: thumbnailFrom(twoRow) ?? '',
            ));
          }
        }

        final standardId = node['videoId'];
        if (standardId is String && standardId.isNotEmpty) {
          final thumbList = (node['thumbnail']?['thumbnails'] as List?) ?? const [];
          final thumb = thumbList.isEmpty ? '' : (thumbList.last['url']?.toString() ?? '');
          out.add(OnlineTrack(
            videoId: standardId,
            title: textFrom(node['title']) ?? 'Unknown title',
            artist: textFrom(node['ownerText']) ?? textFrom(node['shortBylineText']) ?? 'YouTube',
            duration: textFrom(node['lengthText']) ?? '',
            thumbnail: thumb,
          ));
        }

        node.values.forEach(walk);
      } else if (node is List) {
        node.forEach(walk);
      }
    }

    walk(root);
  }

  String _highResThumbnail(String url) {
    if (url.isEmpty) return url;
    if (url.contains('i.ytimg.com/vi/')) {
      final match = RegExp(r'/vi/([A-Za-z0-9_-]{11})/').firstMatch(url);
      if (match != null) {
        return 'https://i.ytimg.com/vi/${match.group(1)}/maxresdefault.jpg';
      }
    }
    return url.replaceFirst(
      RegExp(r'=w\\d+-h\\d+[^?]*$'),
      '=w1000-h1000',
    );
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
