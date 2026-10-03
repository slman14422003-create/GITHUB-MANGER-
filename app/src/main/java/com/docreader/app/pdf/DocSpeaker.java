package com.docreader.app.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ صوتي لمحرّري الوورد والإكسل: ينطق قائمة عناصر (فقرات أو صفوف) واحدًا واحدًا
 * بنفس محرك قارئ PDF (صوت Edge العصبي مع الرجوع لصوت الجهاز عند تعذّر الإنترنت)،
 * ويُبلغ الواجهة بالعنصر الجاري لتظليله.
 *
 * يقع في حزمة pdf لأن SpeechPrep / EdgeTtsClient / PdfSpeechText محلية الحزمة.
 */
public final class DocSpeaker {

    public interface Listener {
        /** بدأ نطق العنصر index. */
        void onItem(int index, int total);

        /** تغيّرت حالة التشغيل (true = يعمل). */
        void onPlaying(boolean playing);

        /** انتهت كل العناصر. */
        void onFinished();

        void onStatus(String message);
    }

    private static final String PREFS = "pdf_tts";
    private static final String DEFAULT_AR = "ar-SA-ZariyahNeural";
    private static final String DEFAULT_EN = "en-US-EmmaMultilingualNeural";

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private String[] items = new String[0];
    private int index = 0;
    private boolean playing = false;
    private boolean released = false;
    private int token = 0;
    private float rate;

    private MediaPlayer player;
    private File currentFile;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean useDeviceOnly = false;

    // تحضير مسبق للعنصر التالي
    private int prefetchIndex = -1;
    private File prefetchFile;

