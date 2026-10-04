package com.brouken.player;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public class SmbDataSource extends BaseDataSource {

    private static final String TAG = "SmbDataSource";
    private static final int NUM_READERS = 4;
    private static final int READ_BLOCK_SIZE = 1024 * 1024;
private static final int PREFETCH_QUEUE_SIZE = 10;

    public static class SmbConfig {
        public final String host, share, path, user, password, domain;
        public SmbConfig(String host, String share, String path,
                         String user, String password, String domain) {
            this.host = host; this.share = share; this.path = path;
            this.user = user; this.password = password; this.domain = domain;
        }
    }

    private static class Reader {
        SMBClient client;
        Connection conn;
        Session session;
        DiskShare share;
        File file;
        int id;
    }

    private Reader[] readers = new Reader[NUM_READERS];
    private SmbConfig config;
    private ExecutorService executor;

    private Uri uri;
    private long fileSize;
    private long position;
    private long bytesRemaining;
    private boolean opened;

    private BlockingQueue<byte[]> queue;
    private Thread schedulerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private byte[] currentBlock;
    private int currentBlockPos;

    public SmbDataSource() { super(true); }

    public static SmbConfig parseUri(Uri uri, String user, String password, String domain) {
        if (uri == null || uri.getHost() == null || uri.getPathSegments().isEmpty()) {
            throw new IllegalArgumentException("Invalid smb:// URI");
        }
        String host = uri.getHost();
        String share = uri.getPathSegments().get(0);
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 1; i < uri.getPathSegments().size(); i++) {
            if (pathBuilder.length() > 0) pathBuilder.append('\\');
            pathBuilder.append(uri.getPathSegments().get(i));
        }
        return new SmbConfig(host, share, pathBuilder.toString(), user, password, domain);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Log.e(TAG, "!!! open() pos=" + dataSpec.position);
        this.uri = dataSpec.uri;
        this.position = dataSpec.position;
        this.config = parseUri(uri, SmbCredentials.user, SmbCredentials.password, SmbCredentials.domain);

        transferInitializing(dataSpec);

        try {
            for (int i = 0; i < NUM_READERS; i++) {
                readers[i] = openReader(i, config);
            }
            Log.e(TAG, "!!! all readers ready");

            fileSize = readers[0].file.getFileInformation().getStandardInformation().getEndOfFile();
            bytesRemaining = fileSize - position;

            queue = new ArrayBlockingQueue<>(PREFETCH_QUEUE_SIZE);
            executor = Executors.newFixedThreadPool(NUM_READERS);

            startScheduler(position);

            opened = true;
            transferStarted(dataSpec);
            Log.e(TAG, "!!! open SUCCESS fileSize=" + fileSize);
            return bytesRemaining;

        } catch (Exception e) {
            Log.e(TAG, "!!! open EXCEPTION", e);
            close();
            throw new IOException("SMB open failed: " + e.getMessage(), e);
        }
    }

    private Reader openReader(int id, SmbConfig c) throws IOException {
        Reader r = new Reader();
        r.id = id;
        com.hierynomus.smbj.SmbConfig sc = com.hierynomus.smbj.SmbConfig.builder()
                .withTimeout(60, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withReadTimeout(60, TimeUnit.SECONDS)
                .withWriteTimeout(60, TimeUnit.SECONDS)
                .withMultiProtocolNegotiate(true)
                .build();
        r.client = new SMBClient(sc);
        r.conn = r.client.connect(c.host);
        AuthenticationContext auth = new AuthenticationContext(
                c.user,
                c.password != null ? c.password.toCharArray() : new char[0],
                c.domain != null ? c.domain : ""
        );
        r.session = r.conn.authenticate(auth);
        r.share = (DiskShare) r.session.connectShare(c.share);

        EnumSet<AccessMask> accessMask = EnumSet.of(AccessMask.GENERIC_READ);
        EnumSet<SMB2ShareAccess> shareAccess = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ);
        EnumSet<SMB2CreateOptions> opts = EnumSet.noneOf(SMB2CreateOptions.class);

        r.file = r.share.openFile(
                c.path, accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                shareAccess, SMB2CreateDisposition.FILE_OPEN, opts);
        return r;
    }

    private void startScheduler(long startPos) {
        running.set(true);
        schedulerThread = new Thread(() -> schedulerLoop(startPos), "SmbScheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    private void schedulerLoop(long startPos) {
        long pos = startPos;
        Log.e(TAG, "!!! scheduler start at " + pos);

        while (running.get() && pos < fileSize) {
            int count = (int) Math.min(NUM_READERS, (fileSize - pos + READ_BLOCK_SIZE - 1) / READ_BLOCK_SIZE);
            if (count <= 0) break;

            Future<byte[]>[] futures = new Future[count];
            long batchStart = System.currentTimeMillis();

            for (int i = 0; i < count; i++) {
                final Reader r = readers[i];
                final long readPos = pos + (long) i * READ_BLOCK_SIZE;
                final int len = (int) Math.min(READ_BLOCK_SIZE, fileSize - readPos);
                futures[i] = executor.submit(() -> readFromReader(r, readPos, len));
            }

            long elapsed = System.currentTimeMillis() - batchStart;
            int totalBytes = 0;

            for (int i = 0; i < count; i++) {
                if (!running.get()) return;
                byte[] data = null;
                try { data = futures[i].get(120, TimeUnit.SECONDS); } catch (Exception e) {
                    Log.e(TAG, "future " + i + " error", e);
                    running.set(false);
                    return;
                }
                if (data == null) { running.set(false); return; }

                while (running.get()) {
                    try {
                        if (queue.offer(data, 200, TimeUnit.MILLISECONDS)) break;
                    } catch (InterruptedException ie) { return; }
                }
                totalBytes += data.length;
            }

            long speed = (totalBytes / 1024) / Math.max(elapsed, 1);
            Log.e(TAG, "!!! batch " + count + " = " + totalBytes + "B in " + elapsed
                    + "ms speed=" + speed + "MB/s queue=" + queue.size());

            pos += (long) count * READ_BLOCK_SIZE;
        }
        Log.e(TAG, "!!! scheduler done at " + pos);
    }

    private byte[] readFromReader(Reader r, long filePos, int length) throws IOException {
        byte[] buffer = new byte[length];
        int totalGot = 0;
        while (totalGot < length) {
            int chunk = (int) Math.min(1024 * 1024, length - totalGot);
            int got = r.file.read(buffer, filePos + totalGot, totalGot, chunk);
            if (got <= 0) break;
            totalGot += got;
        }
        if (totalGot == length) return buffer;
        byte[] trimmed = new byte[totalGot];
        System.arraycopy(buffer, 0, trimmed, 0, totalGot);
        return trimmed;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;

        if (currentBlock == null || currentBlockPos >= currentBlock.length) {
            if (bytesRemaining <= 0 && (queue == null || queue.isEmpty())) {
                return C.RESULT_END_OF_INPUT;
            }
            try {
                currentBlock = (queue != null) ? queue.poll(60, TimeUnit.SECONDS) : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
            if (currentBlock == null) return C.RESULT_END_OF_INPUT;
            currentBlockPos = 0;
            bytesRemaining -= currentBlock.length;
        }

        int available = currentBlock.length - currentBlockPos;
        int toCopy = Math.min(available, length);
        System.arraycopy(currentBlock, currentBlockPos, buffer, offset, toCopy);
        currentBlockPos += toCopy;
        bytesTransferred(toCopy);
        return toCopy;
    }

    @Nullable
    @Override
    public Uri getUri() { return uri; }

    @Override
    public void close() throws IOException {
        if (!opened) return;
        opened = false;

        running.set(false);
        if (schedulerThread != null) {
            schedulerThread.interrupt();
            try { schedulerThread.join(1500); } catch (InterruptedException ignored) {}
            schedulerThread = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            executor = null;
        }
        if (queue != null) { queue.clear(); queue = null; }

        for (Reader r : readers) {
            if (r == null) continue;
            try { if (r.file != null) r.file.close(); } catch (Exception ignored) {}
            try { if (r.share != null) r.share.close(); } catch (Exception ignored) {}
            try { if (r.session != null) r.session.close(); } catch (Exception ignored) {}
            try { if (r.conn != null) r.conn.close(); } catch (Exception ignored) {}
            try { if (r.client != null) r.client.close(); } catch (Exception ignored) {}
        }
        readers = new Reader[NUM_READERS];
        currentBlock = null;

        transferEnded();
    }

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() { return new SmbDataSource(); }
    }

    public static class SmbCredentials {
        public static String user = "guest";
        public static String password = "";
        public static String domain = "WORKGROUP";
    }
}