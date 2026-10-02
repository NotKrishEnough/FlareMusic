import 'dart:async';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class YouTubeLoginPage extends StatefulWidget {
  const YouTubeLoginPage({super.key, required this.onCookies});
  final Future<bool> Function(String cookies) onCookies;

  @override
  State<YouTubeLoginPage> createState() => _YouTubeLoginPageState();
}

class _YouTubeLoginPageState extends State<YouTubeLoginPage> {
  late final WebViewController _controller;
  bool _busy = false;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setUserAgent('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36')
      ..setNavigationDelegate(NavigationDelegate(
        onPageStarted: (_) => mounted ? setState(() => _loading = true) : null,
        onPageFinished: (url) {
          if (mounted) setState(() => _loading = false);
          _tryCaptureSession(url);
        },
      ))
      ..loadRequest(Uri.parse(
        'https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https://music.youtube.com/',
      ));
  }

  Future<void> _tryCaptureSession(String url) async {
    if (_busy || !url.startsWith('https://music.youtube.com')) return;
    _busy = true;
    try {
      final manager = WebViewCookieManager();
      final cookies = await manager.getCookies(domain: Uri.parse('https://music.youtube.com'));
      final header = cookies.map((c) => '${c.name}=${c.value}').join('; ');
      if (header.isEmpty) return;
      final ok = await widget.onCookies(header);
      if (ok && mounted) Navigator.of(context).pop(true);
    } finally {
      _busy = false;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Sign in to YouTube Music'),
      actions: [
        if (_loading)
          const Padding(padding: EdgeInsets.all(16), child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))),
      ],
    ),
    body: WebViewWidget(controller: _controller),
  );
}
