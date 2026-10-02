import 'dart:async';
import 'dart:math' show min;
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:just_audio_background/just_audio_background.dart';
import 'services/inner_tube_client.dart';
import 'services/playback_controller.dart';
import 'services/playlist_library.dart';
import 'services/youtube_account_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Do not leave Android's native splash screen up if the audio plugin fails
  // to initialize on a device. The UI can still start; playback can report
  // its own error when the user tries to play a track.
  try {
    await JustAudioBackground.init(
      androidNotificationChannelId: 'com.flaremusic.playback',
      androidNotificationChannelName: 'FlareMusic playback',
      androidNotificationOngoing: true,
    ).timeout(const Duration(seconds: 8));
  } catch (error, stackTrace) {
    debugPrint('Audio background initialization failed: $error');
    debugPrintStack(stackTrace: stackTrace);
  }
  runApp(const FlareMusicApp());
}

class FlareMusicApp extends StatelessWidget {
  const FlareMusicApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'FlareMusic',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xFF101114),
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF8BC5FF), brightness: Brightness.dark, surface: const Color(0xFF191B20)),
      useMaterial3: true,
    ),
    home: const MusicHome(),
  );
}

class MusicHome extends StatefulWidget {
  const MusicHome({super.key});
  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  final _api = InnerTubeClient();
  final _playback = PlaybackController();
  final _library = PlaylistLibrary();
  final _account = YouTubeAccountService();
  bool _syncing = false;
  List<LocalPlaylist> _playlists = [];
  final _searchController = TextEditingController();
  final _searchFocus = FocusNode();
  final List<OnlineTrack> _results = [];
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
    _restorePlaybackState();
    _refreshLibrary();
    _restoreAccount();
    _playerStateSubscription = _playback.player.playerStateStream.listen((state) {
      if (!mounted) return;
      setState(() => _playing = state.playing);
    });
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

  Future<void> _refreshLibrary() async { final lists = await _library.all(); if (mounted) setState(() => _playlists = lists); }

