package chat.buku.skate3;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.FileAlreadyExistsException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Streaming, safety-checked extraction of .zip, .rar, .7z, .tar and .tar.gz archives.
 *
 * <p>Every entry path is sanitized (no absolute paths, no ".." segments, no drive letters,
 * no NUL bytes), symlinks and hardlinks are skipped, file counts and byte totals are capped,
 * and duplicate entries are rejected, so a hostile archive cannot escape the target directory
 * or exhaust storage. Encrypted entries are rejected with a clear message.
 *
 * <p>Format detection uses magic bytes, never the file extension. The caller supplies a fresh
 * {@link FileChannel}; this class closes it before returning or throwing.
 */
final class ArchiveExtractor {
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_NAME_LENGTH = 512;
    /** Declared-dictionary budget for RAR/7z decoding, sized for 4 GB devices. */
    private static final long MAX_DICTIONARY_BYTES = 256L * 1024 * 1024;
    private static final int MAX_DICTIONARY_KB = 256 * 1024;
    private static final SevenZFileOptions SEVENZ_OPTIONS =
        SevenZFileOptions.builder().withMaxMemoryLimitInKb(MAX_DICTIONARY_KB).build();

    enum Format { ZIP, RAR, SEVEN_Z, TAR, TAR_GZIP, UNKNOWN }

    interface Progress {
        void onProgress(long bytesCopied, String currentFile);
    }

    static final class Limits {
        final int maxEntries;
        final long maxFileBytes;
        final long maxTotalBytes;

        Limits(int maxEntries, long maxFileBytes, long maxTotalBytes) {
            this.maxEntries = maxEntries;
            this.maxFileBytes = maxFileBytes;
            this.maxTotalBytes = maxTotalBytes;
        }

        /** Game dumps are the extracted retail tree; they can be several GB. */
        static Limits forGame() {
            return new Limits(50_000, 8L * 1024 * 1024 * 1024, 48L * 1024 * 1024 * 1024);
        }

        /** Character mods: base.obj + texture_diffuse.png plus README-ish files. */
        static Limits forMod() {
            return new Limits(512, 64L * 1024 * 1024, 128L * 1024 * 1024);
        }

        /** GPU driver packages (ADPKG): matches the previous ZIP importer caps. */
        static Limits forDriver() {
            return new Limits(96, 192L * 1024 * 1024, 384L * 1024 * 1024);
        }
    }

    static final class Result {
        final long totalBytes;
        final int fileCount;

        Result(long totalBytes, int fileCount) {
            this.totalBytes = totalBytes;
            this.fileCount = fileCount;
        }
    }

    private final Path targetDir;
    private final Limits limits;
    private final Progress progress;
    private long totalBytes;
    private long entries;
    private int fileCount;

    private ArchiveExtractor(Path targetDir, Limits limits, Progress progress) {
        this.targetDir = targetDir.toAbsolutePath().normalize();
        this.limits = limits;
        this.progress = progress;
    }

    /** Detect the format from the first bytes of the channel. Resets the position to 0. */
    static Format detect(FileChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(512);
        channel.position(0);
        while (header.hasRemaining()) {
            if (channel.read(header) <= 0) break;
        }
        channel.position(0);
        header.flip();
        byte[] magic = new byte[header.remaining()];
        header.get(magic);
        return detect(magic);
    }

    private static Format detect(byte[] magic) {
        if (magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' &&
                (magic[2] == 3 || magic[2] == 5 || magic[2] == 7) &&
                (magic[3] == 4 || magic[3] == 6 || magic[3] == 8)) {
            return Format.ZIP;
        }
        if (magic.length >= 6 && magic[0] == 'R' && magic[1] == 'a' && magic[2] == 'r' &&
                magic[3] == '!' && (magic[4] & 0xff) == 0x1a && (magic[5] & 0xff) == 0x07) {
            return Format.RAR;
        }
        if (magic.length >= 6 && (magic[0] & 0xff) == 0x37 && (magic[1] & 0xff) == 0x7a &&
                (magic[2] & 0xff) == 0xbc && (magic[3] & 0xff) == 0xaf &&
                (magic[4] & 0xff) == 0x27 && (magic[5] & 0x1c) == 0x1c) {
            return Format.SEVEN_Z;
        }
        if (magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return Format.TAR_GZIP;
        }
        if (magic.length >= 262 && magic[257] == 'u' && magic[258] == 's' &&
                magic[259] == 't' && magic[260] == 'a' && magic[261] == 'r') {
            return Format.TAR;
        }
        return Format.UNKNOWN;
    }

