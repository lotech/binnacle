package app.binnacle;

/** Plain-Java checks for Route's arrival estimate (run by tests/run.sh). */
public class RouteTest {
    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // 80 %, 4.3 km at 4.1 km per % (410 km at 100 %): 80 - 1.05 = 79
        check("short trip", Route.arrivalPercent(80, 4.259, 4.1) == 79);
        check("45 km is still estimated", Route.arrivalPercent(60, 45, 4.1) == 49);
        check("over 45 km: no estimate", Route.arrivalPercent(60, 45.1, 4.1) == -1);
        check("never below 0", Route.arrivalPercent(3, 40, 4.1) == 0);
        check("no % yet", Route.arrivalPercent(-1, 4, 4.1) == -1);
        check("no rate", Route.arrivalPercent(50, 4, 0) == -1 && Route.arrivalPercent(50, 4, Double.NaN) == -1);
        check("arrived: the % now", Route.arrivalPercent(50, 0, 4.1) == 50);
        check("4.259 km reads 4.3", "4.3".equals(Route.distanceText(4.259)));
        check("9.96 km reads 10", "10".equals(Route.distanceText(9.96)));
        check("212.4 km reads 212", "212".equals(Route.distanceText(212.4)));
        check("a negative distance is 0 m", new Route(-5).distanceM == 0);
        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("all route checks passed");
    }
}
