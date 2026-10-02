import 'dart:async';
import 'dart:math' show min;
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'services/inner_tube_client.dart';
import 'services/playback_controller.dart';
import 'services/playlist_library.dart';
import 'services/youtube_account_service.dart';
import 'services/youtube_cookie_auth.dart';
import 'services/youtube_login_page.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dynamic_color/dynamic_color.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Never block Flutter's first frame on the audio service. Android can take
  // a moment to bind the notification service; the app UI should still open.
  runApp(const FlareMusicApp());
}

class FlareMusicApp extends StatefulWidget {
  const FlareMusicApp({super.key});
  @override
  State<FlareMusicApp> createState() => _FlareMusicAppState();
}

class _FlareMusicAppState extends State<FlareMusicApp> {
  ThemeMode _themeMode = ThemeMode.dark;
  bool _useDynamicColors = true;
  int _accentIndex = 0;

  static const _accents = <Color>[
    Color(0xFF8BC5FF), Color(0xFFB39DDB), Color(0xFFFF8A80),
    Color(0xFF80CBC4), Color(0xFFFFD180), Color(0xFFF48FB1),
    Color(0xFFA5D6A7),
  ];

  @override
  void initState() {
    super.initState();
    _loadAppearance();
  }

  Future<void> _loadAppearance() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      final mode = prefs.getString('flare.appearance.theme') ?? 'dark';
      _themeMode = mode == 'light' ? ThemeMode.light
          : mode == 'system' ? ThemeMode.system : ThemeMode.dark;
      _useDynamicColors = prefs.getBool('flare.appearance.dynamic') ?? true;
      _accentIndex = (prefs.getInt('flare.appearance.accent') ?? 0)
          .clamp(0, _accents.length - 1).toInt();
    });
  }

  Future<void> _setThemeMode(ThemeMode value) async {
    setState(() => _themeMode = value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('flare.appearance.theme', value.name);
  }

  Future<void> _setDynamicColors(bool value) async {
    setState(() => _useDynamicColors = value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('flare.appearance.dynamic', value);
  }

  Future<void> _setAccentIndex(int value) async {
    setState(() => _accentIndex = value);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('flare.appearance.accent', value);
  }

  ThemeData _makeTheme(Brightness brightness, ColorScheme? dynamicScheme) {
    final scheme = _useDynamicColors && dynamicScheme != null
        ? dynamicScheme
        : ColorScheme.fromSeed(seedColor: _accents[_accentIndex], brightness: brightness);
    return ThemeData(
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      useMaterial3: true,
      appBarTheme: AppBarTheme(backgroundColor: scheme.surface, foregroundColor: scheme.onSurface),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: scheme.inverseSurface,
        contentTextStyle: TextStyle(color: scheme.onInverseSurface),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => DynamicColorBuilder(
    builder: (lightDynamic, darkDynamic) => MaterialApp(
      title: 'FlareMusic',
      debugShowCheckedModeBanner: false,
      theme: _makeTheme(Brightness.light, lightDynamic),
      darkTheme: _makeTheme(Brightness.dark, darkDynamic),
      themeMode: _themeMode,
      home: MusicHome(
        themeMode: _themeMode,
        useDynamicColors: _useDynamicColors,
        accentIndex: _accentIndex,
        accents: _accents,
        onThemeModeChanged: _setThemeMode,
        onDynamicColorsChanged: _setDynamicColors,
        onAccentChanged: _setAccentIndex,
      ),
    ),
  );
}

class MusicHome extends StatefulWidget {
  const MusicHome({
    super.key,
    required this.themeMode,
    required this.useDynamicColors,
    required this.accentIndex,
    required this.accents,
    required this.onThemeModeChanged,
    required this.onDynamicColorsChanged,
    required this.onAccentChanged,
  });
  final ThemeMode themeMode;
  final bool useDynamicColors;
  final int accentIndex;
  final List<Color> accents;
  final ValueChanged<ThemeMode> onThemeModeChanged;
  final ValueChanged<bool> onDynamicColorsChanged;
  final ValueChanged<int> onAccentChanged;
  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  final _cookieAuth = YouTubeCookieAuth();
  late final InnerTubeClient _api;
  final _playback = PlaybackController();
  final _library = PlaylistLibrary();
  final _account = YouTubeAccountService();
  bool _syncing = false;
  List<LocalPlaylist> _playlists = [];
  LocalPlaylist? _openPlaylist;
  final _searchController = TextEditingController();
  final _searchFocus = FocusNode();
  final List<OnlineTrack> _results = [];
  final List<OnlineTrack> _homeTracks = [];
  final List<OnlineTrack> _likedTracks = [];
  bool _homeLoading = false;
  bool _searching = false;
  bool _loading = false;
  String? _error;
  String _nowTitle = 'Nothing playing';
  String _nowArtist = 'Search for a song to get started';
  int _tab = 0;
  bool _playing = false;
  bool _playerExpanded = false;
  StreamSubscription<PlayerState>? _playerStateSubscription;

  static const _tabs = [(Icons.home_rounded, 'Home'), (Icons.explore_rounded, 'Explore'), (Icons.library_music_rounded, 'Library'), (Icons.person_rounded, 'You')];

  @override
  void initState() {
    super.initState();
    _api = InnerTubeClient(auth: _cookieAuth);
    _requestMediaNotificationPermission();
    _refreshLibrary();
    _restoreAccount();
    _restoreYouTubeMusicSession();
    _initializeAudio();
  }

  @override
  void dispose() {
    _playerStateSubscription?.cancel();
    _playback.dispose();
    _api.close();
    _searchController.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  Future<void> _requestMediaNotificationPermission() async {
    try {
      await Permission.notification.request();
    } catch (e) {
      debugPrint('Notification permission request failed: $e');
    }
  }

  Future<void> _initializeAudio() async {
    try {
      await JustAudioBackground.init(
        androidNotificationChannelId: 'com.flaremusic.playback',
        androidNotificationChannelName: 'FlareMusic playback',
        androidNotificationOngoing: true,
        androidStopForegroundOnPause: false,
        preloadArtwork: true,
      );
      await _playback.initialize();
      if (!mounted) return;
      await _restorePlaybackState();
      _playerStateSubscription = _playback.player.playerStateStream.listen((state) {
        if (!mounted) return;
        setState(() => _playing = state.playing);
      });
    } catch (e, st) {
      debugPrint('Audio initialization failed: $e');
      debugPrintStack(stackTrace: st);
      if (mounted) setState(() => _error = 'Audio engine could not start. Restart FlareMusic to try again.');
    }
  }


  Future<void> _refreshPersonalized() async {
    if (!_cookieAuth.isLoggedIn) {
      setState(() { _searching = true; _error = null; });
      return;
    }
    await _loadPersonalizedMusic();
  }

  Future<void> _playAll(List<OnlineTrack> tracks) async {
    if (tracks.isEmpty) return;
    await _selectTrack(tracks.first, source: tracks);
  }

  Future<void> _restorePlaybackState() async {
    try {
      await _playback.restore();
      final track = _playback.current;
      if (!mounted || track == null) return;
      setState(() {
        _nowTitle = track.title;
        _nowArtist = track.artist;
      });
    } catch (e) {
      debugPrint('Playback restore failed: $e');
    }
  }

  Future<void> _restoreAccount() async {
    try { await _account.restoreSession(); } catch (_) { /* User can sign in again from Library. */ }
  }

  Future<void> _restoreYouTubeMusicSession() async {
    try {
      await _cookieAuth.restore();
      if (_cookieAuth.isLoggedIn) {
        final valid = await _cookieAuth.verifyCurrent();
        if (valid) await _loadPersonalizedMusic();
      }
      if (mounted) setState(() {});
    } catch (e) {
      debugPrint('YouTube Music session restore failed: $e');
    }
  }

  Future<void> _loadPersonalizedMusic() async {
    if (!_cookieAuth.isLoggedIn) return;
    if (mounted) setState(() => _homeLoading = true);
    try {
      final home = await _api.fetchHome();
      final liked = await _api.fetchLikedSongs();
      if (!mounted) return;
      setState(() {
        _homeTracks
          ..clear()
          ..addAll(home);
        _likedTracks
          ..clear()
          ..addAll(liked);
        _homeLoading = false;
      });
    } catch (e) {
      if (mounted) setState(() => _homeLoading = false);
      debugPrint('Personalized YouTube Music load failed: $e');
    }
  }

  Future<void> _refreshLibrary() async { final lists = await _library.all(); if (mounted) setState(() => _playlists = lists); }

  Future<void> _youtubeMusicWebLogin() async {
    final ok = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => YouTubeLoginPage(
          onCookies: (cookies) => _cookieAuth.loginWithCookies(cookies),
        ),
      ),
    );
    if (ok == true && mounted) {
      await _loadPersonalizedMusic();
      setState(() {});
    }
  }

  Future<void> _pasteYouTubeCookie() async {
    final controller = TextEditingController();
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Paste YouTube Music cookies'),
        content: TextField(
          controller: controller,
          maxLines: 5,
          decoration: const InputDecoration(
            hintText: 'SID=...; SAPISID=...; __Secure-3PSID=...',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Connect')),
        ],
      ),
    );
    controller.dispose();
    if (value == null || value.trim().isEmpty) return;
    final ok = await _cookieAuth.loginWithCookies(value);
    if (!mounted) return;
    setState(() {});
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(ok ? 'YouTube Music connected' : 'Could not verify those cookies')),
    );
  }

  Future<void> _logoutYouTubeMusic() async {
    await _cookieAuth.logout();
    if (mounted) setState(() {});
  }

  Future<void> _accountSync() async {
    setState(() => _syncing = true);
    try {
      List<LocalPlaylist> synced;
      if (_account.currentUser != null) {
        synced = await _account.syncPlaylists();
      } else if (_cookieAuth.isLoggedIn) {
        await _loadPersonalizedMusic();
        final remotePlaylists = await _api.fetchLibraryPlaylists();
        synced = [
          LocalPlaylist(
            id: 'ytm:home',
            name: 'YouTube Music Home',
            sourceId: 'FEmusic_home',
            tracks: List<OnlineTrack>.from(_homeTracks),
          ),
          if (_likedTracks.isNotEmpty)
            LocalPlaylist(
              id: 'ytm:liked',
              name: 'Liked Music',
              sourceId: 'FEmusic_liked_videos',
              tracks: List<OnlineTrack>.from(_likedTracks),
            ),
        ];
        for (final remote in remotePlaylists.take(12)) {
          try {
            final tracks = await _api.fetchPlaylist(remote.id);
            if (tracks.isNotEmpty) {
              synced.add(LocalPlaylist(
                id: 'ytm:${remote.id}',
                name: remote.title,
                sourceId: remote.id,
                tracks: tracks,
              ));
            }
          } catch (e) {
            debugPrint('Could not load YouTube Music playlist ${remote.id}: $e');
          }
        }
      } else {
        await _account.signIn();
        if (_account.currentUser == null) return;
        synced = await _account.syncPlaylists();
      }
      await _library.replaceSynced(synced);
      await _refreshLibrary();
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Synced ${synced.length} YouTube playlists')));
    } catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Account sync failed: $e'))); }
    finally { if (mounted) setState(() => _syncing = false); }
  }

  Future<void> _createPlaylist() async {
    final name = TextEditingController();
    final result = await showDialog<String>(context: context, builder: (context) => AlertDialog(title: const Text('New playlist'), content: TextField(controller: name, autofocus: true, decoration: const InputDecoration(hintText: 'Playlist name')), actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')), FilledButton(onPressed: () => Navigator.pop(context, name.text), child: const Text('Create'))]));
    name.dispose();
    if (result == null || result.trim().isEmpty) return;
    await _library.create(result); await _refreshLibrary();
  }

  Future<void> _importPlaylist() async {
    final id = TextEditingController();
    final result = await showDialog<String>(context: context, builder: (context) => AlertDialog(title: const Text('Import public YouTube playlist'), content: TextField(controller: id, decoration: const InputDecoration(hintText: 'Playlist ID or URL')), actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')), FilledButton(onPressed: () => Navigator.pop(context, id.text), child: const Text('Import'))]));
    id.dispose();
    if (result == null || result.trim().isEmpty) return;
    try { final uri = Uri.tryParse(result.trim()); final playlistId = uri?.queryParameters['list'] ?? result.trim(); await _library.importYouTube(playlistId, _api); await _refreshLibrary(); } catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Import failed: $e'))); }
  }

  Future<void> _search([String? value]) async {
    final query = (value ?? _searchController.text).trim();
    if (query.isEmpty) return;
    setState(() { _searching = true; _loading = true; _error = null; _results.clear(); });
    try {
      final found = await _api.search(query);
      if (!mounted) return;
      setState(() { _results.addAll(found); _loading = false; if (found.isEmpty) _error = 'No tracks found. Try another search.'; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _loading = false; _error = e.toString().replaceFirst('Exception: ', ''); });
    }
  }

  Future<void> _selectTrack(OnlineTrack track, {List<OnlineTrack>? source}) async {
    final tracks = source ?? _results;
    final index = tracks.indexOf(track);
    setState(() { _nowTitle = track.title; _nowArtist = track.artist; _playing = false; _searching = false; });
    try {
      await _playback.playSelected(tracks, index < 0 ? 0 : index);
      if (mounted) setState(() => _playing = true);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Playback failed: $e')));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: Stack(children: [
      if (_openPlaylist != null) _playlistDetailPage(_openPlaylist!) else if (_tab == 2) _libraryPage() else if (_tab == 1) _explorePage() else if (_tab == 3) _accountPage() else CustomScrollView(slivers: [
        SliverPadding(padding: const EdgeInsets.fromLTRB(22, 18, 22, 150), sliver: SliverList(delegate: SliverChildListDelegate([
          Row(children: [
            Container(width: 42, height: 42, decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withValues(alpha: .16), borderRadius: BorderRadius.circular(15)), child: Icon(Icons.graphic_eq_rounded, color: Theme.of(context).colorScheme.primary)),
            const SizedBox(width: 12),
            const Text('FlareMusic', style: TextStyle(fontSize: 23, fontWeight: FontWeight.w800)),
            const Spacer(),
            IconButton(onPressed: () => setState(() { _searching = true; _error = null; }), icon: const Icon(Icons.search_rounded)),
          ]),
          const SizedBox(height: 30),
          Text('GOOD EVENING', style: TextStyle(fontSize: 11, letterSpacing: 2, color: Colors.white.withValues(alpha: .55), fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          const Text('Your music, your mood.', style: TextStyle(fontSize: 34, height: 1.12, fontWeight: FontWeight.w800)),
          const SizedBox(height: 25),
          _sectionTitle('Made for you', _cookieAuth.isLoggedIn ? 'Refresh' : 'Search music', onAction: _cookieAuth.isLoggedIn ? _refreshPersonalized : () => setState(() => _searching = true)),
          const SizedBox(height: 14),
          if (_homeTracks.isNotEmpty)
            SizedBox(
              height: 176,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: _homeTracks.take(8).length,
                separatorBuilder: (_, __) => const SizedBox(width: 12),
                itemBuilder: (context, i) {
                  final track = _homeTracks[i];
                  return TweenAnimationBuilder<double>(
                    duration: Duration(milliseconds: 260 + (i * 45)),
                    curve: Curves.easeOutCubic,
                    tween: Tween(begin: .92, end: 1),
                    builder: (context, scale, child) => Transform.scale(scale: scale, child: child),
                    child: SizedBox(
                      width: 150,
                      child: InkWell(
                      borderRadius: BorderRadius.circular(20),
                      onTap: () => _selectTrack(track, source: _homeTracks),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(18),
                          child: track.thumbnail.isEmpty
                              ? Container(width: 150, height: 125, color: const Color(0xFF31516B), child: const Icon(Icons.music_note_rounded, size: 34))
                              : Image.network(track.thumbnail, width: 150, height: 125, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 150, height: 125, color: const Color(0xFF31516B), child: const Icon(Icons.music_note_rounded, size: 34))),
                        ),
                        const SizedBox(height: 7),
                        Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
                        Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .6))),
                      ]),
                      ),
                    ),
                  );
                },
              ),
            )
          else if (_homeLoading)
            const SizedBox(height: 176, child: Center(child: CircularProgressIndicator()))
          else
          Container(height: 176, padding: const EdgeInsets.all(20), decoration: BoxDecoration(gradient: const LinearGradient(colors: [Color(0xFF244D78), Color(0xFF222B42), Color(0xFF30223F)]), borderRadius: BorderRadius.circular(25)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Spacer(), const Text('YOUR DAILY MIX', style: TextStyle(fontSize: 10, letterSpacing: 2, fontWeight: FontWeight.bold)), const SizedBox(height: 6),
            const Text('A little bit of everything', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w800)), const Spacer(),
            const Row(children: [Text('PERSONALIZED PLAYLIST', style: TextStyle(fontSize: 10, letterSpacing: 1.2)), Spacer(), Icon(Icons.arrow_forward_rounded)]),
          ])),
          const SizedBox(height: 28),
          if (_likedTracks.isNotEmpty) ...[
            _sectionTitle('Liked from YouTube Music', 'Play all', onAction: () => _playAll(_likedTracks)),
            const SizedBox(height: 12),
            ..._likedTracks.take(4).map((track) => ListTile(
              contentPadding: EdgeInsets.zero,
              leading: ClipRRect(borderRadius: BorderRadius.circular(10), child: track.thumbnail.isEmpty ? Container(width: 52, height: 52, color: const Color(0xFF31516B), child: const Icon(Icons.music_note_rounded)) : Image.network(track.thumbnail, width: 52, height: 52, fit: BoxFit.cover)),
              title: Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: () => _selectTrack(track, source: _likedTracks),
            )),
            const SizedBox(height: 18),
          ],
          _sectionTitle('Quick picks', 'Search music', onAction: () => setState(() => _searching = true)),
          const SizedBox(height: 12),
          ...List.generate(4, (i) => _trackRow(i)),
        ]))),
      ]),
      if (_searching) _searchPanel(),
      if (_playerExpanded) _fullPlayer(),
      if (!_playerExpanded && _playback.current != null) Align(alignment: Alignment.bottomCenter, child: Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 12), child: Column(mainAxisSize: MainAxisSize.min, children: [
        _miniPlayer(),
        const SizedBox(height: 12),
        Container(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: .96), borderRadius: BorderRadius.circular(32), border: Border.all(color: Colors.white.withValues(alpha: .07))), child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: List.generate(_tabs.length, (i) {
          final selected = _tab == i;
          return InkWell(borderRadius: BorderRadius.circular(25), onTap: () => setState(() => _tab = i), child: AnimatedContainer(duration: const Duration(milliseconds: 220), padding: EdgeInsets.symmetric(horizontal: selected ? 17 : 14, vertical: 10), decoration: BoxDecoration(color: selected ? Theme.of(context).colorScheme.primary.withValues(alpha: .16) : Colors.transparent, borderRadius: BorderRadius.circular(24)), child: Row(children: [
            Icon(_tabs[i].$1, size: 20, color: selected ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.onSurface.withValues(alpha: .7)),
            if (selected) ...[const SizedBox(width: 7), Text(_tabs[i].$2, style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w700, fontSize: 12))],
          ])));
        }))),
      ]))),
    ])),
  );

  Widget _explorePage() => Positioned.fill(child: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(22, 22, 22, 150), children: [
    const Text('Explore', style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800)),
    const SizedBox(height: 8),
    Text('Find something for every mood.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
    const SizedBox(height: 22),
    InkWell(onTap: () { setState(() { _searching = true; _error = null; }); }, borderRadius: BorderRadius.circular(18), child: Container(padding: const EdgeInsets.all(17), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest, borderRadius: BorderRadius.circular(18)), child: const Row(children: [Icon(Icons.search_rounded), SizedBox(width: 12), Text('Search songs, artists, albums...'), Spacer(), Icon(Icons.arrow_forward_rounded)]))),
    const SizedBox(height: 28),
    const Text('Browse by mood', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
    const SizedBox(height: 14),
    Wrap(spacing: 10, runSpacing: 10, children: [
      _moodChip('Chill', Icons.nightlight_round, const Color(0xFF354F75)),
      _moodChip('Focus', Icons.bolt_rounded, const Color(0xFF66513A)),
      _moodChip('Feel good', Icons.wb_sunny_rounded, const Color(0xFF715044)),
      _moodChip('Romance', Icons.favorite_rounded, const Color(0xFF70465F)),
      _moodChip('Workout', Icons.fitness_center_rounded, const Color(0xFF42645A)),
      _moodChip('Throwbacks', Icons.album_rounded, const Color(0xFF5B5075)),
    ]),
    const SizedBox(height: 28),
    const Text('Discover music', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
    const SizedBox(height: 8),
    Text('Search YouTube Music to discover tracks and start listening.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
    const SizedBox(height: 12),
    FilledButton.icon(onPressed: () { setState(() => _searching = true); }, icon: const Icon(Icons.explore_rounded), label: const Text('Explore songs')),
  ])));

  Widget _moodChip(String label, IconData icon, Color color) => ActionChip(
    avatar: Icon(icon, size: 18, color: Colors.white),
    label: Text(label),
    backgroundColor: color,
    side: BorderSide.none,
    onPressed: () { _searchController.text = label; setState(() => _searching = true); _search(label); },
  );

  Widget _accountPage() => Positioned.fill(child: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(22, 22, 22, 150), children: [
    const Text('Your account', style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800)),
    const SizedBox(height: 8),
    Text(_cookieAuth.isLoggedIn ? 'YouTube Music is connected. Personalized requests use your session.' : 'Connect YouTube to sync your playlists.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
    const SizedBox(height: 28),
    Container(padding: const EdgeInsets.all(20), decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest, borderRadius: BorderRadius.circular(22)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      CircleAvatar(radius: 30, backgroundColor: Theme.of(context).colorScheme.primary.withValues(alpha: .18), child: Icon(Icons.person_rounded, size: 32, color: Theme.of(context).colorScheme.primary)),
      const SizedBox(height: 16),
      Text(_account.currentUser?.displayName ?? 'Not signed in', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
      const SizedBox(height: 5),
      Text(_account.currentUser?.email ?? 'Sign in with Google to connect your YouTube account.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
      const SizedBox(height: 20),
      SizedBox(width: double.infinity, child: FilledButton.icon(
        onPressed: _syncing ? null : _accountSync,
        icon: _syncing ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : Icon(_account.currentUser == null ? Icons.login_rounded : Icons.sync_rounded),
        label: Text(_syncing ? 'Syncing...' : _account.currentUser == null && !_cookieAuth.isLoggedIn ? 'Sign in with Google' : 'Sync YouTube playlists'),
      )),
      const SizedBox(height: 10),
      SizedBox(width: double.infinity, child: OutlinedButton.icon(
        onPressed: _youtubeMusicWebLogin,
        icon: const Icon(Icons.language_rounded),
        label: Text(_cookieAuth.isLoggedIn ? 'YouTube Music connected' : 'Sign in with YouTube Music'),
      )),
      const SizedBox(height: 8),
      SizedBox(width: double.infinity, child: TextButton.icon(
        onPressed: _pasteYouTubeCookie,
        icon: const Icon(Icons.key_rounded),
        label: const Text('Use cookie header'),
      )),
      if (_cookieAuth.isLoggedIn) ...[
        const SizedBox(height: 4),
        SizedBox(width: double.infinity, child: OutlinedButton.icon(
          onPressed: _logoutYouTubeMusic,
          icon: const Icon(Icons.logout_rounded),
          label: const Text('Disconnect YouTube Music'),
        )),
      ],
      if (_account.currentUser != null) ...[
        const SizedBox(height: 8),
        SizedBox(width: double.infinity, child: OutlinedButton.icon(onPressed: () async { await _account.signOut(); if (mounted) setState(() {}); }, icon: const Icon(Icons.logout_rounded), label: const Text('Sign out Google'))),
      ],
    ])),
    const SizedBox(height: 26),
    const Text('Appearance & customization', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
    const SizedBox(height: 8),
    Text('Make FlareMusic feel like yours.', style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .65))),
    const SizedBox(height: 12),
    Card(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Theme', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          SegmentedButton<ThemeMode>(
            segments: const [
              ButtonSegment(value: ThemeMode.system, label: Text('System'), icon: Icon(Icons.brightness_auto_rounded)),
              ButtonSegment(value: ThemeMode.light, label: Text('Light'), icon: Icon(Icons.light_mode_rounded)),
              ButtonSegment(value: ThemeMode.dark, label: Text('Dark'), icon: Icon(Icons.dark_mode_rounded)),
            ],
            selected: {widget.themeMode},
            onSelectionChanged: (values) => widget.onThemeModeChanged(values.first),
            showSelectedIcon: false,
          ),
          const SizedBox(height: 18),
          SwitchListTile.adaptive(
            contentPadding: EdgeInsets.zero,
            title: const Text('Dynamic wallpaper colors'),
            subtitle: const Text('Use colors from your Android wallpaper when supported'),
            value: widget.useDynamicColors,
            onChanged: widget.onDynamicColorsChanged,
          ),
          const SizedBox(height: 8),
          const Text('Accent color', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: List.generate(widget.accents.length, (i) => InkWell(
              onTap: () => widget.onAccentChanged(i),
              borderRadius: BorderRadius.circular(24),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: widget.accents[i],
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: widget.accentIndex == i ? Theme.of(context).colorScheme.onSurface : Colors.transparent,
                    width: 3,
                  ),
                ),
                child: widget.accentIndex == i
                    ? const Icon(Icons.check_rounded, color: Colors.black, size: 20)
                    : null,
              ),
            )),
          ),
          const SizedBox(height: 8),
          Text('Your appearance choices are saved on this device.', style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .65), fontSize: 12)),
        ]),
      ),
    ),
    const SizedBox(height: 22),
    const Text('Your music stays yours', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
    const SizedBox(height: 8),
    Text('Local playlists remain on this device. Google sync uses the read-only YouTube permission; YouTube Music login uses a securely stored session cookie.', style: TextStyle(color: Colors.white.withValues(alpha: .6), height: 1.5)),
  ])));

  Widget _libraryPage() => Positioned.fill(child: Container(color: Theme.of(context).scaffoldBackgroundColor, child: SafeArea(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
    Padding(padding: const EdgeInsets.fromLTRB(22, 22, 16, 12), child: Row(children: [const Expanded(child: Text('Your Library', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800))), IconButton(onPressed: _syncing ? null : _accountSync, icon: _syncing ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.sync_rounded), tooltip: 'Sign in and sync YouTube'), IconButton(onPressed: _createPlaylist, icon: const Icon(Icons.add_rounded)), IconButton(onPressed: _importPlaylist, icon: const Icon(Icons.download_rounded))])),
    Padding(padding: const EdgeInsets.symmetric(horizontal: 22), child: Text('Playlists saved on this device', style: TextStyle(color: Colors.white.withValues(alpha: .6)))),
    const SizedBox(height: 12),
    Expanded(child: _playlists.isEmpty
        ? Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.library_music_outlined, size: 48, color: Colors.white38),
            const SizedBox(height: 12),
            const Text('Your library is empty'),
            const SizedBox(height: 8),
            Wrap(spacing: 8, children: [
              OutlinedButton(onPressed: _createPlaylist, child: const Text('Create playlist')),
              OutlinedButton(onPressed: _importPlaylist, child: const Text('Import YouTube')),
            ]),
          ]))
        : ListView.builder(
            itemCount: _playlists.length,
            itemBuilder: (context, i) {
              final p = _playlists[i];
              return ListTile(
                leading: Container(width: 48, height: 48, decoration: BoxDecoration(color: const Color(0xFF31516B), borderRadius: BorderRadius.circular(12)), child: const Icon(Icons.queue_music_rounded)),
                title: Text(p.name),
                subtitle: Text('${p.tracks.length} tracks'),
                trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () async { await _library.remove(p.id); await _refreshLibrary(); }),
                onTap: () => setState(() => _openPlaylist = p),
              );
            },
          )),
  ]))));

  Widget _playlistDetailPage(LocalPlaylist playlist) => Positioned.fill(
    child: Container(
      color: Theme.of(context).scaffoldBackgroundColor,
      child: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(18, 10, 18, 0),
              sliver: SliverToBoxAdapter(
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () => setState(() => _openPlaylist = null),
                      icon: const Icon(Icons.arrow_back_rounded),
                    ),
                    const Expanded(
                      child: Text(
                        'PLAYLIST',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          letterSpacing: 2,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    const SizedBox(width: 48),
                  ],
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(22, 18, 22, 8),
              sliver: SliverToBoxAdapter(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(18),
                      child: playlist.tracks.isNotEmpty &&
                              playlist.tracks.first.thumbnail.isNotEmpty
                          ? Image.network(
                              playlist.tracks.first.thumbnail,
                              width: 128,
                              height: 128,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) =>
                                  _playlistArtworkFallback(),
                            )
                          : _playlistArtworkFallback(),
                    ),
                    const SizedBox(width: 18),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            playlist.name,
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 25,
                              height: 1.05,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            '${playlist.tracks.length} tracks',
                            style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .6)),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(22, 12, 22, 12),
              sliver: SliverToBoxAdapter(
                child: Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: playlist.tracks.isEmpty
                            ? null
                            : () => _playAll(playlist.tracks),
                        icon: const Icon(Icons.play_arrow_rounded),
                        label: const Text('Play all'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    IconButton.filledTonal(
                      onPressed: playlist.tracks.isEmpty
                          ? null
                          : () {
                              final shuffled = List<OnlineTrack>.from(
                                playlist.tracks,
                              )..shuffle();
                              _selectTrack(shuffled.first, source: shuffled);
                            },
                      icon: const Icon(Icons.shuffle_rounded),
                      tooltip: 'Shuffle',
                    ),
                  ],
                ),
              ),
            ),
            if (playlist.tracks.isEmpty)
              const SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Text('This playlist has no tracks yet.'),
                ),
              )
            else
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(10, 0, 10, 160),
                sliver: SliverList(
                  delegate: SliverChildBuilderDelegate(
                    (context, index) {
                      final track = playlist.tracks[index];
                      return ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 3,
                        ),
                        leading: ClipRRect(
                          borderRadius: BorderRadius.circular(10),
                          child: track.thumbnail.isNotEmpty
                              ? Image.network(
                                  track.thumbnail,
                                  width: 54,
                                  height: 54,
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, __, ___) =>
                                      _playlistTrackFallback(),
                                )
                              : _playlistTrackFallback(),
                        ),
                        title: Text(
                          track.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          track.artist,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: Text(
                          track.duration,
                          style: const TextStyle(
                            color: Colors.white54,
                            fontSize: 11,
                          ),
                        ),
                        onTap: () => _selectTrack(
                          track,
                          source: playlist.tracks,
                        ),
                      );
                    },
                    childCount: playlist.tracks.length,
                  ),
                ),
              ),
          ],
        ),
      ),
    ),
  );

  Widget _playlistArtworkFallback() => Container(
    width: 128,
    height: 128,
    decoration: BoxDecoration(
      color: const Color(0xFF31516B),
      borderRadius: BorderRadius.circular(18),
    ),
    child: const Icon(Icons.queue_music_rounded, size: 46),
  );

  Widget _playlistTrackFallback() => Container(
    width: 54,
    height: 54,
    color: const Color(0xFF31516B),
    child: const Icon(Icons.music_note_rounded),
  );

  Widget _searchPanel() => Positioned.fill(child: Material(color: Theme.of(context).scaffoldBackgroundColor.withValues(alpha: .98), child: Column(children: [
    Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 12), child: Row(children: [
      IconButton(onPressed: () => setState(() => _searching = false), icon: const Icon(Icons.arrow_back_rounded)),
      Expanded(child: TextField(controller: _searchController, focusNode: _searchFocus, textInputAction: TextInputAction.search, onSubmitted: _search, decoration: InputDecoration(hintText: 'Search songs, artists...', filled: true, fillColor: Theme.of(context).colorScheme.surfaceContainerHighest, border: OutlineInputBorder(borderRadius: BorderRadius.circular(18), borderSide: BorderSide.none), contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12)))),
      IconButton(onPressed: _searching && _searchController.text.trim().isNotEmpty ? _search : null, icon: const Icon(Icons.search_rounded)),
    ])),
    if (_loading) const Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()),
    if (_error != null) Padding(padding: const EdgeInsets.all(24), child: Text(_error!, textAlign: TextAlign.center, style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .7)))),
    Expanded(child: ListView.builder(itemCount: _results.length, itemBuilder: (context, i) {
      final track = _results[i];
      return ListTile(
        leading: ClipRRect(borderRadius: BorderRadius.circular(10), child: track.thumbnail.isEmpty ? Container(width: 52, height: 52, color: const Color(0xFF31516B), child: const Icon(Icons.music_note_rounded)) : Image.network(track.thumbnail, width: 52, height: 52, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 52, height: 52, color: const Color(0xFF31516B), child: const Icon(Icons.music_note_rounded)))),
        title: Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
        trailing: IconButton(icon: const Icon(Icons.play_arrow_rounded), onPressed: () => _selectTrack(track)),
        onTap: () => _selectTrack(track),
      );
    })),
  ])));

  Widget _sectionTitle(String title, String action, {VoidCallback? onAction}) => Row(children: [Text(title, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800)), const Spacer(), TextButton(onPressed: onAction ?? () => setState(() => _searching = true), child: Text(action))]);

  Widget _trackRow(int index) {
    const titles = ['Midnight City', 'Golden Hour', 'Afterglow', 'Blue Skies'];
    const artists = ['M83', 'JVKE', 'Ed Sheeran', 'Khai Dreams'];
    const colors = [Color(0xFF31516B), Color(0xFF9A704D), Color(0xFF614B72), Color(0xFF3C6C69)];
    return Padding(padding: const EdgeInsets.only(bottom: 13), child: Row(children: [
      Container(width: 54, height: 54, decoration: BoxDecoration(color: colors[index], borderRadius: BorderRadius.circular(13)), child: const Icon(Icons.music_note_rounded, color: Colors.white70)),
      const SizedBox(width: 13),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(titles[index], style: const TextStyle(fontWeight: FontWeight.w700)), const SizedBox(height: 4), Text(artists[index], style: TextStyle(color: Colors.white.withValues(alpha: .55), fontSize: 12))])),
      IconButton(onPressed: () { _searchController.text = titles[index]; setState(() => _searching = true); _search(titles[index]); }, icon: const Icon(Icons.search_rounded)),
    ]));
  }

  Widget _fullPlayer() => Positioned.fill(child: Material(
    color: Theme.of(context).scaffoldBackgroundColor,
    child: SafeArea(child: Column(children: [
      Padding(padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8), child: Row(children: [
        IconButton(onPressed: () => setState(() => _playerExpanded = false), icon: const Icon(Icons.keyboard_arrow_down_rounded, size: 30)),
        const Spacer(),
        const Text('NOW PLAYING', style: TextStyle(letterSpacing: 2, fontSize: 11, fontWeight: FontWeight.w700)),
        const Spacer(),
        IconButton(onPressed: () {}, icon: const Icon(Icons.more_horiz_rounded)),
      ])),
      const Spacer(),
      _playerArtwork(size: min(MediaQuery.of(context).size.width - 72, 340)),
      const Spacer(),
      Padding(padding: const EdgeInsets.symmetric(horizontal: 28), child: Row(children: [
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(_nowTitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
          const SizedBox(height: 6),
          Text(_nowArtist, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: Colors.white.withValues(alpha: .6), fontSize: 16)),
        ])),
        IconButton(onPressed: () {}, icon: const Icon(Icons.favorite_border_rounded, size: 26)),
      ])),
      const SizedBox(height: 24),
      Padding(padding: const EdgeInsets.symmetric(horizontal: 24), child: StreamBuilder<Duration>(
        stream: _playback.player.positionStream,
        builder: (context, snapshot) {
          final position = snapshot.data ?? Duration.zero;
          final duration = _playback.player.duration ?? _currentTrackDuration();
          final maxMs = duration.inMilliseconds > 0 ? duration.inMilliseconds.toDouble() : 1.0;
          return Column(children: [
            Slider(value: position.inMilliseconds.clamp(0, maxMs.toInt()).toDouble(), max: maxMs, onChanged: _playback.player.duration != null && duration > Duration.zero ? (v) => _playback.player.seek(Duration(milliseconds: v.round())) : null),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(_formatDuration(position), style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .6), fontSize: 11)),
              Text(_formatDuration(duration), style: TextStyle(color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .6), fontSize: 11)),
            ]),
          ]);
        },
      )),
      const SizedBox(height: 16),
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        IconButton(iconSize: 34, onPressed: () async { try { await _playback.skipPrevious(); } catch (e) { _showPlaybackError(e); } }, icon: const Icon(Icons.skip_previous_rounded)),
        const SizedBox(width: 22),
        StreamBuilder<PlayerState>(
          stream: _playback.player.playerStateStream,
          builder: (context, snapshot) {
            final playing = snapshot.data?.playing ?? _playing;
            return IconButton(
              iconSize: 38,
              onPressed: () async { try { if (playing) { await _playback.pause(); } else { await _playback.resume(); } if (mounted) setState(() => _playing = !playing); } catch (e) { _showPlaybackError(e); } },
              icon: Icon(playing ? Icons.pause_circle_filled_rounded : Icons.play_circle_fill_rounded, size: 68),
            );
          },
        ),
        const SizedBox(width: 22),
        IconButton(iconSize: 34, onPressed: () async { try { await _playback.skipNext(); } catch (e) { _showPlaybackError(e); } }, icon: const Icon(Icons.skip_next_rounded)),
      ]),
      const Spacer(),
      const SizedBox(height: 22),
    ])),
  ));

  Widget _playerArtwork({required double size, double radius = 28}) {
    final track = _playback.current;
    final thumbnail = track?.thumbnail ?? '';
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: thumbnail.isNotEmpty
          ? Image.network(
              thumbnail,
              width: size,
              height: size,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => _artworkFallback(size, radius),
            )
          : _artworkFallback(size, radius),
    );
  }

  Widget _artworkFallback(double size, double radius) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      color: const Color(0xFF263E59),
      borderRadius: BorderRadius.circular(radius),
    ),
    child: Icon(
      Icons.graphic_eq_rounded,
      size: size * .32,
      color: const Color(0xFF8BC5FF),
    ),
  );

  Duration _currentTrackDuration() {
    final raw = _playback.current?.duration.trim() ?? '';
    final parts = raw.split(':').map(int.tryParse).toList();
    if (parts.length == 2 && parts[0] != null && parts[1] != null) {
      return Duration(minutes: parts[0]!, seconds: parts[1]!);
    }
    if (parts.length == 3 && parts.every((p) => p != null)) {
      return Duration(hours: parts[0]!, minutes: parts[1]!, seconds: parts[2]!);
    }
    return Duration.zero;
  }

  String _formatDuration(Duration value) {
    final seconds = value.inSeconds;
    return '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';
  }

  void _showPlaybackError(Object error) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Playback error: $error')));
  }

  Widget _miniPlayer() => Material(
    color: const Color(0xFF292D35),
    borderRadius: BorderRadius.circular(18),
    child: InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: () => setState(() => _playerExpanded = true),
      child: Container(padding: const EdgeInsets.all(10), decoration: BoxDecoration(borderRadius: BorderRadius.circular(18), border: Border.all(color: Colors.white.withValues(alpha: .08))), child: Row(children: [
        _playerArtwork(size: 42, radius: 11),
        const SizedBox(width: 11),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_nowTitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)), const SizedBox(height: 3), Text(_nowArtist, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .6)))])),
        IconButton(onPressed: () async { try { if (_playing) { await _playback.pause(); } else { await _playback.resume(); } if (mounted) setState(() => _playing = !_playing); } catch (e) { _showPlaybackError(e); } }, icon: Icon(_playing ? Icons.pause_rounded : Icons.play_arrow_rounded)),
      ])),
    ),
  );
}
