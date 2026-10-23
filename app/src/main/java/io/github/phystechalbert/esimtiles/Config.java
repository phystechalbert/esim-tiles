package io.github.phystechalbert.esimtiles;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** What the user picked on the setup screen: the SIM to protect and the SIMs the tiles switch to. */
final class Config {
    /** One tile service is declared per position, see SimTileService. */
    static final int MAX_TILES = 6;

    private static final String PREFS = "config";

    private Config() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Subscription id of the main SIM that is never switched off, or -1 if not chosen yet. */
    static int primary(Context context) {
        return prefs(context).getInt("primary", -1);
    }

    static void setPrimary(Context context, int subId) {
        prefs(context).edit().putInt("primary", subId).apply();
    }

    /** Subscription ids behind the tiles, in tile order. */
    static List<Integer> tiles(Context context) {
        List<Integer> ids = new ArrayList<>();
        for (String part : prefs(context).getString("tiles", "").split(",")) {
            if (part.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                // A corrupt entry just drops out of the list.
            }
        }
        return ids;
    }

    static void setTiles(Context context, List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size() && i < MAX_TILES; i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        prefs(context).edit().putString("tiles", sb.toString()).apply();
    }

    /** The user has read the warning about switching SIMs off. */
    static boolean acknowledged(Context context) {
        return prefs(context).getBoolean("acknowledged", false);
    }

    static void setAcknowledged(Context context) {
        prefs(context).edit().putBoolean("acknowledged", true).apply();
    }
}
