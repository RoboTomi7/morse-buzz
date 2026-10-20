# Morse Buzz

Hold a button on a web page and an Android phone vibrates for exactly as long as you hold it, in real time, even with the phone locked in a pocket.

It has two parts. The **Android app** (`app/`) runs a small background service that stays connected to a free public relay (an MQTT broker) and drives the vibration motor. The **sender page** (`docs/index.html`) is a single web page anyone can open on a laptop or phone, with a big hold-to-buzz key.

While the key is held, the page sends "down" about every 400 ms, and it sends "up" when you let go. The phone buzzes from the first "down" until "up" arrives. If the messages stop (closed tab, lost connection), the phone stops by itself after 1.5 seconds, so it can never get stuck buzzing.

## 1. Get the app onto your phone

### Option A: GitHub builds it for you (no Android Studio needed)

1. Create a new **public** repository on GitHub and push this whole folder to it:
   ```
   cd morse-buzz
   git init -b main
   git add .
   git commit -m "Morse Buzz"
   git remote add origin https://github.com/YOURNAME/morse-buzz.git
   git push -u origin main
   ```
   (Uploading through the GitHub website also works, but make sure the hidden `.github` folder comes along. If the **Actions** tab is empty afterwards, create `.github/workflows/build.yml` on the website and paste in the file's contents.)
2. Open the repo's **Actions** tab, then the **Build APK** run. When it finishes (about 3–5 minutes), download **MorseBuzz-apk** at the bottom of the page. It's a zip with `MorseBuzz.apk` inside.
3. Get `MorseBuzz.apk` onto the phone (email it to yourself, Google Drive, or a USB cable) and open it. Android will ask you to allow installs from that app. Allow it, then tap Install. If Play Protect warns about an unknown app, choose **Install anyway**.

### Option B: Android Studio

Open this folder in Android Studio, plug in the phone with USB debugging on, and press Run.

## 2. Put the sender page online

In the GitHub repo, go to **Settings → Pages** and set Source to **GitHub Actions**. Every build then also publishes `docs/` to `https://YOURNAME.github.io/morse-buzz/`, so the page always matches the latest upload.

On a laptop you can also just double-click `docs/index.html` and it works.

## 3. Use it

1. Open Morse Buzz on the phone and tap **Start**. Allow notifications.
2. Tap **Allow running in background**. This lets it keep listening with the screen off.
3. Paste your sender page URL into **Sender page URL**, then tap **Share** to send someone a link with your code already filled in.
4. The sender opens the link and holds the big button or the space bar. You can lock your phone now.

The app shows a small ongoing notification while it's listening, with a **Stop** button. It starts again by itself after a reboot if it was on.

## Tips

- **Phone not buzzing when locked?** Some brands kill background apps aggressively. Samsung: Settings → Battery → Background usage limits → **Never sleeping apps** → add Morse Buzz. Xiaomi/Redmi/POCO: turn on **Autostart** and set Battery saver to **No restrictions**. OnePlus/Oppo/Realme: Battery → allow **background activity**. Other brands: https://dontkillmyapp.com
- **Buzzes on silent:** it uses the alarm vibration channel, so it still buzzes on silent and with Do Not Disturb's default settings. Tap **Stop** when you don't want buzzes.
- **Who can buzz you:** anyone who has your code. The relay is a public test server, and anyone watching all of its traffic could also spot your code, so treat it like a walkie-talkie channel, not something private. Tap **New code** to lock out old senders. If that ever becomes a problem, the fix is a free private broker with a password (for example HiveMQ Cloud's free tier). Both files would need a small change to add the login.
- **Relay down?** Switch both the app and the page to the other relay in the dropdown. They must match.
- **Battery:** keeping the connection and Wi-Fi awake costs roughly a few percent per hour, depending on the phone. Stop it when you're not using it.
- **Speed:** delay is usually 50–200 ms, and it stays about the same from one press to the next, so Morse timing comes through fine. The page's speed slider only affects how it reads what you send and how fast it plays typed text.
