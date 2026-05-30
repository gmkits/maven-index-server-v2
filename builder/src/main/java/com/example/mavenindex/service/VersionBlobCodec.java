package com.example.mavenindex.service;

import com.example.mavenindex.model.VersionEntry;
import com.github.luben.zstd.Zstd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public final class VersionBlobCodec {
    public static final String FORMAT = "miv2-zstd-bin";
    private static final byte[] MAGIC = {'M', 'I', 'V', '2'};

    private VersionBlobCodec() {
    }

    public static byte[] encode(List<VersionEntry> entries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.max(64, entries.size() * 32));
        raw.writeBytes(MAGIC);
        BinaryIO.writeVarInt(raw, entries.size());
        for (VersionEntry entry : entries) {
            BinaryIO.writeString(raw, entry.version());
            BinaryIO.writeNullableString(raw, entry.packaging());
            BinaryIO.writeLong(raw, entry.timestamp() == null ? BinaryIO.NULL_LONG : entry.timestamp());
            raw.write(entry.stable() ? 1 : 0);
        }
        return Zstd.compress(raw.toByteArray(), 1);
    }

    static List<VersionEntry> decodeUncompressed(byte[] raw) throws IOException {
        if (raw.length < MAGIC.length) {
            throw new IOException("Version blob too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new IOException("Invalid version blob magic");
            }
        }
        BlobInput in = new BlobInput(raw, MAGIC.length);
        int count = BinaryIO.readVarInt(in);
        java.util.ArrayList<VersionEntry> entries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String version = BinaryIO.readString(in);
            String packaging = BinaryIO.readNullableString(in);
            long timestamp = BinaryIO.readLong(in);
            int stable = in.read();
            if (stable < 0) {
                throw new IOException("Unexpected EOF while reading stable flag");
            }
            entries.add(new VersionEntry(
                    version,
                    packaging,
                    timestamp == BinaryIO.NULL_LONG ? null : timestamp,
                    stable != 0
            ));
        }
        return entries;
    }

    private static final class BlobInput extends java.io.InputStream {
        private final byte[] data;
        private int pos;

        private BlobInput(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        @Override
        public int read() {
            if (pos >= data.length) {
                return -1;
            }
            return data[pos++] & 0xFF;
        }

        @Override
        public byte[] readNBytes(int len) {
            int n = Math.min(len, data.length - pos);
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }
    }
}
