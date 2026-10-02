using Microsoft.Win32;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace FlareMusic.Windows;

public partial class MainWindow : Window
{
    private readonly MediaPlayer _player = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private bool _isPlaying;
    private bool _isSeeking;
    private bool _durationKnown;
    private int _currentIndex = -1;

    public ObservableCollection<Track> Tracks { get; } = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;
        _player.MediaOpened += (_, _) => Dispatcher.Invoke(() =>
        {
            _durationKnown = _player.NaturalDuration.HasTimeSpan;
            SeekSlider.Maximum = _durationKnown ? Math.Max(1, _player.NaturalDuration.TimeSpan.TotalSeconds) : 1;
            UpdateTime();
        });
        _player.MediaEnded += (_, _) => Dispatcher.Invoke(NextTrack);
        _timer.Tick += (_, _) => UpdateTime();
        _timer.Start();
        Closed += (_, _) => { _timer.Stop(); _player.Close(); };
    }

    private void AddMusic_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Add music to FlareMusic",
            Filter = "Audio files|*.mp3;*.wav;*.wma;*.m4a;*.aac;*.mid;*.midi;*.aif;*.aiff;*.mp4;*.wmv|All files|*.*",
            Multiselect = true
        };
        if (dialog.ShowDialog(this) != true) return;
        foreach (var path in dialog.FileNames)
        {
            if (Tracks.Any(t => string.Equals(t.Path, path, StringComparison.OrdinalIgnoreCase))) continue;
            Tracks.Add(new Track(path));
        }
        if (_currentIndex < 0 && Tracks.Count > 0) TrackList.SelectedIndex = 0;
    }

    private void TrackList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TrackList.SelectedIndex >= 0) _currentIndex = TrackList.SelectedIndex;
    }

    private void TrackList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (TrackList.SelectedItem is Track track) PlayTrack(Tracks.IndexOf(track));
    }

    private void PlayTrack(int index)
    {
        if (index < 0 || index >= Tracks.Count) return;
        _currentIndex = index;
        TrackList.SelectedIndex = index;
        var track = Tracks[index];
        try
        {
            _player.Open(new Uri(track.Path));
            _player.Play();
            _isPlaying = true;
            _durationKnown = false;
            SeekSlider.Value = 0;
            PlayButton.Content = "Ⅱ";
            NowTitle.Text = track.Title;
            NowArtist.Text = track.Artist;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Could not play this file.\n{ex.Message}", "FlareMusic", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void PlayPause_Click(object sender, RoutedEventArgs e)
    {
        if (_currentIndex < 0)
        {
            if (Tracks.Count > 0) PlayTrack(0);
            return;
        }
        if (_isPlaying) { _player.Pause(); _isPlaying = false; PlayButton.Content = "▶"; }
        else { _player.Play(); _isPlaying = true; PlayButton.Content = "Ⅱ"; }
    }

    private void Previous_Click(object sender, RoutedEventArgs e)
    {
        if (Tracks.Count == 0) return;
        PlayTrack((_currentIndex - 1 + Tracks.Count) % Tracks.Count);
    }

    private void Next_Click(object sender, RoutedEventArgs e) => NextTrack();

    private void NextTrack()
    {
        if (Tracks.Count == 0) return;
        PlayTrack((_currentIndex + 1) % Tracks.Count);
    }

    private void SeekSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isSeeking && _durationKnown) _player.Position = TimeSpan.FromSeconds(SeekSlider.Value);
        UpdateTime();
    }

    private void SeekSlider_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e) => _isSeeking = true;
    private void SeekSlider_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        _isSeeking = false;
        if (_durationKnown) _player.Position = TimeSpan.FromSeconds(SeekSlider.Value);
    }

    private void UpdateTime()
    {
        if (!_isSeeking && _durationKnown && _player.NaturalDuration.HasTimeSpan)
            SeekSlider.Value = Math.Clamp(_player.Position.TotalSeconds, 0, SeekSlider.Maximum);
        var current = _player.Position;
        var total = _durationKnown ? _player.NaturalDuration.TimeSpan : TimeSpan.Zero;
        TimeLabel.Text = $"{FormatTime(current)} / {FormatTime(total)}";
    }

    private static string FormatTime(TimeSpan time) => $"{(int)time.TotalMinutes}:{time.Seconds:00}";
}

public sealed class Track
{
    public string Path { get; }
    public string Title { get; }
    public string Artist => "Local file";
    public string Format => System.IO.Path.GetExtension(Path).TrimStart('.').ToUpperInvariant();
    public Track(string path)
    {
        Path = path;
        Title = System.IO.Path.GetFileNameWithoutExtension(path);
    }
}