    /**
     * Best-effort sum of the declared uncompressed entry sizes, for free-space preflight.
     * Returns -1 when the format cannot be scanned cheaply or the archive is unreadable;
     * callers should then rely on the hard caps instead. The supplied channel is always
     * closed before this method returns or throws; open a fresh one for extraction.
     */
    static long declaredTotalSize(FileChannel channel) {
        try {
            Format format = detect(channel);
            long sum = 0;
            if (format == Format.RAR) {
                try (InputStream input = new BufferedInputStream(
                        Channels.newInputStream(channel), BUFFER_SIZE);
                     Archive archive = openRar(input)) {
                    for (FileHeader header : archive.getFileHeaders()) {
                        sum += Math.max(0, header.getFullUnpackSize());
                        if (sum < 0) return -1;
                    }
                    return sum;
                }
            }
            if (format == Format.SEVEN_Z) {
                @SuppressWarnings("deprecation")
                SevenZFile sevenz = new SevenZFile(channel, SEVENZ_OPTIONS);
                try (SevenZFile file = sevenz) {
                    SevenZArchiveEntry entry;
                    while ((entry = file.getNextEntry()) != null) {
                        sum += Math.max(0, entry.getSize());
                        if (sum < 0) return -1;
                    }
                    return sum;
                }
            }
            if (format == Format.TAR) {
                try (TarArchiveInputStream tar = new TarArchiveInputStream(
                        Channels.newInputStream(channel))) {
                    TarArchiveEntry entry;
                    while ((entry = tar.getNextEntry()) != null) {
                        sum += Math.max(0, entry.getSize());
                        if (sum < 0) return -1;
                    }
                    return sum;
                }
            }
            return -1; // ZIP (central directory is not on the stream) and TAR_GZIP
        } catch (Exception ignored) {
            return -1;
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing during preflight must not mask the result.
            }
        }
    }

    /**
     * Extract the archive into {@code targetDir}. The target directory must be empty or
     * non-existent. The supplied channel is closed before this method returns or throws.
     */
    static Result extract(FileChannel channel, String displayName, Path targetDir,
                          Limits limits, Progress progress) throws IOException {
        ArchiveExtractor extractor = new ArchiveExtractor(targetDir, limits, progress);
        try {
            Format format = detect(channel);
            Files.createDirectories(extractor.targetDir);
            if (format == Format.ZIP) extractor.extractZip(channel);
            else if (format == Format.RAR) extractor.extractRar(channel);
            else if (format == Format.SEVEN_Z) extractor.extractSevenZ(channel);
            else if (format == Format.TAR) extractor.extractTar(
                    new Channels.newInputStream(channel));
            else if (format == Format.TAR_GZIP) extractor.extractTar(
                    new GZIPInputStream(new BufferedInputStream(
                            Channels.newInputStream(channel), BUFFER_SIZE)));
            else {
                throw new IOException("Unsupported archive format. Supported: " +
                    ".zip, .rar, .7z, .tar and .tar.gz.");
            }
            if (extractor.fileCount == 0) {
                throw new IOException("The archive does not contain any files.");
            }
            return new Result(extractor.totalBytes, extractor.fileCount);
        } finally {
            channel.close();
        }
    }

    private static Archive openRar(InputStream input) throws IOException {
        try {
            return new Archive(input, ArchiveOptions.builder()
                    .maxDictionarySize(MAX_DICTIONARY_BYTES)
                    .build());
        } catch (com.github.junrar.exception.RarException exception) {
            throw new IOException("The .rar archive could not be read: " +
                cleanMessage(exception), exception);
        }
    }

    private void extractZip(FileChannel channel) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                countEntry(entry.getName());
                Path target = resolveSafe(entry.getName());
                if (entry.isDirectory()) {
                    if (target != null) Files.createDirectories(target);
                    continue;
                }
                if (target == null) continue;
                copyStream(zip, target, entry.getName());
                zip.closeEntry();
            }
        }
    }

    private void extractRar(FileChannel channel) throws IOException {
        try (InputStream input = new BufferedInputStream(
                Channels.newInputStream(channel), BUFFER_SIZE);
             Archive archive = openRar(input)) {
            if (archive.isPasswordProtected()) {
                throw new IOException("Password-protected archives are not supported.");
            }
            for (FileHeader header : archive.getFileHeaders()) {
                String name = header.getFileName();
                countEntry(name);
                if (header.isDirectory()) continue;
                if (header.isEncrypted()) {
                    throw new IOException("Password-protected archives are not supported.");
                }
                Path target = resolveSafe(name);
                if (target == null) continue;
                try (OutputStream output = newOutput(target, name);
                     BoundedOutputStream bounded = new BoundedOutputStream(output)) {
                    archive.extractFile(header, bounded);
                    finishFile(name);
                }
            }
        } catch (com.github.junrar.exception.RarException exception) {
            throw new IOException("The .rar archive could not be read: " +
                cleanMessage(exception), exception);
        }
    }

    private void extractSevenZ(FileChannel channel) throws IOException {
        @SuppressWarnings("deprecation")
        SevenZFile sevenz = new SevenZFile(channel, SEVENZ_OPTIONS);
        try (SevenZFile file = sevenz) {
            SevenZArchiveEntry entry;
            while ((entry = file.getNextEntry()) != null) {
                String name = entry.getName();
                countEntry(name);
                if (entry.isDirectory() || entry.isAntiItem()) continue;
                if (name == null) continue;
                Path target = resolveSafe(name);
                if (target == null) continue;
                copyStream(new SevenZInputStream(file), target, name);
            }
        }
    }

    private void extractTar(InputStream input) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                countEntry(name);
                if (entry.isDirectory()) continue;
                if (entry.isSymbolicLink() || entry.isLink()) continue;
                Path target = resolveSafe(name);
                if (target == null) continue;
                copyStream(tar, target, name);
            }
        }
    }

    private void countEntry(String name) throws IOException {
        if (++entries > limits.maxEntries) {
            throw new IOException("The archive contains too many files.");
        }
    }

    private Path resolveSafe(String rawName) throws IOException {
        if (rawName == null) throw new IOException("The archive contains an entry with no name.");
        String name = rawName.replace('\\', '/');
        if (name.indexOf('\0') >= 0 || name.length() > MAX_NAME_LENGTH) {
            throw new IOException("The archive contains an unsafe path.");
        }
        while (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || name.equals(".")) return null;
        if (name.startsWith("/") ||
                (name.length() > 1 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':')) {
            throw new IOException("The archive contains an unsafe path.");
        }
        Path resolved = targetDir;
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                throw new IOException("The archive contains an unsafe path.");
            }
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IOException("The archive contains an unsafe path.");
        }
        return resolved;
    }

    private OutputStream newOutput(Path target, String displayName) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            throw new IOException("The archive contains duplicate files: " + displayName,
                exception);
        }
    }

    private void copyStream(InputStream input, Path target, String displayName)
            throws IOException {
        try (OutputStream output = newOutput(target, displayName);
             BoundedOutputStream bounded = new BoundedOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (;;) {
                int read = input.read(buffer);
                if (read < 0) break;
                bounded.write(buffer, 0, read);
            }
            finishFile(displayName);
        }
    }

    private void finishFile(String displayName) {
        fileCount++;
        if (progress != null) progress.onProgress(totalBytes, displayName);
    }

    private final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private BoundedOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        private final byte[] single = new byte[1];

        @Override
        public void write(int value) throws IOException {
            single[0] = (byte) value;
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            count += length;
            totalBytes += length;
            if (count > limits.maxFileBytes || totalBytes > limits.maxTotalBytes) {
                throw new IOException("The archive expands past the size limit for " +
                    "this kind of file.");
            }
            delegate.write(buffer, offset, length);
        }
    }

    private static String cleanMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName()
            : message;
    }

    /** Minimal InputStream adapter so SevenZFile can feed {@link #copyStream}. */
    private static final class SevenZInputStream extends InputStream {
        private final SevenZFile file;

        private SevenZInputStream(SevenZFile file) {
            this.file = file;
        }

        @Override
        public int read() throws IOException {
            return file.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return file.read(buffer, offset, length);
        }
    }
}