    public DocSpeaker(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.rate = Math.max(0.5f, Math.min(2.5f, prefs.getFloat("rate", 1.0f)));
        try {
            if (!TashkeelDict.isReady()) TashkeelDict.load(app);
        } catch (Throwable ignored) {
        }
        try {
            io.execute(EdgeTtsClient::warmUp);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ واجهة التحكم

    public void setItems(List<String> list) {
        stop();
        items = list.toArray(new String[0]);
        index = 0;
    }

    public int count() {
        return items.length;
    }

    public int current() {
        return index;
    }

    public boolean isPlaying() {
        return playing;
    }

    public float getRate() {
        return rate;
    }

    public void setRate(float r) {
        rate = Math.max(0.5f, Math.min(2.5f, r));
        prefs.edit().putFloat("rate", rate).apply();
        MediaPlayer p = player;
        if (p != null && playing) {
            try {
                PlaybackParams pp = new PlaybackParams();
                pp.setSpeed(rate);
                p.setPlaybackParams(pp);
            } catch (Throwable ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.setSpeechRate(rate);
            } catch (Throwable ignored) {
            }
        }
    }

    public void playFrom(int start) {
        if (items.length == 0) {
            listener.onStatus("لا يوجد نص للقراءة");
            return;
        }
        index = Math.max(0, Math.min(items.length - 1, start));
        startCurrent();
    }

    public void toggle() {
        if (playing) {
            pause();
        } else {
            resume();
        }
    }

    public void pause() {
        if (!playing) return;
        playing = false;
        MediaPlayer p = player;
        if (p != null) {
            try {
                if (p.isPlaying()) {
                    p.pause();
                    listener.onPlaying(false);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // صوت الجهاز أو قيد التحضير: نلغي ونعيد العنصر عند الاستئناف
        token++;
        stopDevice();
        releasePlayer();
        listener.onPlaying(false);
    }

    public void resume() {
        if (items.length == 0) return;
        MediaPlayer p = player;
        if (p != null && !playing) {
            try {
                p.start();
                playing = true;
                listener.onPlaying(true);
                return;
            } catch (Throwable ignored) {
            }
        }
        startCurrent();
    }

    public void next() {
        if (index + 1 < items.length) {
            index++;
            startCurrent();
        } else {
            stop();
            listener.onFinished();
        }
    }

    public void previous() {
        index = Math.max(0, index - 1);
        startCurrent();
    }

    public void stop() {
        token++;
        playing = false;
        stopDevice();
        releasePlayer();
        listener.onPlaying(false);
    }

    public void release() {
        released = true;
        stop();
        try {
            io.shutdownNow();
        } catch (Throwable ignored) {
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        deleteQuiet(prefetchFile);
    }

    // ------------------------------------------------------------------ التشغيل

    private void startCurrent() {
        token++;
        final int my = token;
        stopDevice();
        releasePlayer();
        playing = true;
        listener.onPlaying(true);
        final int i = index;
        listener.onItem(i, items.length);
        final String raw = items[i];
        if (raw == null || raw.trim().isEmpty()) {
            advanceIfCurrent(my);
            return;
        }
        if (useDeviceOnly) {
            speakDevice(raw, my);
            return;
        }
        final File ready = (prefetchIndex == i) ? prefetchFile : null;
        if (ready != null) {
            prefetchFile = null;
            prefetchIndex = -1;
        }
        try {
            io.execute(() -> {
                File f = ready;
                try {
                    if (f == null) f = synth(raw, i);
                } catch (Throwable t) {
                    f = null;
                }
                final File out = f;
                main.post(() -> {
                    if (my != token || released) {
                        deleteQuiet(out);
                        return;
                    }
                    if (out == null) {
                        listener.onStatus("تعذّر الصوت العصبي، سيُستعمل صوت الجهاز");
                        speakDevice(raw, my);
                    } else {
                        playFile(out, my);
                        prefetchNext(i + 1);
                    }
                });
            });
        } catch (Throwable t) {
            speakDevice(raw, my);
        }
    }

    private void advanceIfCurrent(int my) {
        if (my != token || released) return;
        if (index + 1 < items.length) {
            index++;
            startCurrent();
        } else {
            playing = false;
            listener.onPlaying(false);
            listener.onFinished();
        }
    }

    private String voiceFor(String lang) {
        String saved = prefs.getString("cvoice_" + lang, null);
        if (saved != null) return saved;
        return "ar".equals(lang) ? DEFAULT_AR : DEFAULT_EN;
    }

    /** يُنفَّذ في خيط الخلفية: يجهّز النص وينتج ملف MP3. */
    private File synth(String raw, int i) throws Exception {
        String lang = PdfSpeechText.detectLang(raw, "en");
        if (!"ar".equals(lang)) lang = "en";
        SpeechPrep.Spoken spoken = SpeechPrep.prepare(raw, lang, "en", false, false);
        String text = (spoken != null && spoken.text != null && !spoken.text.trim().isEmpty()) ? spoken.text : raw;
        text = EdgeTtsClient.sanitize(text);
        EdgeTtsClient.Result r = EdgeTtsClient.synthesize(text, voiceFor(lang),
                new EdgeTtsClient.Style(0, 0, 150, 60));
        if (r == null || r.audio == null || r.audio.length == 0) throw new java.io.IOException("empty");
        File f = new File(app.getCacheDir(), "docspeak_" + System.nanoTime() + ".mp3");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(r.audio);
        }
        return f;
    }

    private void prefetchNext(final int next) {
        if (next >= items.length || useDeviceOnly || released) return;
        final String raw = items[next];
        if (raw == null || raw.trim().isEmpty()) return;
        final int my = token;
        try {
            io.execute(() -> {
                try {
                    File f = synth(raw, next);
                    main.post(() -> {
                        if (my != token || released) {
                            deleteQuiet(f);
                            return;
                        }
                        deleteQuiet(prefetchFile);
                        prefetchFile = f;
                        prefetchIndex = next;
                    });
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void playFile(File f, final int my) {
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            p.setDataSource(f.getAbsolutePath());
            p.setOnCompletionListener(mp -> {
                if (my != token) return;
                releasePlayer();
                advanceIfCurrent(my);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (my != token) return true;
                releasePlayer();
                advanceIfCurrent(my);
                return true;
            });
            p.prepare();
            player = p;
            currentFile = f;
            try {
                PlaybackParams pp = new PlaybackParams();
                pp.setSpeed(rate);
                p.setPlaybackParams(pp);
            } catch (Throwable ignored) {
            }
            p.start();
        } catch (Throwable t) {
            deleteQuiet(f);
            speakDevice(items[index], my);
        }
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
                p.stop();
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        deleteQuiet(currentFile);
        currentFile = null;
    }

    private static void deleteQuiet(File f) {
        if (f != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ صوت الجهاز (احتياطي)

    private void speakDevice(final String raw, final int my) {
        if (my != token || released) return;
        if (tts == null) {
            tts = new TextToSpeech(app, status -> main.post(() -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (!ttsReady) {
                    listener.onStatus("لا يتوفر محرك نطق على الجهاز");
                    playing = false;
                    listener.onPlaying(false);
                    return;
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        final int t = parseToken(id);
                        main.post(() -> advanceIfCurrent(t));
                    }

                    @Override
                    public void onError(String id) {
                        final int t = parseToken(id);
                        main.post(() -> advanceIfCurrent(t));
                    }
                });
                speakDevice(items[index], token);
            }));
            return;
        }
        if (!ttsReady) return;
        String lang = PdfSpeechText.detectLang(raw, "en");
        try {
            tts.setLanguage("ar".equals(lang) ? new Locale("ar") : Locale.US);
            tts.setSpeechRate(rate);
        } catch (Throwable ignored) {
        }
        HashMap<String, String> params = new HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "u" + my);
        tts.speak(raw, TextToSpeech.QUEUE_FLUSH, params);
    }

    private static int parseToken(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (Throwable t) {
            return -1;
        }
    }

    private void stopDevice() {
        if (tts != null && ttsReady) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
    }
}
