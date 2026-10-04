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

@UnstableApi
public class SmbDataSource extends BaseDataSource {

    private static final String TAG = "SmbDataSource";
    private static final int READ_CHUNK_SIZE = 1024 * 1024; // 1 МБ

    public static class SmbConfig {
        public final String host;
        public final String share;
        public final String path;
        public final String user;
        public final String password;
        public final String domain;

        public SmbConfig(String host, String share, String path,
                         String user, String password, String domain) {
            this.host = host;
            this.share = share;
            this.path = path;
            this.user = user;
            this.password = password;
            this.domain = domain;
        }
    }

    private SMBClient smbClient;
    private Connection connection;
    private Session session;
    private DiskShare diskShare;
    private File smbFile;

    private Uri uri;
    private long fileSize;
    private long position;
    private long bytesRemaining;
    private boolean opened;

    private byte[] internalBuffer;
    private int bufferFilled = 0;
    private int bufferPos = 0;

    public SmbDataSource() {
        super(true);
    }

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
        Log.e(TAG, "!!! open() called, pos=" + dataSpec.position + " len=" + dataSpec.length + " uri=" + dataSpec.uri);

        this.uri = dataSpec.uri;
        this.position = dataSpec.position;

        SmbConfig config = parseUri(uri, SmbCredentials.user, SmbCredentials.password, SmbCredentials.domain);
        Log.e(TAG, "!!! config: host=" + config.host + " share=" + config.share + " path=" + config.path);

        transferInitializing(dataSpec);

        try {
            smbClient = new SMBClient();
            connection = smbClient.connect(config.host);

            AuthenticationContext auth = new AuthenticationContext(
                    config.user,
                    config.password != null ? config.password.toCharArray() : new char[0],
                    config.domain != null ? config.domain : ""
            );

            session = connection.authenticate(auth);
            diskShare = (DiskShare) session.connectShare(config.share);

            if (!diskShare.fileExists(config.path)) {
                throw new IOException("SMB file not found: " + config.path);
            }

            SMB2CreateDisposition disposition = SMB2CreateDisposition.FILE_OPEN;
            EnumSet<AccessMask> accessMask = EnumSet.of(AccessMask.GENERIC_READ);
            EnumSet<SMB2ShareAccess> shareAccess = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ);
            EnumSet<SMB2CreateOptions> createOptions = EnumSet.noneOf(SMB2CreateOptions.class);

            smbFile = diskShare.openFile(
                    config.path,
                    accessMask,
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    shareAccess,
                    disposition,
                    createOptions
            );

            fileSize = smbFile.getFileInformation().getStandardInformation().getEndOfFile();

            // ВАЖНО: всегда возвращаем полный размер от текущей позиции,
            // игнорируем dataSpec.length — это только подсказка для одного запроса
            bytesRemaining = fileSize - position;

            // Сбрасываем внутренний буфер
            bufferFilled = 0;
            bufferPos = 0;

            opened = true;
            transferStarted(dataSpec);

            Log.e(TAG, "!!! open() SUCCESS, fileSize=" + fileSize + " position=" + position
                    + " bytesRemaining=" + bytesRemaining);
            return bytesRemaining;

        } catch (Exception e) {
            Log.e(TAG, "!!! EXCEPTION: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            close();
            throw new IOException("SMB open failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;

        // Если буфер пуст — подтягиваем новый блок с SMB
        if (bufferPos >= bufferFilled) {
            if (bytesRemaining <= 0) {
                return C.RESULT_END_OF_INPUT;
            }

            if (internalBuffer == null) {
                internalBuffer = new byte[READ_CHUNK_SIZE];
            }

            int want = (int) Math.min(READ_CHUNK_SIZE, bytesRemaining);
            int got = smbFile.read(internalBuffer, position, 0, want);

            if (got <= 0) {
                return C.RESULT_END_OF_INPUT;
            }

            bufferFilled = got;
            bufferPos = 0;
            position += got;
            bytesRemaining -= got;

            Log.e(TAG, "!!! read chunk, got=" + got + " newPosition=" + position + " remaining=" + bytesRemaining);
        }

        int available = bufferFilled - bufferPos;
        int toCopy = Math.min(available, length);
        System.arraycopy(internalBuffer, bufferPos, buffer, offset, toCopy);
        bufferPos += toCopy;
        bytesTransferred(toCopy);
        return toCopy;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        if (!opened) return;
        opened = false;

        try { if (smbFile != null) smbFile.close(); } catch (Exception ignored) {}
        try { if (diskShare != null) diskShare.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        try { if (smbClient != null) smbClient.close(); } catch (Exception ignored) {}

        smbFile = null;
        diskShare = null;
        session = null;
        connection = null;
        smbClient = null;
        internalBuffer = null;
        bufferFilled = 0;
        bufferPos = 0;

        transferEnded();
    }

    public static class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource();
        }
    }

    public static class SmbCredentials {
        public static String user = "guest";
        public static String password = "";
        public static String domain = "WORKGROUP";
    }
}