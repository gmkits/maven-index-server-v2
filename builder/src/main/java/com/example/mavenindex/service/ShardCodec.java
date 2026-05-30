package com.example.mavenindex.service;

import com.example.mavenindex.model.RawArtifact;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ShardCodec {
    private ShardCodec() {
    }

    static ShardWriter openWriter(Path path) throws IOException {
        return new ShardWriter(new BufferedOutputStream(Files.newOutputStream(path), 256 * 1024));
    }

    static ShardReader openReader(Path path) throws IOException {
        return new ShardReader(new BufferedInputStream(Files.newInputStream(path), 256 * 1024));
    }

    static final class ShardWriter implements Closeable {
        private final OutputStream out;

        private ShardWriter(OutputStream out) {
            this.out = out;
        }

        void write(RawArtifact artifact) throws IOException {
            BinaryIO.writeString(out, artifact.groupId());
            BinaryIO.writeString(out, artifact.artifactId());
            BinaryIO.writeString(out, artifact.version());
            BinaryIO.writeNullableString(out, artifact.packaging());
            BinaryIO.writeLong(out, artifact.timestamp() == null ? BinaryIO.NULL_LONG : artifact.timestamp());
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    static final class ShardReader implements Closeable {
        private final InputStream in;

        private ShardReader(InputStream in) {
            this.in = in;
        }

        RawArtifact next() throws IOException {
            Integer groupLength = BinaryIO.readVarIntOrEof(in);
            if (groupLength == null) {
                return null;
            }
            String groupId = BinaryIO.readStringWithLength(in, groupLength);
            String artifactId = BinaryIO.readString(in);
            String version = BinaryIO.readString(in);
            String packaging = BinaryIO.readNullableString(in);
            long timestamp = BinaryIO.readLong(in);
            return new RawArtifact(
                    groupId,
                    artifactId,
                    version,
                    packaging,
                    timestamp == BinaryIO.NULL_LONG ? null : timestamp
            );
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
