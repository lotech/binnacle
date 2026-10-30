package app.binnacle;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.InputStream;

/**
 * Car renders. The app bundles its own, rendered from a CC BY 4.0 model ("Tesla 2018 Model 3" by
 * Ameer Studio, see assets/cars/CREDITS.txt), in assets/cars/teshila_car_<colour>/ with the same
 * file names and layout as the stock ZIQI renders (the stock dash's own renders are never used).
 * Where to look, first match wins:
 * <ol>
 *   <li>a pack in this app's external files dir (another colour, or new renders without a build);</li>
 *   <li>the bundled pack for the car's colour;</li>
 *   <li>any bundled colour (silver first).</li>
 * </ol>
 * If none are found the views draw their own simple car shapes instead.
 */
final class CarArt {
    /** Index = the car's "carcolor" value, same order as the stock launcher's BitMapLoader. */
    private static final String[] COLOURS = {"white", "red", "blue", "black", "silver"};
    private static final String ASSET_ROOT = "cars";

    static final CarArt NONE = new CarArt(null, null, null, null);

    /** Where a colour's renders come from: a folder on the unit, or the bundled assets. */
    abstract static class Source {
        /** The colour folder's name, e.g. teshila_car_white. */
        final String name;
        Source(String name) { this.name = name; }

        /** The image at rel (e.g. "car/teshila_car_bg(3).png"), or null if missing or undecodable. */
        abstract Bitmap decode(String rel);
    }

    final Bitmap side, rear, top;
    /** The colour's renders (null if none), for loading door frames on demand. */
    final Source src;

    private CarArt(Source src, Bitmap side, Bitmap rear, Bitmap top) {
        this.src = src;
        this.side = side;
        this.rear = rear;
        this.top = top;
    }

    static CarArt load(Context c, int carColour) {
        Source s = find(c, carColour);
        if (s == null) return NONE;
        return new CarArt(s,
                s.decode("car/teshila_car_bg(0).png"),
                s.decode("car_bg/teshila_middlecar_bg.png"),
                s.decode("tpmscar/tpmscar.png"));
    }

    private static Source find(Context c, int carColour) {
        String preferred = carColour >= 0 && carColour < COLOURS.length ? COLOURS[carColour] : "silver";
        AssetManager am = c.getAssets();
        Source s = folder(c.getExternalFilesDir(null), preferred);
        if (s == null) s = asset(am, preferred);
        for (int i = COLOURS.length - 1; s == null && i >= 0; i--) s = asset(am, COLOURS[i]);   // silver first
        return s;
    }

    private static Source folder(File root, String colour) {
        if (root == null) return null;
        File d = new File(root, "teshila_car_" + colour);
        return d.isDirectory() ? new FolderSource(d) : null;
    }

    private static Source asset(AssetManager am, String colour) {
        String dir = ASSET_ROOT + "/teshila_car_" + colour;
        try {
            String[] list = am.list(dir);
            return list != null && list.length > 0 ? new AssetSource(am, dir) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class FolderSource extends Source {
        private final File dir;
        FolderSource(File dir) { super(dir.getName()); this.dir = dir; }

        @Override Bitmap decode(String rel) {
            try {
                File f = new File(dir, rel);
                return f.canRead() ? BitmapFactory.decodeFile(f.getPath()) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean equals(Object o) { return o instanceof FolderSource && ((FolderSource) o).dir.equals(dir); }
        @Override public int hashCode() { return dir.hashCode(); }
    }

    private static final class AssetSource extends Source {
        private final AssetManager am;
        private final String dir;
        AssetSource(AssetManager am, String dir) { super(dir.substring(dir.lastIndexOf('/') + 1)); this.am = am; this.dir = dir; }

        @Override Bitmap decode(String rel) {
            try (InputStream in = am.open(dir + "/" + rel)) {
                return BitmapFactory.decodeStream(in);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean equals(Object o) { return o instanceof AssetSource && ((AssetSource) o).dir.equals(dir); }
        @Override public int hashCode() { return dir.hashCode(); }
    }
}
