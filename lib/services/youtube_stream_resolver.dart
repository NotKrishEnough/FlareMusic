import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Resolves public YouTube audio streams using youtube_explode_dart.
/// This depends on an unofficial extractor and may stop working if YouTube changes.
class YouTubeStreamResolver {
  final YoutubeExplode _youtube = YoutubeExplode();

  Future<String> resolve(String videoId) async {
    Object? lastError;
    final primaryClients = <YoutubeApiClient>[
      YoutubeApiClient.androidVr,
      YoutubeApiClient.androidSdkless,
      YoutubeApiClient.ios,
    ];
    final fallbackClients = <YoutubeApiClient>[
      YoutubeApiClient.tv,
      YoutubeApiClient.mediaConnect,
      YoutubeApiClient.safari,
    ];

    for (final clients in <List<YoutubeApiClient>>[
      primaryClients,
      ...fallbackClients.map((client) => <YoutubeApiClient>[client]),
    ]) {
      try {
        final manifest = await _youtube.videos.streamsClient
            .getManifest(videoId, ytClients: clients, requireWatchPage: false)
            .timeout(const Duration(seconds: 25));
        final candidates = manifest.audioOnly
            .where((stream) => stream.url.toString().startsWith('https://'))
            .toList();
        if (candidates.isEmpty) {
          throw StateError('No playable audio stream was returned by YouTube.');
        }
        final audio = candidates.reduce(
          (a, b) => a.bitrate.bitsPerSecond >= b.bitrate.bitsPerSecond ? a : b,
        );
        return audio.url.toString();
      } catch (e) {
        lastError = e;
      }
    }

    throw Exception('Could not resolve a playable YouTube audio stream. ${lastError ?? ''}');
  }

  Future<void> close() async => _youtube.close();
}
