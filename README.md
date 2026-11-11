# Kruger Checklist (with Mesh)

The checklist app plus a **Mesh** tab that connects to a Meshtastic radio over Bluetooth.
It runs as a website you add to your home screen. After the first visit it works with no signal.

## Put it online (once, about 10 minutes)

1. Make a free account at github.com.
2. Tap **+** → **New repository**. Name it `kruger-checklist`, leave it **Public**, tap **Create repository**.
3. Tap **uploading an existing file**, add all five files from this folder
   (`index.html`, `sw.js`, `manifest.webmanifest`, `icon-192.png`, `icon-512.png`), then **Commit changes**.
4. Go to **Settings → Pages**. Under *Branch* pick `main` and `/ (root)`, then **Save**.
5. After a minute or two the link appears at the top of that page, like
   `https://YOUR-NAME.github.io/kruger-checklist/`. That's the link for the crew.

Public means anyone can see the code. Nobody's ticks, notes or radio data go online; they stay on each phone.

## On each phone

1. Open the link in **Chrome** (with signal).
2. Wait for "Saved on this phone. It works offline now."
3. Chrome menu (⋮) → **Add to Home screen** / **Install app**. Open it from that icon from then on.

To move your history over from the old file: in the old file, ⋮ → **Download backup**;
in the new app, ⋮ → **Restore backup**.

## Updating

Upload the new `index.html` to the same repository (replace the old one).
Phones pick it up the next time they open the app with signal.

## Mesh tab

- **Android:** Chrome.
- **iPhone:** install the free **iOSWebBLE** app (App Store), turn on its extension in Safari
  (Settings → Apps → Safari → Extensions, and allow it on every website), then open the link in **Safari**.
  Use it in a Safari tab rather than from a home-screen icon, since extensions may not run there.
  Not yet tested with this app, so try it with signal first.
- The radio talks to one app at a time: disconnect the Meshtastic app before connecting here, and the other way round.
- The first time, Chrome shows a list of radios and may ask for the radio's PIN. After that it reconnects by itself where Chrome allows, or with one tap.
- If Android puts the page to sleep, the link drops. The tab turns red and says how long ago; tap **Reconnect**.
  Setting Chrome's battery use to *Unrestricted* in Android settings helps it stay connected.
- Each node shows when it was last heard and how old its position is. Positions are only as fresh as each radio sends them.
- **Keep the Meshtastic app as the backup for finding a downed drone** until this has been tested in the field.
- **Try demo** shows made-up nodes so you can see the layout anywhere.
