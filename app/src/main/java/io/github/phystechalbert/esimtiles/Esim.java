package io.github.phystechalbert.esimtiles;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Enables stored eSIM profiles through the hidden telephony services, called with the shell
 * identity that Shizuku lends us (shell holds WRITE_EMBEDDED_SUBSCRIPTIONS and MODIFY_PHONE_STATE).
 * Only enabling is ever requested: nothing here can delete or erase a profile.
 */
final class Esim {
    static final String SHELL = "com.android.shell";

    static final String EXTRA_OP = "op";
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_OUTGOING = "outgoing";
    static final String EXTRA_DEFAULTS = "defaults";

    private static final String PREFS = "state";
    private static final long PENDING_TIMEOUT_MS = 60_000;

    private Esim() {}

    /** A switch the app refuses to start, with a message for the user. */
    static final class Refused extends Exception {
        final int message;

        Refused(int message) {
            super("refused");
            this.message = message;
        }
    }

    static final class State {
        /** Every active SIM, physical ones included. */
        final List<SubscriptionInfo> active = new ArrayList<>();
        /** eSIM profiles stored on the phone, active or not. */
        final List<SubscriptionInfo> stored = new ArrayList<>();
        int defaultData = -1;
        int defaultVoice = -1;
        int defaultSms = -1;

        SubscriptionInfo find(int subId) {
            for (SubscriptionInfo info : active) if (info.getSubscriptionId() == subId) return info;
            for (SubscriptionInfo info : stored) if (info.getSubscriptionId() == subId) return info;
            return null;
        }

        boolean isActive(int subId) {
            return contains(active, subId);
        }

        /** The active SIM other than the primary that the tiles would replace, or null. */
        SubscriptionInfo second(int primaryId) {
            for (SubscriptionInfo info : active) {
                if (info.getSubscriptionId() != primaryId) return info;
            }
            return null;
        }
    }

