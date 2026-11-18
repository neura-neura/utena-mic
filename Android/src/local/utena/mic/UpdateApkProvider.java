package local.utena.mic;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
    static File file(Context context) { return new File(context.getCacheDir(), "UtenaMic.apk"); }

    @Override public boolean onCreate() { return true; }

    private boolean update(Uri uri) { return "/UtenaMic.apk".equals(uri.getPath()); }

    @Override public String getType(Uri uri) {
        return update(uri) ? "application/vnd.android.package-archive" : null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!update(uri)) throw new IllegalArgumentException("APK path not found");
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        File apk = file(getContext());
        if (!apk.isFile()) return result;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = apk.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = apk.length();
        }
        result.addRow(values);
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!update(uri) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
