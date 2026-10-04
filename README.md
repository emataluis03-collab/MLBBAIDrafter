# MLBB AI Lineup Drafter

1. Create a GitHub repository
2. Upload all contents of this ZIP (unzip first; include the hidden .github folder)
3. Go to Actions
4. Run "Build MLBB AI Drafter APK"
5. Download the APK artifact

## Read-only draft screen detection
This build adds an optional Android MediaProjection capture path. It only reads the screen and never injects taps, picks, bans, or other game input.

Flow: MLBB draft screen -> slot crop -> local hero-template comparison -> detected hero label/line -> existing draft engine.

The app can cache hero avatar templates from a public community asset repository through the in-app "DOWNLOAD HERO DETECTION TEMPLATES" button. The template matcher is intentionally conservative; if confidence is below threshold, it does not change the draft state.

The draft slot geometry is based on the supplied 1536x1067 reference screenshot and is normalized so it can be calibrated later for a different aspect ratio/layout.
