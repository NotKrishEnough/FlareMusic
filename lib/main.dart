import 'package:flutter/material.dart';

void main() => runApp(const FlareMusicApp());

class FlareMusicApp extends StatelessWidget {
  const FlareMusicApp({super.key});

  @override
  Widget build(BuildContext context) {
    const accent = Color(0xFF8BC5FF);
    return MaterialApp(
      title: 'FlareMusic',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF101114),
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          surface: const Color(0xFF191B20),
        ),
        useMaterial3: true,
      ),
      home: const MusicHome(),
    );
  }
}

class MusicHome extends StatefulWidget {
  const MusicHome({super.key});

  @override
  State<MusicHome> createState() => _MusicHomeState();
}

class _MusicHomeState extends State<MusicHome> {
  int _tab = 0;
  bool _playing = false;

  static const _tabs = [
    (Icons.home_rounded, 'Home'),
    (Icons.explore_rounded, 'Explore'),
    (Icons.library_music_rounded, 'Library'),
    (Icons.person_rounded, 'You'),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            CustomScrollView(
              slivers: [
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(22, 18, 22, 130),
                  sliver: SliverList(
                    delegate: SliverChildListDelegate([
                      Row(
                        children: [
                          Container(
                            width: 42,
                            height: 42,
                            decoration: BoxDecoration(
                              color: Theme.of(context).colorScheme.primary.withValues(alpha: .16),
                              borderRadius: BorderRadius.circular(15),
                            ),
                            child: const Icon(Icons.graphic_eq_rounded, color: Color(0xFF8BC5FF)),
                          ),
                          const SizedBox(width: 12),
                          const Text('FlareMusic', style: TextStyle(fontSize: 23, fontWeight: FontWeight.w800)),
                          const Spacer(),
                          IconButton(onPressed: () {}, icon: const Icon(Icons.search_rounded)),
                        ],
                      ),
                      const SizedBox(height: 30),
                      Text('GOOD EVENING', style: TextStyle(fontSize: 11, letterSpacing: 2, color: Colors.white.withValues(alpha: .55), fontWeight: FontWeight.w700)),
                      const SizedBox(height: 8),
                      const Text('Your music,\nyour mood.', style: TextStyle(fontSize: 34, height: 1.12, fontWeight: FontWeight.w800)),
                      const SizedBox(height: 25),
                      _sectionTitle('Made for you', 'Refresh'),
                      const SizedBox(height: 14),
                      Container(
                        height: 176,
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          gradient: const LinearGradient(colors: [Color(0xFF244D78), Color(0xFF222B42), Color(0xFF30223F)]),
                          borderRadius: BorderRadius.circular(25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Spacer(),
                            const Text('YOUR DAILY MIX', style: TextStyle(fontSize: 10, letterSpacing: 2, fontWeight: FontWeight.bold)),
                            const SizedBox(height: 6),
                            const Text('A little bit of everything', style: TextStyle(fontSize: 21, fontWeight: FontWeight.w800)),
                            const Spacer(),
                            const Row(children: [Text('PERSONALIZED PLAYLIST', style: TextStyle(fontSize: 10, letterSpacing: 1.2)), Spacer(), Icon(Icons.arrow_forward_rounded)]),
                          ],
                        ),
                      ),
                      const SizedBox(height: 28),
                      _sectionTitle('Quick picks', 'See all'),
                      const SizedBox(height: 12),
                      ...List.generate(4, (i) => _trackRow(i)),
                    ]),
                  ),
                ),
              ],
            ),
            Align(
              alignment: Alignment.bottomCenter,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _miniPlayer(),
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9),
                      decoration: BoxDecoration(
                        color: const Color(0xFF22252B).withValues(alpha: .96),
                        borderRadius: BorderRadius.circular(32),
                        border: Border.all(color: Colors.white.withValues(alpha: .07)),
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceAround,
                        children: List.generate(_tabs.length, (i) {
                          final selected = _tab == i;
                          return InkWell(
                            borderRadius: BorderRadius.circular(25),
                            onTap: () => setState(() => _tab = i),
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 220),
                              padding: EdgeInsets.symmetric(horizontal: selected ? 17 : 14, vertical: 10),
                              decoration: BoxDecoration(
                                color: selected ? const Color(0xFF8BC5FF).withValues(alpha: .16) : Colors.transparent,
                                borderRadius: BorderRadius.circular(24),
                              ),
                              child: Row(children: [
                                Icon(_tabs[i].$1, size: 20, color: selected ? const Color(0xFF8BC5FF) : Colors.white70),
                                if (selected) ...[
                                  const SizedBox(width: 7),
                                  Text(_tabs[i].$2, style: const TextStyle(color: Color(0xFF8BC5FF), fontWeight: FontWeight.w700, fontSize: 12)),
                                ],
                              ]),
                            ),
                          );
                        }),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(String title, String action) => Row(
    children: [
      Text(title, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800)),
      const Spacer(),
      Text(action, style: const TextStyle(color: Color(0xFF8BC5FF), fontSize: 12, fontWeight: FontWeight.w600)),
    ],
  );

  Widget _trackRow(int index) {
    final titles = ['Midnight City', 'Golden Hour', 'Afterglow', 'Blue Skies'];
    final artists = ['M83', 'JVKE', 'Ed Sheeran', 'Khai Dreams'];
    final colors = [const Color(0xFF31516B), const Color(0xFF9A704D), const Color(0xFF614B72), const Color(0xFF3C6C69)];
    return Padding(
      padding: const EdgeInsets.only(bottom: 13),
      child: Row(children: [
        Container(width: 54, height: 54, decoration: BoxDecoration(color: colors[index], borderRadius: BorderRadius.circular(13)), child: const Icon(Icons.music_note_rounded, color: Colors.white70)),
        const SizedBox(width: 13),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(titles[index], style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text(artists[index], style: TextStyle(color: Colors.white.withValues(alpha: .55), fontSize: 12)),
        ])),
        IconButton(onPressed: () => setState(() => _playing = !_playing), icon: const Icon(Icons.more_horiz_rounded)),
      ]),
    );
  }

  Widget _miniPlayer() => Container(
    padding: const EdgeInsets.all(10),
    decoration: BoxDecoration(color: const Color(0xFF292D35), borderRadius: BorderRadius.circular(18), border: Border.all(color: Colors.white.withValues(alpha: .08))),
    child: Row(children: [
      Container(width: 42, height: 42, decoration: BoxDecoration(color: const Color(0xFF31516B), borderRadius: BorderRadius.circular(11)), child: const Icon(Icons.music_note_rounded)),
      const SizedBox(width: 11),
      const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Nothing playing', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
        SizedBox(height: 3),
        Text('Choose a song to get started', style: TextStyle(fontSize: 11, color: Colors.white60)),
      ])),
      IconButton(onPressed: () => setState(() => _playing = !_playing), icon: Icon(_playing ? Icons.pause_rounded : Icons.play_arrow_rounded)),
    ]),
  );
}
