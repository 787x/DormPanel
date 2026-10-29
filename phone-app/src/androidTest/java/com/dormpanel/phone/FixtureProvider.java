package com.dormpanel.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.Arrays;

/** Test APK only. Java keeps this separate provider process independent of the target Kotlin runtime. */
public class FixtureProvider extends ContentProvider {
    private final java.util.concurrent.atomic.AtomicInteger exportOpens = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.util.concurrent.CountDownLatch exportGate = new java.util.concurrent.CountDownLatch(1);
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (method.equals("reset-export")) { exportOpens.set(0); exportGate = new java.util.concurrent.CountDownLatch(1); }
        if (method.equals("release-export")) exportGate.countDown();
        android.os.Bundle result = new android.os.Bundle(); result.putInt("opens",exportOpens.get()); return result;
    }
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return uri.getLastPathSegment().endsWith("xls") ? "application/vnd.ms-excel" : uri.getLastPathSegment().endsWith("csv") ? "text/csv" : "text/calendar"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME });
        cursor.addRow(new Object[] { uri.getLastPathSegment() });
        return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name.startsWith("export-slow") && mode.contains("w")) {
            exportOpens.incrementAndGet();
            try { if (!exportGate.await(30,java.util.concurrent.TimeUnit.SECONDS)) throw new FileNotFoundException("gate timeout"); }
            catch (InterruptedException e) { throw new FileNotFoundException("interrupted"); }
            if (name.equals("export-slow-fail.ics")) throw new FileNotFoundException("injected write failure");
        }
        if (name.equals("export-slow.ics")) return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),name), ParcelFileDescriptor.parseMode(mode));
        if (name.equals("export.ics")) return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),name), ParcelFileDescriptor.parseMode(mode));
        if (name.equals("export-fail.ics")) throw new FileNotFoundException("injected disk full");
        if (!Arrays.asList("wakeup.ics", "hubei_2026-2027-1.csv", "hubei_2026-2027-1.xls", "bad.ics", "future.csv", "night.ics").contains(name)) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), name);
        try (OutputStream out = new FileOutputStream(file)) {
            if (name.equals("bad.ics")) out.write("not a calendar".getBytes("UTF-8"));
            else if (name.equals("future.csv")) {
                try (InputStream in = getContext().getAssets().open("hubei_2026-2027-1.csv")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    out.write(bytes.toString("UTF-8").replace("2026-2027-1", "2027-2028-1").getBytes("UTF-8"));
                }
            } else try (InputStream in = getContext().getAssets().open(name)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues values) { return null; }
    public int delete(Uri uri, String selection, String[] args) { return 0; }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
