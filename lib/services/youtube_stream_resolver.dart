import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Resolves public YouTube audio streams using youtube_explode_dart.
/// This depends on an unofficial extractor and may stop working if YouTube changes.
class YouTubeStreamResolver {
  final YoutubeExplode _youtube = YoutubeExplode();

  Future<String> resolve(String videoId) async {
    Object? lastError;
    for (final clients in <List<YoutubeApiClient>?>[
      null,
      [YoutubeApiClient.androidVr],
      [YoutubeApiClient.safari],
    ]) {
      try {
        final manifest = clients == null
            ? await _youtube.videos.streamsClient.getManifest(videoId, requireWatchPage: true).timeout(const Duration(seconds: 20))
            : await _youtube.videos.streamsClient.getManifest(videoId, ytClients: clients, requireWatchPage: true).timeout(const Duration(seconds: 20));
        final audio = manifest.audioOnly.withHighestBitrate();
        if (audio.url.toString().isEmpty) throw StateError('No playable audio stream was returned.');
        return audio.url.toString();
      } catch (e) {
        lastError = e;
      }
    }
    throw Exception('Could not resolve a playable YouTube audio stream. ${lastError ?? ''}');
  }

  Future<void> close() async => _youtube.close();
}
