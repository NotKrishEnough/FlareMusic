# FlareMusic Privacy Policy

**Effective date: September 30, 2026**

FlareMusic is an Android music player maintained by the FlareMusic project. This policy explains what information the app accesses and how it is handled.

## Information the app accesses

### Music on your device
If you grant Android media permission, FlareMusic reads audio files and related information available through Android's MediaStore, such as track title, artist, album, duration, and the audio file's content URI. This is used to display and play music on your device. FlareMusic does not upload your local audio files to a FlareMusic server.

### Google account and YouTube playlists
If you choose to connect Google, Android's Google authorization flow requests the YouTube read-only permission (`youtube.readonly`). FlareMusic uses the resulting access token to request playlist information from the YouTube Data API, such as playlist titles, descriptions, item counts, and thumbnails, so it can show your playlists in the app.

FlareMusic does not ask for or receive your Google password. The app's current implementation keeps the access token in memory while the app process is running; it does not intentionally save that token to its own persistent storage. You can stop using this feature by disconnecting/revoking FlareMusic's access in your Google Account permissions.

Google handles sign-in and YouTube API requests under Google's own privacy policy and terms. Review [Google's Privacy Policy](https://policies.google.com/privacy) and [YouTube's Terms of Service](https://www.youtube.com/t/terms).

## How information is used and shared

The information described above is used to provide music-library and playlist features. The current app implementation does not send this information to a FlareMusic-operated backend or sell it to advertisers. Requests to Google for YouTube playlist data are made directly from the app using your authorization.

## Data retention and control

Local music access is governed by Android permissions. You can remove the app's media permission in Android settings.

The Google access token is held in app memory for the running process. You can revoke the app's Google access from your Google Account security/third-party connections settings. Revoking access may prevent playlist sync until you authorize again.

## Security

FlareMusic uses Android and Google's authorization mechanisms for the features described above. No method of electronic storage or transmission is completely secure.

## Children's privacy

FlareMusic is not designed to collect personal information from children, and the project does not knowingly collect personal information through a FlareMusic-operated server.

## Changes to this policy

This policy may be updated as app features change. The effective date above will be revised when changes are made.

## Contact

For privacy questions or requests, contact: [routpriti30@gmail.com](mailto:routpriti30@gmail.com).
