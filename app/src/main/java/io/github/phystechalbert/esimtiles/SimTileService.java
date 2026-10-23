package io.github.phystechalbert.esimtiles;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.SubscriptionInfo;
import android.widget.Toast;

import java.util.List;

/** Quick Settings tile for one of the SIMs picked on the setup screen: a tap switches to it. */
public abstract class SimTileService extends TileService {

    public static class Tile1 extends SimTileService {
        @Override int position() { return 0; }
    }

    public static class Tile2 extends SimTileService {
        @Override int position() { return 1; }
    }

    public static class Tile3 extends SimTileService {
        @Override int position() { return 2; }
    }

    public static class Tile4 extends SimTileService {
        @Override int position() { return 3; }
    }

    public static class Tile5 extends SimTileService {
        @Override int position() { return 4; }
    }

    public static class Tile6 extends SimTileService {
        @Override int position() { return 5; }
    }

    /** Tile services by position, Config.MAX_TILES of them. */
    static final Class<?>[] TILES = {Tile1.class, Tile2.class, Tile3.class, Tile4.class, Tile5.class, Tile6.class};

    /** Position of this tile in Config.tiles. */
    abstract int position();

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Keep redrawing until this moment even with nothing pending: the new SIM shows up a bit after the result. */
    private long pollUntil;

    /** While the shade is open and a switch is running, redraw every couple of seconds until it lands. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (Esim.pendingTarget(SimTileService.this) >= 0 || System.currentTimeMillis() < pollUntil) {
                handler.postDelayed(this, 2000);
            }
        }
    };

    /** A switch started or finished (from any tile or the app): redraw and poll for a while. */
    private final SharedPreferences.OnSharedPreferenceChangeListener pendingChanged = (prefs, key) -> {
        pollUntil = System.currentTimeMillis() + 10_000;
        handler.removeCallbacks(poll);
        poll.run();
    };

    @Override
    public void onStartListening() {
        Esim.prefs(this).registerOnSharedPreferenceChangeListener(pendingChanged);
        Esim.whenBinderReady(poll, this::refresh);
    }

    @Override
    public void onStopListening() {
        Esim.prefs(this).unregisterOnSharedPreferenceChangeListener(pendingChanged);
        handler.removeCallbacks(poll);
    }

    @Override
    public void onClick() {
        Esim.whenBinderReady(this::switchHere, this::openApp);
    }

    /** Subscription id configured for this tile, or -1. */
    private int configuredSim() {
        List<Integer> tiles = Config.tiles(this);
        return position() < tiles.size() ? tiles.get(position()) : -1;
    }

    private void switchHere() {
        int subId = configuredSim();
        if (!Esim.ready() || subId < 0 || !Config.acknowledged(this)) {
            openApp();
            return;
        }
        try {
            Esim.State state = Esim.read();
            SubscriptionInfo sim = state.find(subId);
            if (sim == null) {
                openApp();
                return;
            }
            if (state.isActive(subId)) return;
            Esim.switchTo(this, sim);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.switch_failed, Esim.describe(this, e)), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SubscriptionInfo sim = null;
        int subId = configuredSim();
        if (!Esim.ready()) {
            tile.setLabel(getString(R.string.app_name));
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(getString(R.string.tile_needs_shizuku));
        } else if (subId < 0) {
            tile.setLabel(getString(R.string.app_name));
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.tile_not_set));
        } else {
            try {
                Esim.State state = Esim.read();
                sim = state.find(subId);
                if (sim == null) {
                    tile.setLabel(getString(R.string.app_name));
                    tile.setState(Tile.STATE_UNAVAILABLE);
                    tile.setSubtitle(getString(R.string.tile_missing));
                } else {
                    tile.setLabel(Esim.label(sim));
                    if (Esim.pendingTarget(this) == subId) {
                        tile.setState(Tile.STATE_ACTIVE);
                        tile.setSubtitle(getString(R.string.tile_switching));
                    } else if (state.isActive(subId)) {
                        tile.setState(Tile.STATE_ACTIVE);
                        tile.setSubtitle(getString(R.string.tile_on));
                    } else {
                        tile.setState(Tile.STATE_INACTIVE);
                        tile.setSubtitle(getString(R.string.tile_off));
                    }
                }
            } catch (Exception e) {
                tile.setLabel(getString(R.string.app_name));
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.tile_error));
            }
        }
        // Small tiles show only the icon, so it is what tells them apart. The shade tints a
        // highlighted tile's icon white and the others dark; flags are drawn for that tint.
        if (sim != null) {
            tile.setIcon(FlagIcons.forSim(sim, tile.getState() == Tile.STATE_ACTIVE));
        } else {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_sim));
        }
        tile.updateTile();
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }
}
