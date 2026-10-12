package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The two fetches this app makes: the runtime catalog (a few hundred bytes of JSON) and the
 * runtime tarball itself (~790 MB). Plain HttpURLConnection - nothing here needs a client library,
 * and one fewer dependency is one fewer thing that can fail a release build.
 *
 * <p>Every request goes through {@link DownloadSource}, which may hand back a list of interchangeable
 * URLs for the same file (a mirror in front of GitHub). A source that stalls or errors hands the
 * partial file to the next one instead of failing the whole download.
 */
public final class Downloader {
    private static final String TAG = "Downloader";
    /**
     * Short, because a blocked host is usually blocked by silence: waiting the full 30 s before
     * trying the next source turns one dead host into a minute of a stalled progress bar.
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** A silent socket is a stalled one: 15 s of waiting for the next byte is a dead source. */
    private static final int STALL_TIMEOUT_MS = 15_000;
    /** Not in HttpURLConnection's constants, which stop short of 4xx beyond 415. */
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private Downloader() {}

    public static String downloadString(String url) {
        List<String> sources = DownloadSource.INSTANCE.candidates(url);
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) Log.i(TAG, "retrying " + url + " via " + sources.get(i));
            Fetch fetch = fetch(sources.get(i));
            if (fetch.body != null) {
                DownloadSource.INSTANCE.succeeded(sources.get(i));
                return fetch.body;
            }
            if (fetch.fatal) return null;
        }
        DownloadSource.INSTANCE.failed();
        return null;
    }

    /**
     * Downloads to {@code destination}, reporting a 0..1 fraction (or -1 when the server sends no
     * length). {@code resume} continues a partial file with a Range request, which is what makes a
     * 790 MB download survive a dropped connection instead of starting over.
     */
    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress) {
        List<String> sources = DownloadSource.INSTANCE.candidates(url);
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) Log.i(TAG, "retrying " + url + " via " + sources.get(i));
            Outcome outcome = attempt(sources.get(i), destination, resume, progress);
            if (outcome == Outcome.DONE) {
                DownloadSource.INSTANCE.succeeded(sources.get(i));
                return true;
            }
            if (outcome == Outcome.FATAL) return false;
        }
        DownloadSource.INSTANCE.failed();
        return false;
    }

    private enum Outcome { DONE, RETRY, FATAL }

    private static Outcome attempt(String url, File destination, boolean resume,
                                   java.util.function.Consumer<Float> progress) {
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        try {
            connection = open(url, STALL_TIMEOUT_MS);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            if (have > 0 && code == HTTP_RANGE_NOT_SATISFIABLE) {
                // Told to continue past the end: the file on disk is complete or too long, and only
                // starting over can tell which.
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
                return attempt(url, destination, false, progress);
            }
            if (code / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + code);
                return code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE
                        ? Outcome.FATAL : Outcome.RETRY;
            }
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (!appending && have > 0) {
                // The server ignored or refused the range: start the file again.
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
                return attempt(url, destination, false, progress);
            }
            if (appending && have > 0 && contentRangeStart(connection.getHeaderField("Content-Range")) != have) {
                // A proxy that answers 206 with the whole file would otherwise be appended to what
                // we already have.
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
                return attempt(url, destination, false, progress);
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            long total = length < 0 ? -1 : length + have;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            long written = have;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    // A callback per 64 KB would be tens of thousands of UI hops; once per MB is
                    // enough to move a progress bar smoothly.
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            }
            if (total > 0 && written < total) return Outcome.RETRY;
            if (progress != null) progress.accept(1f);
            return Outcome.DONE;
        } catch (Exception e) {
            Log.w(TAG, "download " + url, e);
            return Outcome.RETRY;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static long contentRangeStart(String header) {
        if (header == null) return -1;
        int space = header.indexOf(' ');
        int dash = header.indexOf('-', space + 1);
        if (space < 0 || dash < 0) return -1;
        try {
            return Long.parseLong(header.substring(space + 1, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class Fetch {
        final String body;
        final boolean fatal;

        Fetch(String body, boolean fatal) {
            this.body = body;
            this.fatal = fatal;
        }
    }

    private static Fetch fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, STALL_TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + code);
                return new Fetch(null, code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE);
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                FileUtils.copy(in, out);
                return new Fetch(new String(out.toByteArray(), StandardCharsets.UTF_8), false);
            }
        } catch (Exception e) {
            Log.w(TAG, "GET " + url, e);
            return new Fetch(null, false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int readTimeoutMs) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
