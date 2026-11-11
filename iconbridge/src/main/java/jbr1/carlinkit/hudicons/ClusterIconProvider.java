package jbr1.carlinkit.hudicons;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Claims {@code com.google.android.apps.automotive.templates.host.ClusterIconContentProvider}.
 *
 * <p>GM's Templates Host references this authority when converting CarIcon maneuver icons into
 * cluster/HUD nav state, but never registers a provider for it. The first failed {@code insert()}
 * latches {@code skipIcons = true} for the session — HUD shows road + distance, no turn glyph.
 *
 * <p>Play Console locks that authority to its first publisher (upstream {@code zeno.carlink},
 * lvalen91/carlink#6), so the Play-distributed {@code jbr1.carlinkit} app cannot declare it.
 * This sideloaded companion can. Implemented in Java so the module needs no Kotlin Gradle
 * plugin (avoids Android Studio's {@code prepareKotlinBuildScriptModel} sync failure).
 */
public final class ClusterIconProvider extends ContentProvider {
    private static final String TAG = "CarlinkHudIcons";
    private static final String AUTHORITY =
            "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider";
    private static final int MAX_CONTENT_ENTRIES = 20;
    private static final int MAX_ICON_ID_ENTRIES = 256;
    private static final int MAX_SCALED_ENTRIES = 20;

    private final Map<String, byte[]> pngByContent = lruMap(MAX_CONTENT_ENTRIES);
    private final Map<String, String> contentByIconId = lruMap(MAX_ICON_ID_ENTRIES);
    private final Map<String, byte[]> scaledPng = lruMap(MAX_SCALED_ENTRIES);

    @Override
    public boolean onCreate() {
        Log.i(TAG, "Cluster icon provider registered (" + AUTHORITY + ")");
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        String iconId = values != null ? values.getAsString("iconId") : null;
        byte[] data = values != null ? values.getAsByteArray("data") : null;
        if (iconId == null || data == null) {
            Log.w(
                    TAG,
                    "insert() missing iconId or data (iconId="
                            + iconId
                            + ", dataSize="
                            + (data != null ? data.length : null)
                            + ")");
            return Uri.parse("content://" + AUTHORITY + "/img/unknown");
        }
        String contentKey = contentKey(data);
        pngByContent.put(contentKey, data);
        contentByIconId.put(iconId, contentKey);
        return contentUri(contentKey);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"contentUri", "aspectRatio"});
        if (selection == null) {
            return cursor;
        }

        String contentKey = contentByIconId.get(selection);
        byte[] data = contentKey != null ? pngByContent.get(contentKey) : null;
        if (contentKey == null || data == null) {
            return cursor;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        double aspectRatio = opts.outHeight > 0 ? (double) opts.outWidth / opts.outHeight : 1.0;
        cursor.addRow(new Object[] {contentUri(contentKey).toString(), aspectRatio});
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        String contentKey = uri.getLastPathSegment();
        byte[] data = contentKey != null ? pngByContent.get(contentKey) : null;
        if (contentKey == null || data == null) {
            Log.w(TAG, "openFile() cache miss for " + contentKey);
            return null;
        }

        Integer targetW = parsePositiveInt(uri.getQueryParameter("w"));
        Integer targetH = parsePositiveInt(uri.getQueryParameter("h"));
        final byte[] output;
        if (targetW != null && targetH != null) {
            String scaledKey = contentKey + "@" + targetW + "x" + targetH;
            byte[] cached = scaledPng.get(scaledKey);
            if (cached != null) {
                output = cached;
            } else {
                output = scaleIcon(data, targetW, targetH);
                scaledPng.put(scaledKey, output);
            }
        } else {
            output = data;
        }

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor writeEnd = pipe[1];
            new Thread(
                            () -> {
                                try (ParcelFileDescriptor.AutoCloseOutputStream out =
                                        new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                                    out.write(output);
                                } catch (IOException e) {
                                    Log.e(
                                            TAG,
                                            "openFile() pipe write error for "
                                                    + contentKey
                                                    + ": "
                                                    + e.getMessage());
                                }
                            },
                            "HudIconPipe")
                    .start();
            return pipe[0];
        } catch (IOException e) {
            Log.e(TAG, "openFile() failed to create pipe: " + e.getMessage());
            return null;
        }
    }

    private static Uri contentUri(String contentKey) {
        return Uri.parse("content://" + AUTHORITY + "/img/" + contentKey);
    }

    private static String contentKey(byte[] png) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
            StringBuilder hex = new StringBuilder("png_");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] scaleIcon(byte[] png, int w, int h) {
        try {
            Bitmap original = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (original == null) {
                return png;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(original, w, h, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            if (scaled != original) {
                scaled.recycle();
            }
            original.recycle();
            return out.toByteArray();
        } catch (RuntimeException e) {
            Log.w(TAG, "scaleIcon(" + w + "x" + h + ") failed: " + e.getMessage() + " — serving original");
            return png;
        }
    }

    private static Integer parsePositiveInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    private static <V> Map<String, V> lruMap(final int maxEntries) {
        return Collections.synchronizedMap(
                new LinkedHashMap<String, V>(maxEntries, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                        return size() > maxEntries;
                    }
                });
    }
}
