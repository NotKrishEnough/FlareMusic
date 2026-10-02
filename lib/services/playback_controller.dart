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
  StreamSubscription<int?>? _currentIndexSubscription;

  Future<void> initialize() async {
    if (_initialized) return;
    player = AudioPlayer(userAgent: 'FlareMusic/1.0 (Android)');
    _initialized = true;
    _currentIndexSubscription = player.currentIndexStream.listen((value) {
      if (value == null || value < 0 || value >= queue.length) return;
      index = value;
      unawaited(_persist());
    });
  }
  final YouTubeStreamResolver resolver = YouTubeStreamResolver();
  final List<OnlineTrack> queue = [];
  int index = -1;
  int _queueGeneration = 0;

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

  Future<AudioSource> _sourceFor(
    OnlineTrack track,
    Future<String> Function(String) resolve,
  ) async {
    final url = await resolve(track.videoId);
    if (url.trim().isEmpty) {
      throw StateError('Empty audio stream URL for ${track.title}.');
    }
    return AudioSource.uri(
      Uri.parse(url),
      tag: MediaItem(
        id: track.videoId,
        title: track.title,
        artist: track.artist,
        album: 'FlareMusic',
        displayTitle: track.title,
        displaySubtitle: track.artist,
        artUri: track.thumbnail.isNotEmpty ? Uri.tryParse(track.thumbnail) : null,
      ),
    );
  }

  Future<void> playQueue(
    List<OnlineTrack> tracks,
    int startIndex,
    Future<String> Function(String) resolve,
  ) async {
    if (!_initialized || tracks.isEmpty || startIndex < 0 || startIndex >= tracks.length) {
      return;
    }

    // Resolve the tapped song first so playback starts as soon as possible.
    // Each new playback request invalidates any queue that is still resolving
    // in the background.
    final generation = ++_queueGeneration;
    final selected = tracks[startIndex];
    final firstSource = await _sourceFor(selected, resolve);
    final concat = ConcatenatingAudioSource(children: [firstSource]);

    queue
      ..clear()
      ..add(selected);
    index = 0;

    await player.setAudioSource(concat, initialIndex: 0);
    await player.setLoopMode(LoopMode.all);
    await _persist();
    await player.play();

    // Build the rest of the queue after playback has already started.
    final remaining = <OnlineTrack>[
      ...tracks.sublist(startIndex + 1),
      ...tracks.sublist(0, startIndex),
    ];

    unawaited(() async {
      // Resolve playlist/queue items one at a time. Running dozens of native
      // YouTube extractors concurrently can make NewPipe fail with a generic
      // just_audio "Source error".
      for (final track in remaining) {
        if (generation != _queueGeneration) return;
        try {
          final source = await _sourceFor(track, resolve);
          if (generation != _queueGeneration) return;
          await concat.add(source);
          queue.add(track);
          await _persist();
        } catch (_) {
          // One unavailable track must not break the currently playing song
          // or the rest of the playlist.
        }
      }
    }());
  }

  Future<void> playSelected(List<OnlineTrack> tracks, int startIndex) => playQueue(tracks, startIndex, resolver.resolve);

  Future<void> resume() => _initialized ? player.play() : Future.value();
  Future<void> pause() => _initialized ? player.pause() : Future.value();
  Future<void> skipNext() => _initialized ? next(resolver.resolve) : Future.value();
  Future<void> skipPrevious() => _initialized ? previous(resolver.resolve) : Future.value();

  Future<void> togglePlayPause() async {
    if (!_initialized) return;
    if (player.playing) {
      await player.pause();
    } else {
      await player.play();
    }
  }

  Future<void> playCurrent(Future<String> Function(String) resolve) async {
    if (!_initialized) return;
    final track = current;
    if (track == null) return;

    final source = player.audioSource;
    if (source is ConcatenatingAudioSource) {
      final targetIndex = player.currentIndex;
      if (targetIndex != null && targetIndex >= 0 && targetIndex < queue.length) {
        index = targetIndex;
        await player.play();
        await _persist();
        return;
      }
    }

    await playQueue(queue, index, resolve);
  }

  Future<void> next(Future<String> Function(String) resolve) async {
    if (queue.isEmpty) return;
    if (player.hasNext) {
      await player.seekToNext();
      return;
    }
    await player.seek(Duration.zero, index: 0);
    await player.play();
  }

  Future<void> previous(Future<String> Function(String) resolve) async {
    if (queue.isEmpty) return;
    if (player.hasPrevious) {
      await player.seekToPrevious();
      return;
    }
    await player.seek(Duration.zero, index: queue.length - 1);
    await player.play();
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

  Future<void> dispose() async {
    await _currentIndexSubscription?.cancel();
    await resolver.close();
    if (_initialized) await player.dispose();
  }
}
