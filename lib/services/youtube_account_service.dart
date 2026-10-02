import 'dart:convert';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:http/http.dart' as http;
import 'inner_tube_client.dart';
import 'playlist_library.dart';

class YouTubeAccountService {
  YouTubeAccountService()
      : _google = GoogleSignIn(
          scopes: const ['https://www.googleapis.com/auth/youtube.readonly'],
          serverClientId: const String.fromEnvironment('GOOGLE_WEB_CLIENT_ID'),
        );

  final GoogleSignIn _google;
  GoogleSignInAccount? get currentUser => _google.currentUser;

  Future<GoogleSignInAccount?> signIn() async {
    final user = await _google.signIn();
    if (user == null) return null;
    return user;
  }

  Future<void> signOut() => _google.signOut();

  Future<List<LocalPlaylist>> syncPlaylists() async {
    final user = _google.currentUser;
    if (user == null) throw StateError('Sign in with Google first.');
    final auth = await user.authentication;
    final token = auth.accessToken;
    if (token == null || token.isEmpty) throw StateError('Google did not provide an access token. Check OAuth configuration.');
    final output = <LocalPlaylist>[];
    String? page;
    do {
      final uri = Uri.https('www.googleapis.com', '/youtube/v3/playlists', {
        'part': 'snippet,contentDetails', 'mine': 'true', 'maxResults': '50',
        if (page != null) 'pageToken': page,
      });
      final response = await http.get(uri, headers: {'authorization': 'Bearer $token'});
      if (response.statusCode != 200) throw Exception('YouTube playlist sync failed (${response.statusCode}): ${response.body}');
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      for (final raw in (data['items'] as List? ?? const [])) {
        if (raw is! Map) continue;
        final id = raw['id']?.toString() ?? '';
        final snippet = raw['snippet'] as Map? ?? const {};
        if (id.isEmpty) continue;
        final title = snippet['title']?.toString() ?? 'YouTube playlist';
        final thumbMap = snippet['thumbnails'] as Map? ?? const {};
        final thumb = ((thumbMap['medium'] ?? thumbMap['default']) as Map?)?['url']?.toString() ?? '';
        output.add(LocalPlaylist(id: 'yt:$id', name: title, sourceId: id, tracks: const []));
        // Playlist entries are loaded lazily by the library screen to avoid
        // large syncs and API quota use during sign-in.
        _thumbnailCache[id] = thumb;
      }
      page = data['nextPageToken']?.toString();
    } while (page != null && page.isNotEmpty);
    return output;
  }

  final Map<String, String> _thumbnailCache = {};
}
