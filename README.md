# Gem Rush

A multiplayer quiz game for 2–3 players (ages 8–12). Questions fall from the sky as gems worth 1, 3 or 5 points. Grab one by tapping it 3 times or tilting the phone, then answer within 15 seconds. Right answers add points, wrong answers take them away. 5 sets per game.

## Install on a phone
Open this link on the phone and install the file:
https://github.com/pagarwaltsk-dot/gem-rush/releases/latest/download/GemRush.apk

## How to play together (no internet needed)
1. Phone A: **Host a game**. Allow the permission it asks for, and keep Location on.
2. Phones B and C: **Join a game** → **Scan QR code** → tap **Connect** if the phone asks.
3. Phone A: **Start game**.

## Files
- `app/src/main/assets/index.html`: the game
- `app/src/main/assets/questions.js`: the question bank (one line per question: `Subject|Question|Correct|Wrong|Wrong|Wrong`)
- `app/src/main/java/com/gemrush/quiz/MainActivity.kt`: hotspot, QR scanning, tilt
- Every change to `main` builds a new app automatically (see the Actions tab).
