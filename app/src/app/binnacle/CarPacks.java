package app.binnacle;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads car colour packs (renders for one colour and driving side) from this project's GitHub
 * release, over Wi-Fi, into the app's own files dir where CarArt looks first:
 * files/<hand>/teshila_car_<colour>/. Only silver is bundled in the APK.
 *
 * Fixed source: RELEASE_BASE + "binnacle-cars-<hand>-<colour>.zip" (the guard pins both), HTTPS
 * only, download only (GET). Entries are unpacked only if they stay inside the pack folder and are
 * images or text; the pack replaces any older copy only once it has fully unpacked. Nothing here
 * touches the car.
 */
final class CarPacks {
    static final String RELEASE_BASE = "https://github.com/lotech/binnacle/releases/download/cars-v1/";
    static final String[] HANDS = {"rhd", "lhd"};
    /** Same order as the car's carcolor value. */
    static final String[] COLOURS = {"white", "red", "blue", "black", "silver"};
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int TIMEOUT_MS = 20_000;

    interface Progress {
        /** Bytes unpacked so far (called on the download thread). */
        void onProgress(long bytes);
    }

    private CarPacks() {}

    static String packName(String hand, String colour) { return "binnacle-cars-" + hand + "-" + colour + ".zip"; }

    static File packDir(Context c, String hand, String colour) {
        File root = c.getExternalFilesDir(null);
        return root == null ? null : new File(new File(root, hand), "teshila_car_" + colour);
    }

    /** True if a downloaded pack for this colour and side is present. */
    static boolean downloaded(Context c, String hand, String colour) {
        File d = packDir(c, hand, colour);
        return d != null && new File(d, "car/teshila_car_bg(0).png").isFile();
    }

    /** True if the APK bundles this colour and side. */
    static boolean bundled(Context c, String hand, String colour) {
        try {
            String[] list = c.getAssets().list("cars/" + hand + "/teshila_car_" + colour);
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Blocking: downloads and unpacks one pack. Call off the main thread. Throws with a short reason. */
    static void download(Context c, String hand, String colour, Progress progress) throws IOException {
        if (!contains(HANDS, hand) || !contains(COLOURS, colour)) throw new IOException("unknown pack");
        File dest = packDir(c, hand, colour);
        if (dest == null) throw new IOException("no storage");
        File tmp = new File(dest.getParentFile(), ".download-" + colour);
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("can't create folder");

        HttpURLConnection http = (HttpURLConnection) new URL(RELEASE_BASE + packName(hand, colour)).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setInstanceFollowRedirects(true);   // GitHub serves release files through an HTTPS redirect
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException(code == 404 ? "pack not published yet" : "server said " + code);
            if (!"https".equals(http.getURL().getProtocol())) throw new IOException("not HTTPS");
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(http.getInputStream()))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName();
                    if (e.isDirectory()) continue;
                    if (!(name.endsWith(".png") || name.endsWith(".txt"))) continue;
                    File out = new File(tmp, name);
                    if (!isInside(tmp, out)) throw new IOException("bad entry in pack");
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create folder");
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_BYTES) throw new IOException("pack too large");
                            os.write(buf, 0, n);
                        }
                    }
                    if (progress != null) progress.onProgress(total);
                }
            }
        } finally {
            http.disconnect();
        }
        if (!new File(tmp, "car/teshila_car_bg(0).png").isFile()) {
            deleteTree(tmp);
            throw new IOException("pack incomplete");
        }
        deleteTree(dest);
        if (!tmp.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException("can't install pack");
        }
    }

    /** Removes a downloaded pack (the bundled one, if any, is used again). */
    static void remove(Context c, String hand, String colour) {
        File d = packDir(c, hand, colour);
        if (d != null) deleteTree(d);
    }

    /** True if f is inside dir (no "..", absolute paths or links out). */
    static boolean isInside(File dir, File f) {
        try {
            String root = dir.getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean contains(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
