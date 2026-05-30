package com.example.mavenindex.service;

import com.example.mavenindex.model.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public final class SourceIndexReader implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(SourceIndexReader.class);
    private static final int FIELD_COORDS = 'u';
    private static final int FIELD_TIMESTAMP = 'm';
    private static final int FIELD_PACKAGING = 'i';
    private static final int MAX_FIELD_LENGTH = 4 * 1024 * 1024;

    private final DataInputStream input;
    private final int formatVersion;
    private final long sourceTimestamp;
    private long recordNumber;

    public SourceIndexReader(Path indexGzPath) throws IOException {
        LOG.info("Reading packed index from {}", indexGzPath);
        this.input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(indexGzPath), 1024 * 1024), 1024 * 1024),
                1024 * 1024
        ));
        this.formatVersion = input.readUnsignedByte();
        this.sourceTimestamp = input.readLong();
        LOG.info("Packed index header: format={}, timestamp={}", formatVersion, sourceTimestamp);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public long sourceTimestamp() {
        return sourceTimestamp;
    }

    public RawArtifact next() throws IOException {
        while (true) {
            RawArtifact record = readRecord();
            if (record == null) {
                return null;
            }
            if (!record.groupId().isEmpty() && !record.artifactId().isEmpty()) {
                return record;
            }
        }
    }

    private RawArtifact readRecord() throws IOException {
        int fieldCount;
        try {
            fieldCount = input.readInt();
        } catch (EOFException eof) {
            return null;
        }
        recordNumber++;
        if (fieldCount < 0 || fieldCount > 256) {
            throw new IOException("Invalid field count " + fieldCount + " at record " + recordNumber);
        }

        Coord coord = null;
        Long timestamp = null;
        String packaging = null;

        for (int i = 0; i < fieldCount; i++) {
            input.readUnsignedByte(); // stored field metadata kind; not needed for this builder.
            int nameLength = input.readUnsignedShort();
            int fieldName = readFieldName(nameLength);
            int valueLength = input.readInt();
            if (valueLength < 0 || valueLength > MAX_FIELD_LENGTH) {
                throw new IOException("Invalid field length " + valueLength + " at record " + recordNumber);
            }

            switch (fieldName) {
                case FIELD_COORDS -> coord = readCoord(valueLength);
                case FIELD_TIMESTAMP -> timestamp = readTimestamp(valueLength);
                case FIELD_PACKAGING -> packaging = readFirstToken(valueLength);
                default -> input.skipNBytes(valueLength);
            }
        }

        if (coord == null) {
            return new RawArtifact("", "", "", packaging, timestamp);
        }
        return new RawArtifact(coord.groupId, coord.artifactId, coord.version, packaging, timestamp);
    }

    private int readFieldName(int nameLength) throws IOException {
        if (nameLength == 1) {
            return input.readUnsignedByte();
        }
        input.skipNBytes(nameLength);
        return -1;
    }

    private Coord readCoord(int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected EOF while reading coordinates");
        }

        int first = indexOf(bytes, 0, length, (byte) '|');
        if (first < 0) {
            return null;
        }
        int second = indexOf(bytes, first + 1, length, (byte) '|');
        if (second < 0) {
            return null;
        }
        int third = indexOf(bytes, second + 1, length, (byte) '|');
        int versionEnd = third < 0 ? length : third;

        String groupId = utf8(bytes, 0, first);
        String artifactId = utf8(bytes, first + 1, second);
        String version = utf8(bytes, second + 1, versionEnd);
        return new Coord(groupId, artifactId, version);
    }

    private Long readTimestamp(int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected EOF while reading timestamp");
        }
        if (length == 0) {
            return null;
        }
        long value = 0;
        boolean negative = bytes[0] == '-';
        int start = negative ? 1 : 0;
        for (int i = start; i < length; i++) {
            byte b = bytes[i];
            if (b < '0' || b > '9') {
                return null;
            }
            value = value * 10 + (b - '0');
        }
        return negative ? -value : value;
    }

    private String readFirstToken(int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected EOF while reading packaging");
        }
        if (length == 0) {
            return null;
        }
        int end = indexOf(bytes, 0, length, (byte) '|');
        if (end < 0) {
            end = length;
        }
        if (end == 0) {
            return null;
        }
        return utf8(bytes, 0, end);
    }

    private static int indexOf(byte[] bytes, int start, int end, byte target) {
        for (int i = start; i < end; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static String utf8(byte[] bytes, int startInclusive, int endExclusive) {
        return new String(bytes, startInclusive, endExclusive - startInclusive, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private record Coord(String groupId, String artifactId, String version) {
    }
}