    static boolean ready() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Runs onReady once the Shizuku binder has arrived (it comes asynchronously after the
     * process starts), or onFail if it hasn't within a second and a half.
     */
    static void whenBinderReady(Runnable onReady, Runnable onFail) {
        if (Shizuku.pingBinder()) {
            onReady.run();
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] done = {false};
        Shizuku.OnBinderReceivedListener[] holder = new Shizuku.OnBinderReceivedListener[1];
        holder[0] = () -> handler.post(() -> {
            if (done[0]) return;
            done[0] = true;
            Shizuku.removeBinderReceivedListener(holder[0]);
            onReady.run();
        });
        Shizuku.addBinderReceivedListenerSticky(holder[0], handler);
        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            Shizuku.removeBinderReceivedListener(holder[0]);
            onFail.run();
        }, 1500);
    }

    static String label(SubscriptionInfo info) {
        CharSequence name = info.getDisplayName();
        if (name == null || name.length() == 0) name = info.getCarrierName();
        return name != null && name.length() > 0 ? name.toString() : "SIM " + info.getSubscriptionId();
    }

    @SuppressWarnings("unchecked")
    static State read() throws Exception {
        Object sub = service("isub", "com.android.internal.telephony.ISub");
        List<SubscriptionInfo> active =
                (List<SubscriptionInfo>) call(sub, "getActiveSubscriptionInfoList", SHELL, null, true);
        List<SubscriptionInfo> all = (List<SubscriptionInfo>) call(sub, "getAllSubInfoList", SHELL, null);

        State state = new State();
        if (active != null) state.active.addAll(active);
        List<SubscriptionInfo> candidates = new ArrayList<>(state.active);
        if (all != null) candidates.addAll(all);
        for (SubscriptionInfo info : candidates) {
            // Deleted profiles linger in the database as non-embedded rows; only stored ones count.
            if (info.isEmbedded() && !contains(state.stored, info.getSubscriptionId())) state.stored.add(info);
        }
        state.defaultData = (int) call(sub, "getDefaultDataSubId");
        state.defaultVoice = (int) call(sub, "getDefaultVoiceSubId");
        state.defaultSms = (int) call(sub, "getDefaultSmsSubId");
        return state;
    }

    private static boolean contains(List<SubscriptionInfo> list, int subId) {
        for (SubscriptionInfo info : list) if (info.getSubscriptionId() == subId) return true;
        return false;
    }

    /** Where a switch would go: the eUICC card and port, and the SIM it would turn off. */
    static final class Plan {
        int cardId;
        int port;
        SubscriptionInfo outgoing;
    }

    /**
     * Works out which port the target goes to, refusing anything that could touch the primary SIM
     * or that this app can't reason about safely.
     */
    static Plan plan(State state, int primaryId, SubscriptionInfo target, int modems) throws Refused {
        SubscriptionInfo primary = primaryId >= 0 ? state.find(primaryId) : null;
        if (primary == null || !state.isActive(primaryId)) throw new Refused(R.string.refuse_no_primary);
        if (target.getSubscriptionId() == primaryId) throw new Refused(R.string.refuse_target_is_primary);
        if (!target.isEmbedded()) throw new Refused(R.string.refuse_not_esim);
        if (state.isActive(target.getSubscriptionId())) throw new Refused(R.string.refuse_already_on);

        List<SubscriptionInfo> others = new ArrayList<>();
        for (SubscriptionInfo info : state.active) {
            if (info.getSubscriptionId() != primaryId) others.add(info);
        }
        if (others.size() > 1) throw new Refused(R.string.refuse_ambiguous);

        Plan plan = new Plan();
        if (others.size() == 1) {
            SubscriptionInfo outgoing = others.get(0);
            if (!outgoing.isEmbedded()) throw new Refused(R.string.refuse_second_is_physical);
            plan.outgoing = outgoing;
            plan.cardId = outgoing.getCardId();
            plan.port = outgoing.getPortIndex();
        } else if (primary.isEmbedded() && primary.getPortIndex() >= 0 && modems >= 2) {
            // Second slot empty on a dual-SIM eUICC: take the port the primary isn't using.
            plan.cardId = primary.getCardId();
            plan.port = primary.getPortIndex() == 0 ? 1 : 0;
        } else {
            // Which logical slot a free eUICC port lands in isn't known here (single-SIM mode, a
            // physical primary): guessing could switch the primary off. Settings does it safely once.
            throw new Refused(R.string.refuse_empty_slot);
        }

        if (plan.cardId < 0 || plan.port < 0) throw new Refused(R.string.refuse_unknown_port);
        if (target.getCardId() >= 0 && target.getCardId() != plan.cardId) {
            throw new Refused(R.string.refuse_other_chip);
        }
        if (primary.isEmbedded() && primary.getCardId() == plan.cardId && primary.getPortIndex() == plan.port) {
            throw new Refused(R.string.refuse_would_hit_primary);
        }
        return plan;
    }

    /** Starts enabling target; the result arrives at SwitchResultReceiver. */
    static void switchTo(Context context, SubscriptionInfo target) throws Exception {
        if (pendingTarget(context) >= 0) throw new Refused(R.string.refuse_busy);
        if (inCall()) throw new Refused(R.string.refuse_in_call);
        State state = read();
        int modems = context.getSystemService(TelephonyManager.class).getActiveModemCount();
        Plan plan = plan(state, Config.primary(context), target, modems);

        int outgoing = plan.outgoing != null ? plan.outgoing.getSubscriptionId() : -1;
        int[] defaults = {state.defaultData, state.defaultVoice, state.defaultSms};
        Object euicc = service("econtroller", "com.android.internal.telephony.euicc.IEuiccController");
        int op = startOp(context, target.getSubscriptionId());
        PendingIntent callback = callback(context, op, target.getSubscriptionId(), outgoing, defaults);
        try {
            if (hasMethod(euicc, "switchToSubscriptionWithPort")) {
                call(euicc, "switchToSubscriptionWithPort", plan.cardId, target.getSubscriptionId(), plan.port,
                        SHELL, callback);
            } else {
                call(euicc, "switchToSubscription", plan.cardId, target.getSubscriptionId(), SHELL, callback);
            }
        } catch (Exception e) {
            clearPending(context, op);
            throw e;
        }
    }

    /** Marks a new switch as pending and returns its id. */
    static int startOp(Context context, int targetSubId) {
        // Each switch gets its own id, so a late result can't act on a newer switch.
        int op = (int) (System.currentTimeMillis() & 0x7fffffff);
        if (op == 0) op = 1;
        setPending(context, op, targetSubId);
        return op;
    }

    /** Callback the eUICC controller sends the result to; carries what the receiver needs. */
    static PendingIntent callback(Context context, int op, int target, int outgoing, int[] defaults) {
        Intent result = new Intent(context, SwitchResultReceiver.class)
                .putExtra(EXTRA_OP, op)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_OUTGOING, outgoing)
                .putExtra(EXTRA_DEFAULTS, defaults);
        // Mutable: the eUICC controller fills in result extras when it sends it.
        return PendingIntent.getBroadcast(context, op, result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Phone, SIM layout and last result for a bug report. No numbers, ICCIDs or SIM names: only
     * positions and network codes.
     */
    static String report(Context context, int primaryId) {
        StringBuilder sb = new StringBuilder();
        String version;
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        sb.append("eSIM Tiles ").append(version).append('\n');
        sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" (").append(android.os.Build.DEVICE).append(")\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" / SDK ")
                .append(android.os.Build.VERSION.SDK_INT).append(" / ").append(android.os.Build.ID).append('\n');
        try {
            sb.append("Shizuku: v").append(Shizuku.getVersion()).append(", uid ").append(Shizuku.getUid()).append('\n');
        } catch (RuntimeException e) {
            sb.append("Shizuku: not available\n");
        }
        try {
            State state = read();
            sb.append("Active SIMs:\n");
            for (SubscriptionInfo info : state.active) sb.append("  ").append(describeSim(info, state, primaryId)).append('\n');
            sb.append("Stored eSIMs: ").append(state.stored.size()).append('\n');
            for (SubscriptionInfo info : state.stored) sb.append("  ").append(describeSim(info, state, primaryId)).append('\n');
            Object euicc = service("econtroller", "com.android.internal.telephony.euicc.IEuiccController");
            sb.append("Port switch API: ").append(hasMethod(euicc, "switchToSubscriptionWithPort") ? "yes" : "no").append('\n');
        } catch (Exception e) {
            sb.append("Read failed: ").append(describe(context, e)).append('\n');
        }
        sb.append("Last result: ").append(lastResult(context)).append('\n');
        return sb.toString();
    }

    private static String describeSim(SubscriptionInfo info, State state, int primaryId) {
        int id = info.getSubscriptionId();
        return "mcc/mnc " + info.getMccString() + "/" + info.getMncString()
                + ", " + (info.isEmbedded() ? "eSIM" : "physical")
                + ", slot " + info.getSimSlotIndex() + ", card " + info.getCardId() + ", port " + info.getPortIndex()
                + (state.isActive(id) ? ", on" : ", off")
                + (id == primaryId ? ", main" : "")
                + (id == state.defaultData ? ", data" : "")
                + (id == state.defaultVoice ? ", calls" : "")
                + (id == state.defaultSms ? ", sms" : "");
    }

    /**
     * Puts data, calls and texts back where the user had them before the switch. A default that
     * pointed at the SIM that was just turned off is left to the system. With onlyFrom >= 0, only
     * defaults that now point at onlyFrom (the new SIM) or at nothing are put back.
     */
    static void restoreDefaults(int[] before, int outgoing, int onlyFrom) throws Exception {
        if (before == null || before.length != 3) return;
        State state = read();
        Object sub = service("isub", "com.android.internal.telephony.ISub");
        int[] now = {state.defaultData, state.defaultVoice, state.defaultSms};
        String[] setters = {"setDefaultDataSubId", "setDefaultVoiceSubId", "setDefaultSmsSubId"};
        for (int i = 0; i < 3; i++) {
            int want = before[i];
            if (want == now[i]) continue;
            // -1 for calls and texts is "ask every time", a real choice; for data it means none.
            if (want < 0 ? i == 0 : want == outgoing || !state.isActive(want)) continue;
            if (onlyFrom >= 0 && now[i] != onlyFrom && now[i] >= 0) continue;
            call(sub, setters[i], want);
        }
    }

    /** True if op is the switch in progress and hasn't timed out (a timed-out one is stale). */
    static boolean isPendingOp(Context context, int op) {
        return op != 0 && pendingTarget(context) >= 0 && prefs(context).getInt("pending_op", 0) == op;
    }

    /** Last result codes from the eUICC controller, for the tester report. */
    static void recordResult(Context context, int code, int detailed) {
        prefs(context).edit().putString("last_result", code + "/" + detailed).apply();
    }

    static String lastResult(Context context) {
        return prefs(context).getString("last_result", "none");
    }

    /** True while a phone call is ringing or going on; unknown counts as not in a call. */
    private static boolean inCall() {
        try {
            Object telecom = service("telecom", "com.android.internal.telecom.ITelecomService");
            return (boolean) call(telecom, "isInCall", SHELL, null);
        } catch (Exception e) {
            return false;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int pendingTarget(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong("pending_at", 0);
        if (System.currentTimeMillis() - at > PENDING_TIMEOUT_MS) return -1;
        return prefs.getInt("pending_sub", -1);
    }

    private static void setPending(Context context, int op, int subId) {
        prefs(context).edit()
                .putInt("pending_op", op)
                .putInt("pending_sub", subId)
                .putLong("pending_at", System.currentTimeMillis())
                .apply();
    }

    /** Clears the pending switch, but only if it is still the one identified by op. */
    static void clearPending(Context context, int op) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt("pending_op", 0) != op) return;
        prefs.edit().remove("pending_op").remove("pending_sub").remove("pending_at").apply();
    }

    static String describe(Context context, Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        if (e instanceof Refused) return context.getString(((Refused) e).message);
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static Object service(String name, String iface) throws Exception {
        IBinder binder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name));
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static boolean hasMethod(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    /** Calls a hidden AIDL method by name and argument count (signatures shift between releases). */
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name + " with " + args.length + " args");
    }
}
