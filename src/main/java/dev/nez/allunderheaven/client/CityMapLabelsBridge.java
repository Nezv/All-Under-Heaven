package dev.nez.allunderheaven.client;

import java.util.List;

import xaero.common.HudMod;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointVisibilityType;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * The only class that touches Xaero's Minimap. It is loaded lazily by
 * {@link CityMapLabels} and only after {@code xaerominimap} is confirmed
 * installed, so the mod loads fine without Xaero.
 *
 * <p>City labels live in their own managed waypoint set ({@link #SET_ID}) that
 * this class fully owns: on every update the set is cleared and repopulated from
 * the current city list, so labels follow the player and never accumulate
 * duplicates. The waypoints are marked {@link WaypointVisibilityType#GLOBAL} so
 * they render on the minimap and world map regardless of the player's active
 * set. The call chain mirrors Xaero's own "add waypoint" control:
 * {@code BuiltInHudModules.MINIMAP.getCurrentSession() -> world manager ->
 * current world -> waypoint set}.
 */
final class CityMapLabelsBridge {
    private static final String SET_ID = "allunderheaven_cities";

    private CityMapLabelsBridge() {
    }

    static void updateLabels(List<CityMapLabels.City> cities) {
        if (HudMod.INSTANCE == null) {
            return; // Xaero not initialized yet
        }
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            return;
        }
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) {
            return; // no world mapped yet (e.g. still loading)
        }

        WaypointSet set = world.getWaypointSet(SET_ID);
        if (set == null) {
            world.addWaypointSet(SET_ID);
            set = world.getWaypointSet(SET_ID);
            if (set == null) {
                return;
            }
        }

        set.clear();
        for (CityMapLabels.City city : cities) {
            Waypoint waypoint = new Waypoint(city.x(), 70, city.z(), city.name(), initials(city.name()), WaypointColor.GOLD);
            waypoint.setVisibility(WaypointVisibilityType.GLOBAL);
            set.add(waypoint);
        }
    }

    /** Up to two leading initials for the waypoint icon (the full name is the label). */
    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty() && out.length() < 2) {
                out.append(word.charAt(0));
            }
        }
        return out.isEmpty() ? "C" : out.toString();
    }
}
