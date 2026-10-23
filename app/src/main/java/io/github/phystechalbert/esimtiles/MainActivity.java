package io.github.phystechalbert.esimtiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import rikka.shizuku.Shizuku;

/** Setup screen: Shizuku access, the SIM to protect, the SIMs behind the tiles, manual switching. */
public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 1;
    private static final int CONFIRM_REQUEST = 2;
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private int confirmOp;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> render();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad * 2, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Esim.whenBinderReady(() -> {
            confirmPendingSwitch();
            render();
        }, this::render);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != CONFIRM_REQUEST || confirmOp == 0) return;
        if (resultCode == RESULT_OK) {
            // Approved: the switch runs now, so the usual execution timeout starts here.
            Esim.extendPending(this, confirmOp, Esim.PENDING_TIMEOUT_MS);
        } else {
            // Declined or backed out: the controller sends no result, so free the tiles right away.
            Esim.clearPending(this, confirmOp);
        }
        confirmOp = 0;
    }

    /**
     * Some phones won't switch silently and ask for the user's consent instead (seen in AOSP for
     * carrier-locked profiles). The tile can't show that screen, so the app does when opened.
     */
    private void confirmPendingSwitch() {
        Intent result = SwitchResultReceiver.pendingResolution;
        if (result == null) return;
        SwitchResultReceiver.pendingResolution = null;
        int target = result.getIntExtra(Esim.EXTRA_TARGET, -1);
        int outgoing = result.getIntExtra(Esim.EXTRA_OUTGOING, -1);
        // The consent belongs to the SIM layout of its time: show it only if nothing has moved since.
        Esim.State state;
        try {
            state = Esim.read();
        } catch (Exception e) {
            return;
        }
        if (Esim.pendingTarget(this) >= 0 || Esim.inCall()) return;
        // Same checks as a fresh switch, and it must still replace the same SIM.
        SubscriptionInfo targetInfo = state.find(target);
        if (targetInfo == null) return;
        try {
            Esim.Plan plan = Esim.plan(state, Config.primary(this), targetInfo,
                    getSystemService(TelephonyManager.class).getActiveModemCount());
            int planned = plan.outgoing != null ? plan.outgoing.getSubscriptionId() : -1;
            if (planned != outgoing) return;
        } catch (Esim.Refused e) {
            return;
        }
        int op = Esim.startOp(this, target, Esim.CONSENT_TIMEOUT_MS);
        try {
            PendingIntent callback = Esim.callback(this, op, target, outgoing,
                    new int[]{state.defaultData, state.defaultVoice, state.defaultSms});
            getSystemService(EuiccManager.class).startResolutionActivity(this, CONFIRM_REQUEST, result, callback);
            confirmOp = op;
        } catch (Exception e) {
            Esim.clearPending(this, op);
            Toast.makeText(this, getString(R.string.confirm_failed, Esim.describe(this, e)), Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        root.removeAllViews();
        addText(getString(R.string.app_name), 26, true);
        addText(getString(R.string.intro), 15, false);
        renderSetup();
        // Last and always there: testers whose phone isn't supported need it most.
        addHeader(getString(R.string.report_header));
        addButton(getString(R.string.copy_report), v -> {
            String report = Esim.report(this, Config.primary(this));
            getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("eSIM Tiles report", report));
            Toast.makeText(this, R.string.report_copied, Toast.LENGTH_LONG).show();
        });
    }

    private void renderSetup() {
        if (!shizukuReady()) return;

        Esim.State state;
        try {
            state = Esim.read();
        } catch (Exception e) {
            addText(getString(R.string.read_failed, Esim.describe(this, e)), 15, false);
            return;
        }
        if (state.stored.isEmpty()) {
            addText(getString(R.string.no_esims), 15, false);
            return;
        }

        int primary = choosePrimary(state);
        renderPrimary(state, primary);
        renderTiles(state, primary);
        renderNow(state, primary);

        addHeader(getString(R.string.shade_header));
        addButton(getString(R.string.add_tiles), v -> requestTile(0, Config.tiles(this).size()));
        addText(getString(R.string.notes), 13, false);
    }

    /** Walks the user through installing, starting and allowing Shizuku. Returns true once usable. */
    private boolean shizukuReady() {
        if (!Shizuku.pingBinder()) {
            boolean installed = isInstalled(SHIZUKU_PACKAGE);
            addHeader(getString(R.string.shizuku_header));
            addText(getString(installed ? R.string.shizuku_not_running : R.string.shizuku_not_installed), 15, false);
            if (installed) {
                addButton(getString(R.string.open_shizuku), v -> {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
                    if (launch != null) startActivity(launch);
                });
            } else {
                addButton(getString(R.string.install_shizuku), v -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PACKAGE))));
            }
            addButton(getString(R.string.check_again), v -> Esim.whenBinderReady(this::render, this::render));
            return false;
        }
        if (Shizuku.isPreV11()) {
            addText(getString(R.string.shizuku_too_old), 15, false);
            return false;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            addHeader(getString(R.string.shizuku_header));
            addText(getString(R.string.shizuku_allow), 15, false);
            addButton(getString(R.string.allow), v -> Shizuku.requestPermission(SHIZUKU_REQUEST));
            return false;
        }
        return true;
    }

    /**
     * The saved main SIM. Only on first run is one picked for the user (the data SIM): replacing a
     * saved one that is off for a moment would make the app switch the real main SIM off later.
     */
    private int choosePrimary(Esim.State state) {
        int primary = Config.primary(this);
        if (primary >= 0) return primary;
        int suggestion = state.isActive(state.defaultData) ? state.defaultData
                : state.active.isEmpty() ? -1 : state.active.get(0).getSubscriptionId();
        if (suggestion >= 0) Config.setPrimary(this, suggestion);
        return suggestion;
    }

    private void renderPrimary(Esim.State state, int primary) {
        addHeader(getString(R.string.primary_header));
        addText(getString(R.string.primary_hint), 14, false);
        if (!state.isActive(primary)) addText(getString(R.string.primary_missing), 14, true);
        RadioGroup group = new RadioGroup(this);
        for (SubscriptionInfo info : state.active) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(Esim.label(info));
            group.addView(button);
            if (info.getSubscriptionId() == primary) button.setChecked(true);
            button.setOnCheckedChangeListener((b, checked) -> {
                if (!checked) return;
                Config.setPrimary(this, info.getSubscriptionId());
                SwitchResultReceiver.pendingResolution = null;
                List<Integer> tiles = Config.tiles(this);
                tiles.remove(Integer.valueOf(info.getSubscriptionId()));
                Config.setTiles(this, tiles);
                handler.post(this::render);
            });
        }
        root.addView(group);
    }

    private void renderTiles(Esim.State state, int primary) {
        addHeader(getString(R.string.tiles_header));
        addText(getString(R.string.tiles_hint, Config.MAX_TILES), 14, false);
        List<Integer> tiles = Config.tiles(this);
        for (SubscriptionInfo info : state.stored) {
            int id = info.getSubscriptionId();
            if (id == primary) continue;
            CheckBox box = new CheckBox(this);
            int position = tiles.indexOf(id);
            box.setText(position >= 0 ? (position + 1) + ". " + Esim.label(info) : Esim.label(info));
            box.setChecked(position >= 0);
            box.setOnCheckedChangeListener((b, checked) -> {
                List<Integer> now = Config.tiles(this);
                // Deleted profiles would otherwise hold tile places with no checkbox to free them.
                now.removeIf(saved -> state.find(saved) == null);
                if (checked && !now.contains(id)) {
                    if (now.size() >= Config.MAX_TILES) {
                        Toast.makeText(this, getString(R.string.too_many, Config.MAX_TILES), Toast.LENGTH_SHORT).show();
                    } else {
                        now.add(id);
                    }
                } else if (!checked) {
                    now.remove(Integer.valueOf(id));
                }
                Config.setTiles(this, now);
                handler.post(this::render);
            });
            root.addView(box);
        }
    }

    private void renderNow(Esim.State state, int primary) {
        addHeader(getString(R.string.now_header));
        SubscriptionInfo second = state.second(primary);
        addText(getString(R.string.now_second, second != null ? Esim.label(second) : getString(R.string.none)), 16, false);
        if (Esim.pendingTarget(this) >= 0) addText(getString(R.string.switching), 15, false);
        for (int id : Config.tiles(this)) {
            SubscriptionInfo info = state.find(id);
            if (info == null) continue;
            Button button = addButton(Esim.label(info), v -> confirmThenSwitch(info));
            button.setEnabled(!state.isActive(id));
        }
    }

    /** Shows the one-time warning before the very first switch. */
    private void confirmThenSwitch(SubscriptionInfo target) {
        if (Config.acknowledged(this)) {
            switchTo(target);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.warning_title)
                .setMessage(R.string.warning_text)
                .setPositiveButton(R.string.warning_ok, (d, w) -> {
                    Config.setAcknowledged(this);
                    switchTo(target);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void switchTo(SubscriptionInfo target) {
        try {
            Esim.switchTo(this, target);
            Toast.makeText(this, getString(R.string.switching_to, Esim.label(target)), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.switch_failed, Esim.describe(this, e)), Toast.LENGTH_LONG).show();
        }
        render();
        handler.postDelayed(this::render, 15000);
    }

    /** Asks the system to add tile i, then the next one once that dialog is answered. */
    private void requestTile(int i, int count) {
        if (i >= count || i >= SimTileService.TILES.length) return;
        if (!Config.acknowledged(this)) {
            // Tiles switch without asking, so the warning has to be accepted first.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.warning_title)
                    .setMessage(R.string.warning_text)
                    .setPositiveButton(R.string.warning_ok, (d, w) -> {
                        Config.setAcknowledged(this);
                        requestTile(i, count);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, SimTileService.TILES[i]),
                getString(R.string.tile_default_label, i + 1), Icon.createWithResource(this, R.drawable.ic_sim),
                getMainExecutor(), result -> requestTile(i + 1, count));
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void addHeader(String text) {
        TextView view = addText(text, 18, true);
        view.setPadding(0, dp(18), 0, dp(4));
    }

    private TextView addText(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        root.addView(view);
        return view;
    }

    private Button addButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        root.addView(button);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
