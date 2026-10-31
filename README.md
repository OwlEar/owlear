# OwlEar

🌐 **English** · [Español](docs/README.es.md) · [Português](docs/README.pt-BR.md) · [Français](docs/README.fr.md) · [Deutsch](docs/README.de.md) · [Italiano](docs/README.it.md) · [Nederlands](docs/README.nl.md) · [Polski](docs/README.pl.md) · [Українська](docs/README.uk.md) · [Türkçe](docs/README.tr.md) · [العربية](docs/README.ar.md) · [فارسی](docs/README.fa.md) · [עברית](docs/README.he.md)

**A free phone app that warns you when you might be tracked, watched or recorded.**

OwlEar is a free app for Android phones. It quietly listens to the wireless signals around you and lets you know when something nearby could be used to follow you or record you.

It costs nothing, has no ads, needs no account, and never sends anything off your phone.

## What it warns you about

- **A hidden tracker following you.** For example an AirTag, Tile, Samsung SmartTag, Chipolo or Google tag slipped into your bag, coat or car. OwlEar only warns you when the same tracker stays with you for a while and shows up in several different places, so a stranger's keys on the bus won't set it off.
- **License-plate cameras.** Flock Safety cameras that photograph every car that drives past.
- **Police equipment.** Axon body cameras and Tasers, and the Wi-Fi used in some police vehicles, including unmarked ones.
- **Drones** flying nearby that broadcast their required ID.
- **Smart glasses that can record you,** such as Ray-Ban and Oakley Meta glasses.
- **Some hidden cameras,** like cheap Wi-Fi cameras that haven't been fully set up yet. Useful when checking a rental or hotel room.
- **Fake cell towers.** Warning signs that someone may be using a device that pretends to be a phone tower so it can spy on phones nearby.
- **Anything else you choose.** You can add your own items to watch for.

## You decide what it watches for

- **Turn each kind of device on or off.** For every type of tracker, camera or device, choose **Alert** (your phone buzzes), **Show only** (it appears in the app quietly) or **Off** (OwlEar ignores it completely).
- **Save the places you go often,** like home or work. At a saved place you can silence warnings for equipment you already know is there. A tracker following you will still always warn you, even at home.
- **"Regular here" labels.** Equipment that shows up at one of your saved places on several different days gets a small label so you know it's normally there. It's only a label. It never silences a warning.

## Your privacy comes first

- OwlEar **cannot use the internet**. It doesn't even ask for permission to. Nothing it sees ever leaves your phone.
- No account, no sign-up, no ads and no tracking of you.
- What it hears is kept for a few hours and then forgotten.
- It only listens. It never connects to, blocks or interferes with any device.
- The few things it saves (your places, your choices and your watch list) are locked with a key that never leaves your phone. **Erase everything** destroys that key, so nothing can be recovered.
- All of the app's code is public, so anyone can check that it does what it says. Read the full [privacy policy](PRIVACY.md).

## How to install it

OwlEar is not in the Google Play Store. You install it straight from this page.

1. On your Android phone, open the [Releases page](https://github.com/OwlEar/owlear/releases).
2. Under the newest version, tap **owlear.apk** to download it.
3. Open the downloaded file. If your phone asks, allow it to install apps from this source.
4. Open OwlEar and tap **Start listening**. When it asks, allow **Nearby devices** and **Location**. The app needs these to hear signals, and your location stays on your phone.

New versions install right over the old one, and your settings stay. If you installed version 0.2.0 or older, uninstall it once before installing the newest version. That's only needed one time.

### Check that your copy is the real OwlEar

Every OwlEar release is signed with the same key. If someone changed the app, the signature won't match. This is the key's fingerprint:

```
BE:61:47:AE:99:A9:B5:1A:41:BE:FB:C1:73:47:A5:FC:DC:4A:CB:CE:CB:82:4A:35:55:C2:FA:4E:96:0B:B3:E4
```

Apps like [Obtainium](https://github.com/ImranR98/Obtainium) and [AppVerifier](https://github.com/soupslurpr/AppVerifier) can compare it for you. Each release also includes an `owlear.apk.sha256` file you can use to check the download.

You need Android 8 or newer. There is no iPhone version yet. The app uses your phone's language. On Android 13 or newer you can also pick its language in your phone's settings.

## How to use it

- Tap **Start listening**. OwlEar keeps listening even when the app is closed, and sends you an alert when something important shows up.
- The **radar** shows what's around you. The closer a dot is to the middle, the closer that thing is to you.
- To find which way something is, tap **Find direction**. Hold the phone flat against your chest and turn slowly in a full circle.
- If a tracker turns out to be yours or your family's, tap it and choose **Ignore**.

## If OwlEar warns you

- **A tracker is following you:** if you can, don't go straight home. Go somewhere public and safe, then check your bag, pockets, coat and car. If you feel unsafe, call someone you trust, a local support line or emergency services.
- **Any other warning:** treat it as a heads-up, not proof. Look around and decide for yourself.

## Good to know

- No phone can detect radar or laser speed guns, so OwlEar can't either.
- Distance and direction are rough guesses. Walls and people block signals.
- It can't find every hidden camera or every piece of police equipment. Many devices hide who made them.
- **Don't use your phone while driving.** Let the alerts come to you.

## Help out

- **Developers:** how detection works, how to build the app and how to add new devices are in the [technical guide](docs/TECHNICAL.md).
- **Translators:** these pages and the app screens were translated with the help of AI. If you speak one of these languages and see a mistake, please open an issue or a pull request. The app's words live in `app/src/main/res/values-*/strings.xml`.

## License

MIT. Free to use, copy and share.
