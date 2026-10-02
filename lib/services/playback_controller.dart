import 'dart:async';
import 'package:just_audio/just_audio.dart';
import 'package:audio_service/audio_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'inner_tube_client.dart';
import 'youtube_stream_resolver.dart';

/// Owns the queue and audio engine for the Flutter app. Stream URLs are
/// resolved by the caller; YouTube extraction is deliberately not faked here.
class PlaybackController {
  PlaybackController({AudioPlayer? player}) {
    if (player != null) this.player = player;
  }
  late final AudioPlayer player;
  bool _initialized = false;

  Future<void> initialize() async {
    if (_initialized) return;
    player = AudioPlayer(userAgent: 'FlareMusic/1.0 (Android)');
    _initialized = true;
  }
  final YouTubeStreamResolver resolver = YouTubeStreamResolver();
  final List<OnlineTrack> queue = [];
  int index = -1;

  OnlineTrack? get current => index >= 0 && index < queue.length ? queue[index] : null;

  Future<void> restore() async {
    if (!_initialized) return;
    final prefs = await SharedPreferences.getInstance();
    final ids = prefs.getStringList('flare.queue.ids') ?? const [];
    final titles = prefs.getStringList('flare.queue.titles') ?? const [];
    final artists = prefs.getStringList('flare.queue.artists') ?? const [];
    final thumbnails = prefs.getStringList('flare.queue.thumbnails') ?? const [];
    final durations = prefs.getStringList('flare.queue.durations') ?? const [];
    queue
      ..clear()
      ..addAll(List.generate(ids.length, (i) => OnlineTrack(
        videoId: ids[i],
        title: i < titles.length ? titles[i] : 'Unknown title',
        artist: i < artists.length ? artists[i] : 'YouTube',
        thumbnail: i < thumbnails.length ? thumbnails[i] : '',
        duration: i < durations.length ? durations[i] : '',
      )));
    index = prefs.getInt('flare.queue.index') ?? -1;
    if (index < 0 || index >= queue.length) index = -1;
  }

  Future<void> playQueue(List<OnlineTrack> tracks, int startIndex, Future<String> Function(String) resolve) async {
    if (!_initialized || tracks.isEmpty || startIndex < 0 || startIndex >= tracks.length) return;
    queue..clear()..addAll(tracks);
    index = startIndex;
    await _persist();
    await _loadQueue(resolve);
    await player.play();
  }

  Future<void> playSelected(List<OnlineTrack> tracks, int startIndex) => playQueue(tracks, startIndex, resolver.resolve);

  Future<void> resume() async {
    if (!_initialized) await initialize();
    await player.play();
  }

  Future<void> pause() async {
    if (!_initialized) await initialize();
    await player.pause();
  }

  Future<void> togglePlayPause() async {
    if (!_initialized) await initialize();
    if (player.playing) {
      await player.pause();
    } else {
      await player.play();
    }
  }

  Future<void> skipNext() async {
    if (!_initialized) await initialize();
    await next(resolver.resolve);
  }

  Future<void> skipPrevious() async {
    if (!_initialized) await initialize();
    await previous(resolver.resolve);
  }

  MediaItem _mediaItem(OnlineTrack track) {
    return MediaItem(
      id: track.videoId,
      title: track.title,
      artist: track.artist,
      album: 'FlareMusic',
      displayTitle: track.title,
      displaySubtitle: track.artist,
      artUri: track.thumbnail.isNotEmpty ? Uri.tryParse(track.thumbnail) : null,
    );
  }

  Future<UriAudioSource> _resolveSource(
    OnlineTrack track,
    Future<String> Function(String) resolve,
  ) async {
    final url = await resolve(track.videoId);
    if (url.trim().isEmpty) {
      throw StateError('YouTube returned an empty audio stream.');
    }
    return AudioSource.uri(Uri.parse(url), tag: _mediaItem(track));
  }

  Future<void> playCurrent(Future<String> Function(String) resolve) async {
    if (!_initialized) return;
    final track = current;
    if (track == null) return;

    // When a queue is loaded into just_audio, Android's media session can
    // expose that queue to the lock screen, notification, Bluetooth controls,
    // Android Auto, etc. Selecting a source directly would discard that queue.
    if (player.sequence.length == queue.length &&
        index >= 0 &&
        index < player.sequence.length) {
      await player.seek(Duration.zero, index: index);
      await player.play();
      await _persist();
      return;
    }

    final source = await _resolveSource(track, resolve);
    await player.setAudioSource(source);
    await player.play();
    await _persist();
  }

  Future<void> _loadQueue(
    Future<String> Function(String) resolve,
  ) async {
    final sources = <UriAudioSource>[];
    for (final track in queue) {
      sources.add(await _resolveSource(track, resolve));
    }
    await player.setAudioSource(
      ConcatenatingAudioSource(
        useLazyPreparation: true,
        children: sources,
      ),
      initialIndex: index,
      initialPosition: Duration.zero,
    );
  }

  Future<void> next(Future<String> Function(String) resolve) async {
    if (queue.isEmpty) return;
    index = (index + 1) % queue.length;
    await _persist();
    if (player.sequence.length == queue.length) {
      await player.seek(Duration.zero, index: index);
      await player.play();
    } else {
      await playCurrent(resolve);
    }
  }

  Future<void> previous(Future<String> Function(String) resolve) async {
    if (queue.isEmpty) return;
    index = index <= 0 ? queue.length - 1 : index - 1;
    await _persist();
    if (player.sequence.length == queue.length) {
      await player.seek(Duration.zero, index: index);
      await player.play();
    } else {
      await playCurrent(resolve);
    }
  }
  Future<void> _persist() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList('flare.queue.ids', queue.map((e) => e.videoId).toList());
    await prefs.setStringList('flare.queue.titles', queue.map((e) => e.title).toList());
    await prefs.setStringList('flare.queue.artists', queue.map((e) => e.artist).toList());
    await prefs.setStringList('flare.queue.thumbnails', queue.map((e) => e.thumbnail).toList());
    await prefs.setStringList('flare.queue.durations', queue.map((e) => e.duration).toList());
    await prefs.setInt('flare.queue.index', index);
  }

  Future<void> dispose() async { await resolver.close(); if (_initialized) await player.dispose(); }
}
