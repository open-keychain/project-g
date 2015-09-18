/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.gm;


import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/**
 * TemporaryStorageProvider stores decrypted files inside the app's cache directory previously to
 * sharing them with other applications.
 * <p/>
 * Security:
 * - It is writable by OpenKeychain only (see Manifest), but exported for reading files
 * - It uses UUIDs as identifiers which makes predicting files from outside impossible
 * - Querying a number of files is not allowed, only querying single files
 * -> You can only open a file if you know the Uri containing the precise UUID, this Uri is only
 * revealed when the user shares a decrypted file with another app.
 * <p/>
 * Why is support lib's FileProvider not used?
 * Because granting Uri permissions temporarily does not work correctly. See
 * - https://code.google.com/p/android/issues/detail?id=76683
 * - https://github.com/nmr8acme/FileProvider-permission-bug
 * - http://stackoverflow.com/q/24467696
 * - http://stackoverflow.com/q/18249007
 * - Comments at http://www.blogc.at/2014/03/23/share-private-files-with-other-apps-fileprovider/
 */
public class TemporaryStorageProvider extends ContentProvider {

    private static final String DB_NAME = "tempstorage.db";
    private static final String TABLE_FILES = "files";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_TIME = "time";
    private static final String COLUMN_TYPE = "mimetype";
    public static final String AUTHORITY = Constants.TEMPSTORAGE_AUTHORITY;
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    private static final int DB_VERSION = 3;

    private static File cacheDir;

    public static Uri createFile(Context context, String targetName, String mimeType) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_NAME, targetName);
        contentValues.put(COLUMN_TYPE, mimeType);
        return context.getContentResolver().insert(CONTENT_URI, contentValues);
    }

    public static Uri createFile(Context context, String targetName) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_NAME, targetName);
        return context.getContentResolver().insert(CONTENT_URI, contentValues);
    }

    public static Uri createFile(Context context) {
        ContentValues contentValues = new ContentValues();
        return context.getContentResolver().insert(CONTENT_URI, contentValues);
    }

    public static int setMimeType(Context context, Uri uri, String mimetype) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_TYPE, mimetype);
        return context.getContentResolver().update(uri, values, null, null);
    }

    public static int cleanUp(Context context) {
        return context.getContentResolver().delete(CONTENT_URI, COLUMN_TIME + "< ?",
                new String[]{Long.toString(System.currentTimeMillis() - Constants.TEMPFILE_TTL)});
    }

    private class TemporaryStorageDatabase extends SQLiteOpenHelper {

        public TemporaryStorageDatabase(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FILES + " (" +
                    COLUMN_ID + " TEXT PRIMARY KEY, " +
                    COLUMN_NAME + " TEXT, " +
                    COLUMN_TYPE + " TEXT, " +
                    COLUMN_TIME + " INTEGER" +
                    ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(Constants.TAG, "Upgrading files db from " + oldVersion + " to " + newVersion);

            switch (oldVersion) {
                case 1:
                    db.execSQL("DROP TABLE IF EXISTS files");
                    db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FILES + " (" +
                            COLUMN_ID + " TEXT PRIMARY KEY, " +
                            COLUMN_NAME + " TEXT, " +
                            COLUMN_TIME + " INTEGER" +
                            ");");
                case 2:
                    db.execSQL("ALTER TABLE files ADD COLUMN " + COLUMN_TYPE + " TEXT");
            }
        }
    }

    private static TemporaryStorageDatabase db;

    private File getFile(Uri uri) throws FileNotFoundException {
        try {
            return getFile(uri.getLastPathSegment());
        } catch (NumberFormatException e) {
            throw new FileNotFoundException();
        }
    }

    private File getFile(String id) {
        return new File(cacheDir, "temp/" + id);
    }

    @Override
    public boolean onCreate() {
        db = new TemporaryStorageDatabase(getContext());
        cacheDir = getContext().getCacheDir();
        return new File(cacheDir, "temp").mkdirs();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri.getLastPathSegment() == null) {
            throw new SecurityException("Listing temporary files is not allowed, only querying single files.");
        }

        Log.d(Constants.TAG, "being asked for file " + uri);

        File file;
        try {
            file = getFile(uri);
            if (file.exists()) {
                Log.e(Constants.TAG, "already exists");
            }
        } catch (FileNotFoundException e) {
            Log.e(Constants.TAG, "file not found!");
            return null;
        }

        Cursor fileName = db.getReadableDatabase().query(TABLE_FILES, new String[]{COLUMN_NAME}, COLUMN_ID + "=?",
                new String[]{uri.getLastPathSegment()}, null, null, null);
        if (fileName != null) {
            if (fileName.moveToNext()) {
                MatrixCursor cursor = new MatrixCursor(new String[]{
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE,
                        "_data"
                });
                cursor.newRow()
                        .add(fileName.getString(0))
                        .add(file.length())
                        .add(file.getAbsolutePath());
                fileName.close();
                return cursor;
            }
            fileName.close();
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        Cursor cursor = db.getReadableDatabase().query(TABLE_FILES,
                new String[]{COLUMN_TYPE}, COLUMN_ID + "=?",
                new String[]{uri.getLastPathSegment()}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) {
                        return cursor.getString(0);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return "application/octet-stream";
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        String type = getType(uri);
        if (ClipDescription.compareMimeTypes(type, mimeTypeFilter)) {
            return new String[]{type};
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!values.containsKey(COLUMN_TIME)) {
            values.put(COLUMN_TIME, System.currentTimeMillis());
        }
        String uuid = UUID.randomUUID().toString();
        values.put(COLUMN_ID, uuid);
        int insert = (int) db.getWritableDatabase().insert(TABLE_FILES, null, values);
        if (insert == -1) {
            Log.e(Constants.TAG, "Insert failed!");
            return null;
        }
        try {
            getFile(uuid).createNewFile();
        } catch (IOException e) {
            Log.e(Constants.TAG, "File creation failed!");
            return null;
        }
        return Uri.withAppendedPath(CONTENT_URI, uuid);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (uri == null || uri.getLastPathSegment() == null) {
            return 0;
        }

        selection = DatabaseUtil.concatenateWhere(selection, COLUMN_ID + "=?");
        selectionArgs = DatabaseUtil.appendSelectionArgs(selectionArgs, new String[]{uri.getLastPathSegment()});

        Cursor files = db.getReadableDatabase().query(TABLE_FILES, new String[]{COLUMN_ID}, selection,
                selectionArgs, null, null, null);
        if (files != null) {
            while (files.moveToNext()) {
                getFile(files.getString(0)).delete();
            }
            files.close();
            return db.getWritableDatabase().delete(TABLE_FILES, selection, selectionArgs);
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (values.size() != 1 || !values.containsKey(COLUMN_TYPE)) {
            throw new UnsupportedOperationException("Update supported only for type field!");
        }
        if (selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException("Update supported only for plain uri!");
        }
        return db.getWritableDatabase().update(TABLE_FILES, values,
                COLUMN_ID + " = ?", new String[]{uri.getLastPathSegment()});
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Log.d(Constants.TAG, "openFile");
        return openFileHelper(uri, mode);
    }

}
