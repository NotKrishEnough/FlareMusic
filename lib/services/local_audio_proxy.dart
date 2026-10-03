import 'dart:async';
import 'dart:io';

/// Loopback HTTP bridge for YouTube CDN streams.
///
/// Android's media stack requests byte ranges while seeking/buffering. This
/// bridge forwards those requests and the upstream response headers/body,
/// matching the transport used by DA-Tunes instead of handing ExoPlayer a
/// signed googlevideo URL directly.
class LocalAudioProxy {
  HttpServer? _server;
  HttpClient? _client;

  int? get port => _server?.port;

  Future<void> start() async {
    if (_server != null) return;
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 12)
      ..idleTimeout = const Duration(seconds: 20)
      ..maxConnectionsPerHost = 6;
    _server = server;
    server.listen(_handle, onError: (_) {});
  }

  Uri wrap(String streamUrl) {
    final currentPort = port;
    if (currentPort == null) return Uri.parse(streamUrl);
    return Uri(
      scheme: 'http',
      host: '127.0.0.1',
      port: currentPort,
      path: '/audio',
      queryParameters: {'url': streamUrl},
    );
  }

  Future<void> _handle(HttpRequest incoming) async {
    if (incoming.uri.path != '/audio') {
      incoming.response.statusCode = HttpStatus.notFound;
      await incoming.response.close();
      return;
    }

    final rawUrl = incoming.uri.queryParameters['url'];
    final target = rawUrl == null ? null : Uri.tryParse(rawUrl);
    if (target == null ||
        target.scheme != 'https' ||
        !target.host.endsWith('googlevideo.com')) {
      incoming.response.statusCode = HttpStatus.badRequest;
      await incoming.response.close();
      return;
    }

    final client = _client;
    if (client == null) {
      incoming.response.statusCode = HttpStatus.serviceUnavailable;
      await incoming.response.close();
      return;
    }

    try {
      final upstream = await client.openUrl(incoming.method, target)
          .timeout(const Duration(seconds: 15));
      upstream.followRedirects = true;
      upstream.maxRedirects = 8;
      upstream.headers.set(
        HttpHeaders.userAgentHeader,
        'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36',
      );
      upstream.headers.set(HttpHeaders.acceptHeader, '*/*');
      for (final name in ['range', 'if-range', 'if-modified-since']) {
        final value = incoming.headers.value(name);
        if (value != null) upstream.headers.set(name, value);
      }

      final response = await upstream.close()
          .timeout(const Duration(seconds: 20));
      incoming.response.statusCode = response.statusCode;
      for (final name in [
        HttpHeaders.contentTypeHeader,
        HttpHeaders.contentLengthHeader,
        HttpHeaders.contentRangeHeader,
        HttpHeaders.acceptRangesHeader,
        HttpHeaders.cacheControlHeader,
        HttpHeaders.etagHeader,
        HttpHeaders.lastModifiedHeader,
      ]) {
        final value = response.headers.value(name);
        if (value != null) incoming.response.headers.set(name, value);
      }
      incoming.response.headers.set(HttpHeaders.accessControlAllowOriginHeader, '*');
      if (incoming.method == 'HEAD') {
        await incoming.response.close();
        return;
      }
      await incoming.response.addStream(response);
      await incoming.response.close();
    } catch (error) {
      if (!incoming.response.headersSent) {
        incoming.response.statusCode = HttpStatus.badGateway;
        incoming.response.headers.set(HttpHeaders.contentTypeHeader, 'text/plain; charset=utf-8');
        incoming.response.write('Upstream audio request failed: $error');
        await incoming.response.close();
      } else {
        incoming.response.abort(error);
      }
    }
  }

  Future<void> close() async {
    final server = _server;
    _server = null;
    await server?.close(force: true);
    _client?.close(force: true);
    _client = null;
  }
}
