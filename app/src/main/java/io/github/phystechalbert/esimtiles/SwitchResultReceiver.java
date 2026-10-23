package io.github.phystechalbert.esimtiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.widget.Toast;

/** Receives the eUICC controller's verdict on a switch started by Esim.switchTo. */
public class SwitchResultReceiver extends BroadcastReceiver {
    private static final String TAG = "EsimTiles";
    private static final long RESTORE_DELAY_MS = 7000;

    /**
     * Result of the last switch that needs the user's consent; MainActivity shows the system's
     * confirmation for it. Lives only as long as the process, which is enough for "open the app".
     */
    static Intent pendingResolution;

    @Override
    public void onReceive(Context context, Intent intent) {
        int code = getResultCode();
        int detailed = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
        int op = intent.getIntExtra(Esim.EXTRA_OP, 0);
        int target = intent.getIntExtra(Esim.EXTRA_TARGET, -1);
        int outgoing = intent.getIntExtra(Esim.EXTRA_OUTGOING, -1);
        int[] defaults = intent.getIntArrayExtra(Esim.EXTRA_DEFAULTS);
        Log.i(TAG, "switch result " + code + " detailed " + detailed);
        Esim.recordResult(context, code, detailed);

        // A result for a switch that is no longer the pending one (timed out, superseded) must not
        // touch the user's defaults: they may have changed since.
        if (!Esim.isPendingOp(context, op)) return;

        if (code != EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
            Esim.clearPending(context, op);
            if (code == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
                pendingResolution = intent;
                Toast.makeText(context, R.string.result_needs_confirmation, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, context.getString(R.string.result_error, code, detailed),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        PendingResult async = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        // Hold the switch through the delayed pass below, however late within its timeout this came.
        Esim.extendPending(context, op, RESTORE_DELAY_MS + 30_000);
        restore(defaults, outgoing, -1);
        // The new SIM finishes loading a few seconds later and the system may move defaults onto it
        // then. Fix only those; anything else changed in between is the user's choice. The switch
        // stays pending until here, so no other switch can interleave.
        handler.postDelayed(() -> {
            if (!Esim.isPendingOp(context, op)) {
                async.finish();
                return;
            }
            restore(defaults, outgoing, target);
            try {
                if (!Esim.read().isActive(target)) {
                    Toast.makeText(context, R.string.result_not_active, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.w(TAG, "verify: " + e);
            }
            Esim.clearPending(context, op);
            async.finish();
        }, RESTORE_DELAY_MS);
    }

    private static void restore(int[] defaults, int outgoing, int onlyFrom) {
        try {
            Esim.restoreDefaults(defaults, outgoing, onlyFrom);
        } catch (Exception e) {
            Log.w(TAG, "restoreDefaults: " + e);
        }
    }
}
