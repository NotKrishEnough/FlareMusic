import 'dart:io';
import 'package:flutter/services.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

/// Resolves YouTube audio streams. On Android the app first uses the native
/// NewPipe extractor, which is better suited to current YouTube stream changes.
/// youtube_explode_dart remains as a cross-platform fallback.
class YouTubeStreamResolver {
  final YoutubeExplode _youtube = YoutubeExplode();
  static const _native = MethodChannel('flare_music/native_resolver');

  Future<String> resolve(String videoId) async {
    Object? nativeError;
    if (Platform.isAndroid) {
      try {
        final url = await _native
            .invokeMethod<String>('resolve', <String, dynamic>{'videoId': videoId})
            .timeout(const Duration(seconds: 30));
        if (url != null && url.trim().isNotEmpty) {
          return url;
        }
        nativeError = StateError('Native extractor returned an empty stream URL.');
      } catch (e) {
        nativeError = e;
      }
    }

    Object? lastError = nativeError;
    final clients = <List<YoutubeApiClient>>[
      <YoutubeApiClient>[
        YoutubeApiClient.ios,
        YoutubeApiClient.safari,
        YoutubeApiClient.tv,
      ],
      <YoutubeApiClient>[YoutubeApiClient.mediaConnect],
      <YoutubeApiClient>[YoutubeApiClient.androidSdkless],
    ];

    for (final ytClients in clients) {
      try {
        final manifest = await _youtube.videos.streamsClient
            .getManifest(videoId, ytClients: ytClients, requireWatchPage: false)
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

    throw Exception(
      'Could not resolve a playable YouTube audio stream. '
      '${lastError ?? 'Unknown extractor error.'}',
    );
  }

  Future<void> close() async => _youtube.close();
}
