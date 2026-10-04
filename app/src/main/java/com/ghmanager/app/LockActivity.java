package com.ghmanager.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen lock that asks for the phone's screen lock before the app can be used. */
public class LockActivity extends Activity {
    private static final int REQ = 77;
    private boolean asking = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        int p = Ui.dp(this, 32);
        root.setPadding(p, p, p, p);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(Ui.color(this, R.color.accent_text));
        root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        TextView t = new TextView(this);
        t.setText(R.string.lock_title);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 20));
        root.addView(t);

        Button unlock = Ui.button(this, R.string.lock_unlock, true);
        unlock.setOnClickListener(v -> ask());
        root.addView(unlock, new LinearLayout.LayoutParams(Ui.dp(this, 220),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AppLock.isLocked() || !AppLock.enabled(this) || !AppLock.available(this)) {
            finish();
            return;
        }
        if (!asking) ask();
    }

    private void ask() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return;
        Intent i = km.createConfirmDeviceCredentialIntent(getString(R.string.app_name),
                getString(R.string.lock_title));
        if (i == null) {
            // the phone no longer has a screen lock: nothing to ask, let the user in
            AppLock.unlocked();
            finish();
            return;
        }
        asking = true;
        startActivityForResult(i, REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        asking = false;
        if (req == REQ && res == RESULT_OK) {
            AppLock.unlocked();
            finish();
        }
    }

    /** Back must not reveal the app behind the lock: send the whole task to the background. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
