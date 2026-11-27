package app.binnacle;

/**
 * The route the iPhone is guiding to, as xcertplay reports it (CarPlayLink, issue #180): the
 * distance left. Read only: the battery column shows it, with an estimate of the battery % at
 * arrival on short trips.
 */
final class Route {
    /** Metres left to the destination. */
    final long distanceM;

    Route(long distanceM) {
        this.distanceM = Math.max(0, distanceM);
    }

    /**
     * Longest trip, in km, that gets an arrival estimate. Beyond it the % drifts too far from the
     * car's own figure (climbs, motorway speed, heating), so only the distance is shown.
     */
    static final double MAX_ESTIMATE_KM = 45;

    /**
     * The battery % at arrival: now [soc] %, [km] to go, [kmPerPercent] km on each %. -1 when
     * there is no sensible estimate (no %, no rate, or a trip over MAX_ESTIMATE_KM). Never below 0.
     */
    static int arrivalPercent(int soc, double km, double kmPerPercent) {
        if (soc < 0 || km < 0 || km > MAX_ESTIMATE_KM || !(kmPerPercent > 0)) return -1;
        return (int) Math.max(0, Math.round(soc - km / kmPerPercent));
    }

    /** A distance for the line: one decimal under 10, whole numbers above. */
    static String distanceText(double value) {
        if (value < 9.95) return String.format(java.util.Locale.UK, "%.1f", value);
        return String.valueOf(Math.round(value));
    }
}
