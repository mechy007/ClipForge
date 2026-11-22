package com.clipforge.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Test-only document destination with an injectable create failure. */
public final class BatchExportTestProvider extends ContentProvider {
    public static final String AUTHORITY = "com.clipforge.app.test.batch";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            if ("fixture:allow".equals(method)) {
                new File(folder(arg), "allow").createNewFile();
                return new Bundle();
            }
            if ("fixture:cleanup".equals(method)) {
                File directory = folder(arg);
                File[] children = directory.listFiles();
                if (children != null) {
                    for (File child : children) child.delete();
                }
                directory.delete();
                return new Bundle();
            }
            if ("fixture:count".equals(method)) {
                File[] outputs = folder(arg).listFiles((directory, name) -> name.endsWith(".mp4"));
                Bundle result = new Bundle();
                result.putInt("outputs", outputs == null ? 0 : outputs.length);
                return result;
            }
            if (extras == null) return null;
            @SuppressWarnings("deprecation") Uri uri = extras.getParcelable("uri");
            if (uri == null) return null;
            String id = DocumentsContract.getDocumentId(uri);
            if ("android:createDocument".equals(method)) {
                String name = validName(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                File directory = folder(id);
                if (name.endsWith("_blocked.mp4") && !new File(directory, "allow").exists()) return null;
                if (!new File(directory, name).createNewFile()) {
                    throw new IllegalStateException("The batch attempted to create a duplicate document.");
                }
                Bundle result = new Bundle();
                result.putParcelable("uri", DocumentsContract.buildDocumentUri(AUTHORITY, id + "/" + name));
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                document(id).delete();
                return new Bundle();
            }
            return null;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = document(DocumentsContract.getDocumentId(uri));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "video/mp4";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File folder(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Invalid test folder");
        File root = new File(getContext().getCacheDir(), "batch-export-fixtures");
        File directory = new File(root, id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create test folder");
        return directory;
    }

    private File document(String id) {
        String[] parts = id.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid test document ID");
        return new File(folder(parts[0]), validName(parts[1]));
    }

    private String validName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.mp4")) {
            throw new IllegalArgumentException("Invalid output filename");
        }
        return name;
    }
}
