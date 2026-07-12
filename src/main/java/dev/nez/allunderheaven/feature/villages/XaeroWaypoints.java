package dev.nez.allunderheaven.feature.villages;

/**
 * Builds Xaero's Minimap waypoint-share chat strings. Clients running Xaero's
 * Minimap render such a message as "<name> [Add]" — clicking Add creates the
 * waypoint, which also shows on Xaero's World Map. Clients without the mod see
 * the raw string, so sending these is config-gated.
 *
 * <p>Format (verified against xaero.hud.minimap.waypoint.WaypointSharingHandler
 * in xaerominimap-neoforge-26.2-26.2.0):
 * {@code xaero-waypoint:NAME:INITIALS:X:Y:Z:COLOR:DISABLED:TYPE:Internal-<dim>-waypoints}
 * — {@code Y} may be {@code ~} (unspecified), colons in names must be escaped
 * as {@code ^col^}.
 */
public final class XaeroWaypoints {
    private XaeroWaypoints() {
    }

    /** Share string for an overworld waypoint (villages only spawn there). */
    public static String shareString(String name, int x, int z) {
        String safeName = name.replace(":", "^col^");
        int color = Math.floorMod(name.hashCode(), 16);
        return "xaero-waypoint:" + safeName
                + ":" + initialsOf(name)
                + ":" + x
                + ":~:" + z
                + ":" + color
                + ":false:0:Internal-overworld-waypoints";
    }

    private static String initialsOf(String name) {
        String[] words = name.split(" ");
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < words.length && initials.length() < 2; i++) {
            if (!words[i].isEmpty()) {
                initials.append(words[i].charAt(0));
            }
        }
        return initials.isEmpty() ? "V" : initials.toString();
    }
}