  Future<void> _accountSync() async {
    setState(() => _syncing = true);
    try {
      if (_account.currentUser == null) await _account.signIn();
      if (_account.currentUser == null) return;
      final synced = await _account.syncPlaylists();
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
      if (_tab == 2) _libraryPage() else if (_tab == 1) _explorePage() else if (_tab == 3) _accountPage() else CustomScrollView(slivers: [
        SliverPadding(padding: const EdgeInsets.fromLTRB(22, 18, 22, 150), sliver: SliverList(delegate: SliverChildListDelegate([
          Row(children: [
            Container(width: 42, height: 42, decoration: BoxDecoration(color: const Color(0xFF8BC5FF).withValues(alpha: .16), borderRadius: BorderRadius.circular(15)), child: const Icon(Icons.graphic_eq_rounded, color: Color(0xFF8BC5FF))),
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
          _sectionTitle('Made for you', 'Refresh'),
          const SizedBox(height: 14),
          Container(height: 176, padding: const EdgeInsets.all(20), decoration: BoxDecoration(gradient: const LinearGradient(colors: [Color(0xFF244D78), Color(0xFF222B42), Color(0xFF30223F)]), borderRadius: BorderRadius.circular(25)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Spacer(), const Text('YOUR DAILY MIX', style: TextStyle(fontSize: 10, letterSpacing: 2, fontWeight: FontWeight.bold)), const SizedBox(height: 6),
            const Text('A little bit of everything', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w800)), const Spacer(),
            const Row(children: [Text('PERSONALIZED PLAYLIST', style: TextStyle(fontSize: 10, letterSpacing: 1.2)), Spacer(), Icon(Icons.arrow_forward_rounded)]),
          ])),
          const SizedBox(height: 28),
          _sectionTitle('Quick picks', 'Search music'),
          const SizedBox(height: 12),
          ...List.generate(4, (i) => _trackRow(i)),
        ]))),
      ]),
      if (_searching) _searchPanel(),
      if (_playerExpanded) _fullPlayer(),
      if (!_playerExpanded) Align(alignment: Alignment.bottomCenter, child: Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 12), child: Column(mainAxisSize: MainAxisSize.min, children: [
        _miniPlayer(),
        const SizedBox(height: 12),
        Container(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9), decoration: BoxDecoration(color: const Color(0xFF22252B).withValues(alpha: .96), borderRadius: BorderRadius.circular(32), border: Border.all(color: Colors.white.withValues(alpha: .07))), child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: List.generate(_tabs.length, (i) {
          final selected = _tab == i;
          return InkWell(borderRadius: BorderRadius.circular(25), onTap: () => setState(() => _tab = i), child: AnimatedContainer(duration: const Duration(milliseconds: 220), padding: EdgeInsets.symmetric(horizontal: selected ? 17 : 14, vertical: 10), decoration: BoxDecoration(color: selected ? const Color(0xFF8BC5FF).withValues(alpha: .16) : Colors.transparent, borderRadius: BorderRadius.circular(24)), child: Row(children: [
            Icon(_tabs[i].$1, size: 20, color: selected ? const Color(0xFF8BC5FF) : Colors.white70),
            if (selected) ...[const SizedBox(width: 7), Text(_tabs[i].$2, style: const TextStyle(color: Color(0xFF8BC5FF), fontWeight: FontWeight.w700, fontSize: 12))],
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
    InkWell(onTap: () { setState(() { _searching = true; _error = null; }); }, borderRadius: BorderRadius.circular(18), child: Container(padding: const EdgeInsets.all(17), decoration: BoxDecoration(color: const Color(0xFF22252B), borderRadius: BorderRadius.circular(18)), child: const Row(children: [Icon(Icons.search_rounded), SizedBox(width: 12), Text('Search songs, artists, albums...'), Spacer(), Icon(Icons.arrow_forward_rounded)]))),
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
    Text('Connect YouTube to sync your playlists.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
    const SizedBox(height: 28),
    Container(padding: const EdgeInsets.all(20), decoration: BoxDecoration(color: const Color(0xFF20242C), borderRadius: BorderRadius.circular(22)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      CircleAvatar(radius: 30, backgroundColor: const Color(0xFF8BC5FF).withValues(alpha: .18), child: const Icon(Icons.person_rounded, size: 32, color: Color(0xFF8BC5FF))),
      const SizedBox(height: 16),
      Text(_account.currentUser?.displayName ?? 'Not signed in', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
      const SizedBox(height: 5),
      Text(_account.currentUser?.email ?? 'Sign in with Google to connect your YouTube account.', style: TextStyle(color: Colors.white.withValues(alpha: .6))),
      const SizedBox(height: 20),
      SizedBox(width: double.infinity, child: FilledButton.icon(
        onPressed: _syncing ? null : _accountSync,
        icon: _syncing ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : Icon(_account.currentUser == null ? Icons.login_rounded : Icons.sync_rounded),
        label: Text(_syncing ? 'Syncing...' : _account.currentUser == null ? 'Sign in with Google' : 'Sync YouTube playlists'),
      )),
      if (_account.currentUser != null) ...[
        const SizedBox(height: 8),
        SizedBox(width: double.infinity, child: OutlinedButton.icon(onPressed: () async { await _account.signOut(); if (mounted) setState(() {}); }, icon: const Icon(Icons.logout_rounded), label: const Text('Sign out'))),
      ],
    ])),
    const SizedBox(height: 22),
    const Text('Your music stays yours', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
    const SizedBox(height: 8),
    Text('Local playlists remain on this device. YouTube sync uses Google sign-in and the read-only YouTube permission.', style: TextStyle(color: Colors.white.withValues(alpha: .6), height: 1.5)),
  ])));

  Widget _libraryPage() => Positioned.fill(child: Container(color: const Color(0xFF101114), child: SafeArea(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
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
                onTap: () { if (p.tracks.isNotEmpty) _selectTrack(p.tracks.first, source: p.tracks); },
              );
            },
          )),
  ]))));

  Widget _searchPanel() => Positioned.fill(child: Material(color: const Color(0xFF101114).withValues(alpha: .98), child: Column(children: [
    Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 12), child: Row(children: [
      IconButton(onPressed: () => setState(() => _searching = false), icon: const Icon(Icons.arrow_back_rounded)),
      Expanded(child: TextField(controller: _searchController, focusNode: _searchFocus, textInputAction: TextInputAction.search, onSubmitted: _search, decoration: InputDecoration(hintText: 'Search songs, artists...', filled: true, fillColor: const Color(0xFF22252B), border: OutlineInputBorder(borderRadius: BorderRadius.circular(18), borderSide: BorderSide.none), contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12)))),
      IconButton(onPressed: _searching && _searchController.text.trim().isNotEmpty ? _search : null, icon: const Icon(Icons.search_rounded)),
    ])),
    if (_loading) const Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()),
    if (_error != null) Padding(padding: const EdgeInsets.all(24), child: Text(_error!, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70))),
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

  Widget _sectionTitle(String title, String action) => Row(children: [Text(title, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800)), const Spacer(), TextButton(onPressed: () => setState(() => _searching = true), child: Text(action))]);

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
    color: const Color(0xFF101114),
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
              Text(_formatDuration(position), style: const TextStyle(color: Colors.white60, fontSize: 11)),
              Text(_formatDuration(duration), style: const TextStyle(color: Colors.white60, fontSize: 11)),
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
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_nowTitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)), const SizedBox(height: 3), Text(_nowArtist, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11, color: Colors.white60))])),
        IconButton(onPressed: () async { try { if (_playing) { await _playback.pause(); } else { await _playback.resume(); } if (mounted) setState(() => _playing = !_playing); } catch (e) { _showPlaybackError(e); } }, icon: Icon(_playing ? Icons.pause_rounded : Icons.play_arrow_rounded)),
      ])),
    ),
  );
}
