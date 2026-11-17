# Fridge Raid

An Android app you talk to. Tell it what's in your fridge, tap the herbs, spices and cupboard basics you have, and it searches UK recipe sites for three real recipes that use them. Each recipe links to the page it came from.

## Get the APK

Every push to `main` builds the app on GitHub.

1. Open the repo's **Releases** page on your phone (signed in to GitHub).
2. Open the newest **Fridge Raid build**, then tap **FridgeRaid.apk** to download it.
3. Open the downloaded file. If Android asks, allow your browser to install unknown apps.
4. Open Fridge Raid, paste your Claude API key in Settings, and start.

A new build installs over the old one, because every build is signed with the same key in `signing/`.

## How it works

- **Voice**: Android's built-in speech recogniser (the Google app) turns speech into text; questions are read aloud with the phone's text-to-speech.
- **Understanding the fridge**: `claude-haiku-5-5` tidies what you said into a list of ingredients and suggests which herbs, spices and basics to ask about.
- **Finding recipes**: `claude-sonnet-5-5` with the Claude API's web search tool searches UK recipe sites, picks three recipes and summarises the method. A link is only shown as "Open full recipe" if the URL came back from a real search result; otherwise the button opens a web search for the dish.
- **Your API key** is stored only in the app's private storage on your phone. It is never in this repo.

## Project layout

```
app/src/main/java/uk/fridgeraid/app/
  MainActivity.java   screens, voice in and out
  RecipeEngine.java   prompts, parsing, checking recipe links against search results
  ClaudeClient.java   Messages API calls, web search, pause_turn handling
  FlowLayout.java     wrapping rows of chips
.github/workflows/build-apk.yml   builds and publishes the APK
```

No third-party libraries: the app uses only the Android framework, so the build has nothing to download except the Android Gradle plugin.

## Build it yourself

Open the folder in Android Studio and run it, or with Gradle 8.11 and JDK 17:

```
base64 -d signing/fridgeraid.keystore.b64 > app/fridgeraid.keystore
gradle :app:assembleRelease
```
