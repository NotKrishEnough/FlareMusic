import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Resolves public YouTube audio streams using youtube_explode_dart.
/// This depends on an unofficial extractor and may stop working if YouTube changes.
class YouTubeStreamResolver {
  final YoutubeExplode _youtube = YoutubeExplode();

  Future<String> resolve(String videoId) async {
    final manifest = await _youtube.videos.streamsClient.getManifest(videoId);
    final audio = manifest.audioOnly.withHighestBitrate();
    return audio.url.toString();
  }

  Future<void> close() async => _youtube.close();
}
