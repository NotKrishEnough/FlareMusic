import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'inner_tube_client.dart';

class LocalPlaylist {
  const LocalPlaylist({required this.id, required this.name, required this.tracks, this.sourceId});
  final String id;
  final String name;
  final List<OnlineTrack> tracks;
  final String? sourceId;

  Map<String, dynamic> toJson() => {
    'id': id, 'name': name, 'sourceId': sourceId,
    'tracks': tracks.map((t) => {'id': t.videoId, 'title': t.title, 'artist': t.artist, 'duration': t.duration, 'thumbnail': t.thumbnail}).toList(),
  };

  factory LocalPlaylist.fromJson(Map<String, dynamic> json) => LocalPlaylist(
    id: json['id'] as String,
    name: json['name'] as String? ?? 'Untitled playlist',
    sourceId: json['sourceId'] as String?,
    tracks: ((json['tracks'] as List?) ?? const []).whereType<Map>().map((t) => OnlineTrack(
      videoId: t['id']?.toString() ?? '',
      title: t['title']?.toString() ?? 'Unknown title',
      artist: t['artist']?.toString() ?? 'YouTube',
      duration: t['duration']?.toString() ?? '',
      thumbnail: t['thumbnail']?.toString() ?? '',
    )).where((t) => t.videoId.isNotEmpty).toList(),
  );
}

/// Device-local playlist library. Imported YouTube playlists are snapshots;
/// signed-in account sync is intentionally not represented as available.
class PlaylistLibrary {
  static const _key = 'flare.playlists.v1';

  Future<List<LocalPlaylist>> all() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_key);
    if (raw == null) return [];
    try {
      return (jsonDecode(raw) as List).whereType<Map>().map((e) => LocalPlaylist.fromJson(Map<String, dynamic>.from(e))).toList();
    } catch (_) {
      return [];
    }
  }

  Future<LocalPlaylist> create(String name) async {
    final clean = name.trim();
    if (clean.isEmpty) throw ArgumentError('Playlist name cannot be empty');
    final playlist = LocalPlaylist(id: DateTime.now().microsecondsSinceEpoch.toString(), name: clean, tracks: const []);
    await _save([playlist, ...await all()]);
    return playlist;
  }

  Future<LocalPlaylist> importYouTube(String playlistId, InnerTubeClient client, {String? name}) async {
    final tracks = await client.fetchPlaylist(playlistId);
    if (tracks.isEmpty) throw Exception('No public tracks found for that playlist.');
    final playlist = LocalPlaylist(id: DateTime.now().microsecondsSinceEpoch.toString(), name: name?.trim().isNotEmpty == true ? name!.trim() : 'Imported YouTube playlist', tracks: tracks, sourceId: playlistId);
    await _save([playlist, ...await all()]);
    return playlist;
  }

  Future<void> replaceSynced(List<LocalPlaylist> synced) async {
    final current = await all();
    final local = current.where((p) => !p.id.startsWith('yt:')).toList();
    await _save([...synced, ...local]);
  }

  Future<void> addTrack(String playlistId, OnlineTrack track) async {
    final lists = await all();
    final index = lists.indexWhere((p) => p.id == playlistId);
    if (index < 0) throw StateError('Playlist not found');
    final p = lists[index];
    if (p.tracks.any((t) => t.videoId == track.videoId)) return;
    lists[index] = LocalPlaylist(id: p.id, name: p.name, sourceId: p.sourceId, tracks: [...p.tracks, track]);
    await _save(lists);
  }

  Future<void> remove(String playlistId) async {
    final lists = await all()..removeWhere((p) => p.id == playlistId);
    await _save(lists);
  }

  Future<void> _save(List<LocalPlaylist> lists) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode(lists.map((p) => p.toJson()).toList()));
  }
}
