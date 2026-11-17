package uk.fridgeraid.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int REQ_SPEECH = 41;

    private static final int STAGE_SETTINGS = 0;
    private static final int STAGE_FRIDGE = 1;
    private static final int STAGE_HERBS = 2;
    private static final int STAGE_RECIPES = 3;

    private static final String FIELD_FRIDGE = "fridge";
    private static final String FIELD_EXTRA = "extra";
    private static final String FIELD_CHANGE = "change";

    private static final String PREF_KEY = "api_key";
    private static final String PREF_SPEAK = "speak_questions";

    private static final String EXAMPLE_FRIDGE =
            "half a roast chicken, two leeks, a block of cheddar, a tub of crème fraîche, a bag of spinach and some mushrooms";

    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    // What the cook has told us so far
    private int stage = STAGE_FRIDGE;
    private int stageBeforeSettings = STAGE_FRIDGE;
    private String fridgeText = "";
    private String question = "";
    private String extraText = "";
    private String changeText = "";
    private String keyDraft = "";
    private final ArrayList<String> ingredients = new ArrayList<>();
    private final ArrayList<Toggle> herbs = new ArrayList<>();
    private final ArrayList<Toggle> staples = new ArrayList<>();
    private final ArrayList<RecipeEngine.Recipe> recipes = new ArrayList<>();
    private final ArrayList<String> searches = new ArrayList<>();
    private boolean busy;
    private String busyMessage = "";
    private String errorMessage = "";
    private String speechField = FIELD_FRIDGE;

    // Palette: a fridge door (cool grey-blue), white shelves, leaf green for go, magnet yellow for highlights
    private int cDoor, cShelf, cInk, cSoft, cLine, cLeaf, cOnLeaf, cMagnet, cOnMagnet, cAlert;

    private ScrollView scroll;
    private LinearLayout pageView;

    private static final class Toggle {
        final String name;
        boolean on;
        Toggle(String name) { this.name = name; }
    }

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("fridge_raid", Context.MODE_PRIVATE);
        setPalette();

        Window w = getWindow();
        w.setStatusBarColor(cDoor);
        w.setNavigationBarColor(cDoor);
        if (!isNight()) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(cDoor);
        scroll.setFitsSystemWindows(true);
        pageView = new LinearLayout(this);
        pageView.setOrientation(LinearLayout.VERTICAL);
        pageView.setPadding(dp(20), dp(16), dp(20), dp(48));
        scroll.addView(pageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        tts = new TextToSpeech(this, this);

        if (apiKey().isEmpty()) {
            stageBeforeSettings = STAGE_FRIDGE;
            stage = STAGE_SETTINGS;
        }
        render();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int r = tts.setLanguage(Locale.UK);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsReady) ttsReady = tts.setLanguage(Locale.ENGLISH) >= 0;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        if (stage == STAGE_SETTINGS && !apiKey().isEmpty()) { go(stageBeforeSettings); return; }
        if (stage == STAGE_RECIPES) { go(STAGE_HERBS); return; }
        if (stage == STAGE_HERBS) { go(STAGE_FRIDGE); return; }
        super.onBackPressed();
    }

    // ================================================================ voice

    private void listen(String field) {
        if (busy) return;
        stopSpeaking();
        speechField = field;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, FIELD_FRIDGE.equals(field)
                ? "What's in your fridge?"
                : FIELD_EXTRA.equals(field) ? "Which herbs and spices do you have?" : "What would you like instead?");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            toast("Voice input isn't available on this phone. Install or update the Google app, or type instead.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        String said = heard.get(0).trim();
        if (said.isEmpty()) return;
        if (FIELD_FRIDGE.equals(speechField)) fridgeText = join(fridgeText, said);
        else if (FIELD_EXTRA.equals(speechField)) extraText = join(extraText, said);
        else changeText = join(changeText, said);
        render();
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fridge-raid");
    }

    private void stopSpeaking() {
        if (tts != null) tts.stop();
    }

    // ================================================================ actions

    private void submitFridge() {
        hideKeyboard();
        if (fridgeText.trim().isEmpty()) {
            errorMessage = "Say or type at least one thing from your fridge first.";
            render();
            return;
        }
        final String key = apiKey();
        final String said = fridgeText.trim();
        runBusy("Looking in your fridge…", new Job() {
            @Override public Object run() throws Exception { return RecipeEngine.readFridge(key, said); }
            @Override public void done(Object result) {
                RecipeEngine.Fridge f = (RecipeEngine.Fridge) result;
                if (f.ingredients.isEmpty()) {
                    errorMessage = "I couldn't pick out any food from that. Try listing the items, like \"eggs, peppers, feta\".";
                    render();
                    return;
                }
                ingredients.clear();
                ingredients.addAll(f.ingredients);
                question = f.question;
                herbs.clear();
                for (String h : f.herbs) herbs.add(new Toggle(h));
                staples.clear();
                for (String s : f.staples) staples.add(new Toggle(s));
                recipes.clear();
                changeText = "";
                go(STAGE_HERBS);
                if (prefs.getBoolean(PREF_SPEAK, true)) speak(question);
            }
        });
    }

    private void submitRecipes(final boolean different) {
        hideKeyboard();
        stopSpeaking();
        if (ingredients.isEmpty()) {
            errorMessage = "Add at least one ingredient first.";
            render();
            return;
        }
        final String key = apiKey();
        final List<String> ing = new ArrayList<>(ingredients);
        final List<String> h = picked(herbs);
        final List<String> s = picked(staples);
        final String extra = extraText.trim();
        final String change = different ? changeText.trim() : "";
        final List<String> avoid = new ArrayList<>();
        if (different) for (RecipeEngine.Recipe r : recipes) avoid.add(r.title);

        runBusy("Searching recipe sites… this can take up to a minute.", new Job() {
            @Override public Object run() throws Exception {
                return RecipeEngine.findRecipes(key, ing, h, s, extra, change, avoid);
            }
            @Override public void done(Object result) {
                RecipeEngine.Found f = (RecipeEngine.Found) result;
                recipes.clear();
                recipes.addAll(f.recipes);
                searches.clear();
                searches.addAll(f.queries);
                go(STAGE_RECIPES);
            }
        });
    }

    private interface Job {
        Object run() throws Exception;
        void done(Object result);
    }

    private void runBusy(String message, final Job job) {
        if (busy) return;
        busy = true;
        busyMessage = message;
        errorMessage = "";
        render();
        worker.execute(new Runnable() {
            @Override public void run() {
                Object result = null;
                Exception failure = null;
                try {
                    result = job.run();
                } catch (Exception e) {
                    failure = e;
                }
                final Object r = result;
                final Exception f = failure;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        busy = false;
                        if (f != null) {
                            errorMessage = friendly(f);
                            render();
                        } else {
                            job.done(r);
                        }
                    }
                });
            }
        });
    }

    private String friendly(Exception e) {
        if (e instanceof ClaudeClient.ApiException) {
            ClaudeClient.ApiException a = (ClaudeClient.ApiException) e;
            String msg = a.getMessage() == null ? "" : a.getMessage().toLowerCase(Locale.ROOT);
            if (a.status == 401 || "authentication_error".equals(a.type))
                return "Your API key was rejected. Open Settings and paste the key again.";
            if (a.status == 403 || "permission_error".equals(a.type))
                return "Your API key isn't allowed to do this. Check the key's workspace in the Claude Console.";
            if (msg.contains("credit") || msg.contains("billing"))
                return "Your API account has no credit. Add credit on the Billing page in the Claude Console, then try again.";
            if (msg.contains("web search") && msg.contains("enable"))
                return "Web search is turned off for your API account. An admin can turn it on in the Claude Console under Settings → Privacy.";
            if (a.status == 429 || "rate_limit_error".equals(a.type))
                return "Too many requests in a short time. Wait a minute, then try again.";
            if (a.status == 529 || a.status >= 500 || "overloaded_error".equals(a.type))
                return "Claude is busy right now. Wait a minute, then try again.";
            return "Claude couldn't do that: " + a.getMessage();
        }
        if (e instanceof IOException)
            return "No connection. Check your mobile data or Wi-Fi, then try again.";
        if (e instanceof JSONException)
            return "The answer came back garbled. Tap the button to try again.";
        return "Something went wrong. Tap the button to try again.";
    }

    private void go(int newStage) {
        if (newStage == STAGE_SETTINGS && stage != STAGE_SETTINGS) {
            stageBeforeSettings = stage;
            keyDraft = "";
        }
        stage = newStage;
        errorMessage = "";
        render();
        scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private void restart() {
        stopSpeaking();
        fridgeText = "";
        question = "";
        extraText = "";
        changeText = "";
        ingredients.clear();
        herbs.clear();
        staples.clear();
        recipes.clear();
        searches.clear();
        go(STAGE_FRIDGE);
    }

    // ================================================================ screens

    private void render() {
        pageView.removeAllViews();
        header();
        switch (stage) {
            case STAGE_SETTINGS: renderSettings(); break;
            case STAGE_FRIDGE: renderFridge(); break;
            case STAGE_HERBS: renderHerbs(); break;
            default: renderRecipes(); break;
        }
    }

    private void header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Fridge ");
        title.setTextSize(34);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setTextColor(cInk);
        title.setIncludeFontPadding(false);
        row.addView(title);

        TextView magnet = new TextView(this);
        magnet.setText("Raid");
        magnet.setTextSize(34);
        magnet.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        magnet.setTextColor(cOnMagnet);
        magnet.setIncludeFontPadding(false);
        magnet.setPadding(dp(8), dp(2), dp(8), dp(4));
        magnet.setBackground(rounded(cMagnet, 8, 0));
        magnet.setRotation(-2f);
        row.addView(magnet);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        if (stage != STAGE_SETTINGS) {
            TextView settings = linkButton("Settings");
            settings.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (!busy) go(STAGE_SETTINGS); }
            });
            row.addView(settings);
        }
        add(row, 4);

        if (stage != STAGE_SETTINGS) {
            LinearLayout steps = new LinearLayout(this);
            steps.setOrientation(LinearLayout.HORIZONTAL);
            String[] names = {"1 Fridge", "2 Herbs & spices", "3 Recipes"};
            for (int i = 0; i < 3; i++) {
                int n = i + 1;
                TextView t = new TextView(this);
                t.setText(names[i]);
                t.setTextSize(12);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(n == stage ? cInk : cSoft);
                t.setPadding(0, dp(6), dp(4), 0);
                int bar = n == stage ? cLeaf : (n < stage ? cInk : cLine);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                View line = new View(this);
                line.setBackgroundColor(bar);
                cell.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
                cell.addView(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = i < 2 ? dp(6) : 0;
                steps.addView(cell, lp);
            }
            add(steps, 22);
        }
    }

    private void renderSettings() {
        add(h2("Settings"), 10);
        add(body("Fridge Raid uses your own Claude API key to search recipe sites. The key is kept only on this phone."), 16);

        String saved = apiKey();
        if (!saved.isEmpty()) {
            String tail = saved.length() > 4 ? saved.substring(saved.length() - 4) : "";
            add(small("Key saved: sk-ant-…" + tail), 12);
        }

        add(label(saved.isEmpty() ? "Paste your API key" : "Replace your API key"), 6);
        final EditText key = field("sk-ant-…", false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(keyDraft);
        key.addTextChangedListener(new Watcher() { @Override void changed(String s) { keyDraft = s; } });
        add(key, 12);

        TextView save = primary("Save key");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String k = keyDraft.trim().replaceAll("\\s+", "");
                if (!k.startsWith("sk-ant-")) {
                    errorMessage = "That doesn't look like a Claude API key. It should start with sk-ant-.";
                    render();
                    return;
                }
                prefs.edit().putString(PREF_KEY, k).apply();
                keyDraft = "";
                hideKeyboard();
                toast("Key saved");
                go(stageBeforeSettings == STAGE_SETTINGS ? STAGE_FRIDGE : stageBeforeSettings);
            }
        });
        add(save, 10);
        errorView();

        TextView get = linkButton("Get a key from the Claude Console");
        get.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open("https://platform.claude.com/settings/keys"); }
        });
        add(get, 18);

        final CheckBox speakBox = new CheckBox(this);
        speakBox.setText("Read questions aloud");
        speakBox.setTextColor(cInk);
        speakBox.setTextSize(16);
        speakBox.setChecked(prefs.getBoolean(PREF_SPEAK, true));
        speakBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(PREF_SPEAK, on).apply());
        add(speakBox, 18);

        if (!saved.isEmpty()) {
            TextView remove = linkButton("Remove saved key");
            remove.setTextColor(cAlert);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().remove(PREF_KEY).apply();
                    toast("Key removed");
                    render();
                }
            });
            add(remove, 10);

            TextView back = linkButton("Back");
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { hideKeyboard(); go(stageBeforeSettings); }
            });
            add(back, 10);
        }

        add(small("Each recipe search costs a few pence of API credit. Recipes are found with Claude's web search tool and link to the page they came from."), 0);
    }

    private void renderFridge() {
        add(h2("What's in your fridge?"), 14);

        LinearLayout micRow = new LinearLayout(this);
        micRow.setOrientation(LinearLayout.HORIZONTAL);
        micRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mic = micButton(84, FIELD_FRIDGE);
        micRow.addView(mic);
        TextView micLabel = new TextView(this);
        micLabel.setText("Tap and say everything you can see");
        micLabel.setTextSize(19);
        micLabel.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        micLabel.setTextColor(cInk);
        micLabel.setPadding(dp(16), 0, 0, 0);
        micRow.addView(micLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(micRow, 18);

        add(label("Your ingredients"), 6);
        final EditText box = field("e.g. half a roast chicken, two leeks, cheddar, spinach, some mushrooms", true);
        box.setText(fridgeText);
        box.addTextChangedListener(new Watcher() { @Override void changed(String s) { fridgeText = s; } });
        add(box, 8);

        TextView example = linkButton("Fill in an example fridge");
        example.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fridgeText = EXAMPLE_FRIDGE; render(); }
        });
        add(example, 14);

        busyOr(primary("Next: herbs and spices"), new View.OnClickListener() {
            @Override public void onClick(View v) { submitFridge(); }
        });
        errorView();

        add(small("1  Say or type what's in the fridge.\n2  Tap the herbs, spices and basics you have.\n3  Get three real recipes from UK recipe sites."), 0);
    }

    private void renderHerbs() {
        add(groupTitle("In your fridge"), 8);
        FlowLayout magnets = new FlowLayout(this, dp(8));
        for (int i = 0; i < ingredients.size(); i++) {
            final int idx = i;
            final String name = ingredients.get(i);
            TextView m = new TextView(this);
            m.setText(name + "   ×");
            m.setTextSize(15);
            m.setTypeface(Typeface.DEFAULT_BOLD);
            boolean yellow = i % 3 == 1;
            m.setTextColor(yellow ? cOnMagnet : cDoor);
            m.setBackground(rounded(yellow ? cMagnet : cInk, 8, 0));
            m.setPadding(dp(12), dp(8), dp(12), dp(8));
            m.setRotation(i % 3 == 0 ? -1.5f : (i % 3 == 1 ? 1f : -0.5f));
            m.setContentDescription("Remove " + name);
            m.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (busy) return;
                    if (idx < ingredients.size()) ingredients.remove(idx);
                    render();
                }
            });
            magnets.addView(m);
        }
        add(magnets, 20);

        TextView q = new TextView(this);
        q.setText(question);
        q.setTextSize(23);
        q.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        q.setTextColor(cInk);
        q.setLineSpacing(0, 1.05f);
        add(q, 6);
        if (ttsReady) {
            TextView again = linkButton("Read this question aloud");
            again.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { speak(question); }
            });
            add(again, 14);
        }

        add(groupTitle("Herbs and spices. Tap the ones you have"), 8);
        add(toggles(herbs), 18);
        add(groupTitle("Cupboard basics"), 8);
        add(toggles(staples), 18);

        add(label("Anything else? Other spices, or how you want to eat"), 6);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText extra = field("e.g. garam masala, and nothing too spicy", false);
        extra.setText(extraText);
        extra.addTextChangedListener(new Watcher() { @Override void changed(String s) { extraText = s; } });
        row.addView(extra, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mic = micButton(52, FIELD_EXTRA);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        mlp.leftMargin = dp(10);
        row.addView(mic, mlp);
        add(row, 18);

        busyOr(primary("Find real recipes"), new View.OnClickListener() {
            @Override public void onClick(View v) { submitRecipes(false); }
        });
        errorView();

        TextView back = linkButton("Change what's in the fridge");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!busy) go(STAGE_FRIDGE); }
        });
        add(back, 0);
    }

    private void renderRecipes() {
        add(h2("Three recipes for your fridge"), 6);
        if (!searches.isEmpty()) {
            add(small("Searched: " + String.join(" · ", searches)), 16);
        }

        for (final RecipeEngine.Recipe r : recipes) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rounded(cShelf, 20, cLine));
            card.setPadding(dp(18), dp(18), dp(18), dp(18));

            TextView t = new TextView(this);
            t.setText(r.title);
            t.setTextSize(22);
            t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            t.setTextColor(cInk);
            cardAdd(card, t, 4);

            StringBuilder meta = new StringBuilder();
            if (!r.sourceName.isEmpty()) meta.append(r.sourceName);
            if (r.minutes > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append(r.minutes).append(" min");
            if (r.serves > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append("Serves ").append(r.serves);
            if (meta.length() > 0) {
                TextView m = small(meta.toString());
                m.setTypeface(Typeface.DEFAULT_BOLD);
                cardAdd(card, m, 10);
            }
            if (!r.summary.isEmpty()) cardAdd(card, body(r.summary), 12);

            if (!r.uses.isEmpty()) {
                cardAdd(card, groupTitle("Uses what you have"), 6);
                cardAdd(card, tags(r.uses, false), 12);
            }
            if (!r.missing.isEmpty()) {
                cardAdd(card, groupTitle("You'd also need"), 6);
                cardAdd(card, tags(r.missing, true), 12);
            }

            cardAdd(card, groupTitle("Method"), 6);
            for (int i = 0; i < r.steps.size(); i++) {
                LinearLayout step = new LinearLayout(this);
                step.setOrientation(LinearLayout.HORIZONTAL);
                TextView n = new TextView(this);
                n.setText(String.valueOf(i + 1));
                n.setTextSize(17);
                n.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                n.setTextColor(cLeaf);
                step.addView(n, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView s = body(r.steps.get(i));
                step.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cardAdd(card, step, 8);
            }

            if (!r.tip.isEmpty()) {
                LinearLayout tip = new LinearLayout(this);
                tip.setOrientation(LinearLayout.HORIZONTAL);
                View bar = new View(this);
                bar.setBackgroundColor(cMagnet);
                tip.addView(bar, new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT));
                TextView tt = small(r.tip);
                tt.setTextColor(cInk);
                tt.setPadding(dp(12), 0, 0, 0);
                tip.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cardAdd(card, tip, 14);
            }

            TextView openBtn = primary(r.verified
                    ? "Open full recipe" + (r.sourceName.isEmpty() ? "" : " on " + r.sourceName)
                    : "Search the web for this recipe");
            openBtn.setTextSize(16);
            openBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(r.sourceUrl); }
            });
            cardAdd(card, openBtn, 6);

            if (ttsReady) {
                TextView read = linkButton("Read the method aloud");
                read.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { speak(spoken(r)); }
                });
                cardAdd(card, read, 0);
            }
            add(card, 16);
        }

        add(label("Want something different?"), 6);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText change = field("e.g. vegetarian, under 20 minutes", false);
        change.setText(changeText);
        change.addTextChangedListener(new Watcher() { @Override void changed(String s) { changeText = s; } });
        row.addView(change, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mic = micButton(52, FIELD_CHANGE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        mlp.leftMargin = dp(10);
        row.addView(mic, mlp);
        add(row, 12);

        busyOr(primary("Find three different recipes"), new View.OnClickListener() {
            @Override public void onClick(View v) { submitRecipes(true); }
        });
        errorView();

        TextView back = linkButton("Change herbs and spices");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!busy) go(STAGE_HERBS); }
        });
        add(back, 4);
        TextView again = linkButton("Start again with a new fridge");
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!busy) restart(); }
        });
        add(again, 14);
        add(small("Methods are short summaries. Open the full recipe for exact quantities, and always cook meat, fish and eggs through."), 0);
    }

    private String spoken(RecipeEngine.Recipe r) {
        StringBuilder sb = new StringBuilder(r.title).append(". ");
        for (int i = 0; i < r.steps.size(); i++) sb.append("Step ").append(i + 1).append(". ").append(r.steps.get(i)).append(" ");
        return sb.length() > 3900 ? sb.substring(0, 3900) : sb.toString();
    }

    // ================================================================ building blocks

    private void add(View v, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        pageView.addView(v, lp);
    }

    private void cardAdd(LinearLayout card, View v, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        card.addView(v, lp);
    }

    private void busyOr(TextView button, View.OnClickListener onClick) {
        if (!busy) {
            button.setOnClickListener(onClick);
            add(button, 10);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rounded(cShelf, 14, cLine));
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminate(true);
        row.addView(spin, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView msg = body(busyMessage);
        msg.setPadding(dp(12), 0, 0, 0);
        row.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(row, 10);
    }

    private void errorView() {
        if (errorMessage.isEmpty()) return;
        TextView e = body(errorMessage);
        e.setTextColor(cAlert);
        e.setTypeface(Typeface.DEFAULT_BOLD);
        add(e, 12);
    }

    private TextView h2(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(26);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setTextColor(cInk);
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(cInk);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView small(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(cSoft);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cInk);
        return t;
    }

    private TextView groupTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.UK));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cSoft);
        return t;
    }

    private TextView primary(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(18);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextColor(cOnLeaf);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(15), dp(16), dp(15));
        b.setBackground(rounded(cLeaf, 14, 0));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView linkButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(cInk);
        b.setPaintFlags(b.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        b.setPadding(0, dp(10), dp(4), dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView micButton(int sizeDp, final String field) {
        TextView mic = new TextView(this);
        mic.setText("🎤");
        mic.setTextSize(sizeDp > 60 ? 30 : 20);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(cInk);
        mic.setBackground(g);
        mic.setContentDescription("Speak");
        mic.setClickable(true);
        mic.setFocusable(true);
        mic.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        mic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listen(field); }
        });
        return mic;
    }

    private EditText field(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(cInk);
        e.setHintTextColor(cSoft);
        e.setTextSize(17);
        e.setBackground(rounded(cShelf, 14, cLine));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(4);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setSingleLine(true);
        }
        return e;
    }

    private FlowLayout toggles(final List<Toggle> list) {
        FlowLayout box = new FlowLayout(this, dp(8));
        for (final Toggle item : list) {
            final TextView chip = new TextView(this);
            chip.setTextSize(15);
            chip.setPadding(dp(14), dp(10), dp(14), dp(10));
            chip.setMinHeight(dp(44));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setClickable(true);
            chip.setFocusable(true);
            styleChip(chip, item);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    item.on = !item.on;
                    styleChip(chip, item);
                }
            });
            box.addView(chip);
        }
        return box;
    }

    private void styleChip(TextView chip, Toggle item) {
        chip.setText((item.on ? "✓ " : "+ ") + item.name);
        chip.setTextColor(item.on ? cOnLeaf : cInk);
        chip.setTypeface(item.on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        chip.setBackground(rounded(item.on ? cLeaf : cShelf, 999, item.on ? cLeaf : cLine));
        chip.setContentDescription(item.name + (item.on ? ", you have this" : ", tap if you have this"));
    }

    private FlowLayout tags(List<String> items, boolean need) {
        FlowLayout box = new FlowLayout(this, dp(6));
        for (String s : items) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(need ? cAlert : cInk);
            t.setPadding(dp(9), dp(4), dp(9), dp(4));
            GradientDrawable g = rounded(Color.TRANSPARENT, 6, need ? cAlert : cLine);
            if (need) g.setStroke(dp(1.5f), cAlert, dp(4), dp(3));
            t.setBackground(g);
            box.addView(t);
        }
        return box;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private abstract static class Watcher implements TextWatcher {
        abstract void changed(String s);
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable e) { changed(e.toString()); }
    }

    // ================================================================ small helpers

    private String apiKey() {
        return prefs.getString(PREF_KEY, "");
    }

    private static List<String> picked(List<Toggle> list) {
        ArrayList<String> out = new ArrayList<>();
        for (Toggle t : list) if (t.on) out.add(t.name);
        return out;
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim();
        b = b == null ? "" : b.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        char last = a.charAt(a.length() - 1);
        return a + (last == ',' || last == '.' || last == ';' ? " " : ", ") + b;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("No web browser found on this phone.");
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setPalette() {
        if (isNight()) {
            cDoor = 0xFF0C1B1F; cShelf = 0xFF142A30; cInk = 0xFFE4F0F1; cSoft = 0xFF9DB7BC;
            cLine = 0xFF29444B; cLeaf = 0xFF5FCF94; cOnLeaf = 0xFF06231A; cAlert = 0xFFFF8A80;
        } else {
            cDoor = 0xFFE9F0F1; cShelf = 0xFFFFFFFF; cInk = 0xFF10323A; cSoft = 0xFF4C6A70;
            cLine = 0xFFC5D5D8; cLeaf = 0xFF1F7A4D; cOnLeaf = 0xFFFFFFFF; cAlert = 0xFFB3261E;
        }
        cMagnet = 0xFFF2B632;
        cOnMagnet = 0xFF2A1E00;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
