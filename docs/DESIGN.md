# KidTube design (approved 2026-11-10)

Source of truth for look and feel. Mockups: Claude design canvas "KidTube Screens" (10 artboards: Setup, Share sheet, Parent library, Kid picker, Kid grid, Player; Urdu RTL for the first four). When code and mockup disagree, ask before deviating.

## Principles
- Friendly and calm. Flat colour, big rounded shapes, no gradients, no emoji, no shadows heavier than a hairline border.
- Kid mode: big thumbnails, almost no text, avatars, no exits. The only way out of the player is "All videos". Leaving kid mode needs the parent PIN.
- Parent mode: clean and fast. Sharing a link = pick child, pick preset, enter PIN, save.
- English and Urdu from day one. Urdu is right-to-left. Every string lives in resources; never hard-code text.

## Colour tokens (Compose `KidTubeColors`)
| Token | Hex | Use |
|---|---|---|
| ground | #FFF8EE | parent screens background |
| kidGround | #FFE9A8 | kid screens background |
| ink | #12343B | main text, dark buttons |
| muted | #4A6670 | secondary text (passes 4.5:1 on ground) |
| card | #FFFFFF | cards, chips |
| line | #EADFCB | card/chip borders |
| primary | #0F766E | primary buttons, selected borders (white text on it) |
| primarySoft | #CFEDE9 | selected chip fill, "Verified" badge (text #0B5B54) |
| coral | #FF7A59 | logo, Ali avatar (never behind white text) |
| sun | #FFC93C | accents, avatar |
| pendingBg / pendingText | #FFE9A8 / #6B4A00 | Pending badge |
| errorBg / errorText | #F6B8A5 / #7A2410 | Unavailable badge |
| thumbnail pastels | #F6B8A5 #BFD9F2 #CFEDE9 #E9D4F5 #FFE9A8 | placeholder thumbnails |

Rule: never white text on coral, sun or pastels; use ink. White text only on primary (#0F766E) or ink.
Dark mode: not in v1. Force light theme.

## Typography
- Latin: Nunito (500, 700, 800). Urdu: Noto Naskh Arabic (500, 700). Bundle both as font resources (res/font) so they work offline. Do not use Nastaliq (illegible at small sizes).
- Sizes (sp): kid title 38, screen title 28-34, section label 15-17 bold muted, body 16-17, badge/caption 13-14. Urdu: add about 10-15% more line height (1.6-1.8) and about 1-2 sp larger.
- Weights: titles 800 (Urdu 700), buttons 800 (Urdu 700).

## Shape and spacing
- Screen padding 20-24dp. Card radius 18-24dp, chips fully rounded, big kid tiles 28-40dp, buttons 20dp radius.
- Min touch target 48dp (parent), kid tiles at least 150dp tall.
- Borders 2dp `line`; selected 3dp `primary`.
- Gaps: 8 / 10 / 12 / 16 / 20 / 24.

## Components to build once
PrimaryButton (60dp, primary), BigChoiceCard (Setup), Chip (selected / unselected), StatusBadge (Verified / Pending / Unavailable), VideoRow (96x64 thumb + title + badge + child/preset), KidAvatar (circle + simple face, colour per child), KidTile (thumbnail + centred play circle, optional "First" tag top-start, check mark top-end when watched), LeftTodayDots (filled = left), TimeLeftBar, PinField (password, centred, spaced), LanguageToggle (English | اردو).

## Screens
1. Setup: logo, language toggle, "Whose phone is this?", cards Parent phone / Kid's phone.
2. Share sheet (Android share target): link card, Who is it for? (child chips, multi), How should it appear? (Once a day, 3 times total, Must watch first, Library only), Parent PIN, Save for <child>. Offline note.
3. Parent library: title, Kid mode button, settings, child filter chips, VideoRows, bottom nav Library / Plans / Reports.
4. Kid picker: "Who is watching?", big avatar tiles, small "Parents" lock button.
5. Kid grid: avatar + name, dots left today, big "First" tile, 2-column tiles, minutes left bar.
6. Player: video area, progress bar, title, one big "All videos" button.
Still to design: Done for today, Time's up, PIN entry, plan editor, projection, reports, pairing code entry.

## RTL and language
- Use start/end (never left/right) in Compose; mirror directional icons (back arrow, play triangle only inside the Urdu layouts as in mockups).
- Keep Latin things LTR inside Urdu: "KidTube" wordmark, PIN digits, numbers.
- Per-app language: AppCompatDelegate.setApplicationLocales; resources `values/strings.xml` (en) and `values-ur/strings.xml`. Setup screen toggle switches it immediately and saves it.
- Urdu copy in the mockups is a first draft; keep it in strings.xml so a native reader can fix it.