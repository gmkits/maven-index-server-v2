package com.example.mavenindex.service;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class BinaryIO {
    static final long NULL_LONG = Long.MIN_VALUE;

    private BinaryIO() {
    }

    static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    static String readString(InputStream in) throws IOException {
        int length = readVarInt(in);
        return readStringWithLength(in, length);
    }

    static String readStringWithLength(InputStream in, int length) throws IOException {
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected EOF while reading string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeNullableString(OutputStream out, String value) throws IOException {
        if (value == null) {
            writeVarInt(out, 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length + 1);
        out.write(bytes);
    }

    static String readNullableString(InputStream in) throws IOException {
        int encodedLength = readVarInt(in);
        if (encodedLength == 0) {
            return null;
        }
        return readStringWithLength(in, encodedLength - 1);
    }

    static void writeLong(OutputStream out, long value) throws IOException {
        out.write((byte) (value >>> 56));
        out.write((byte) (value >>> 48));
        out.write((byte) (value >>> 40));
        out.write((byte) (value >>> 32));
        out.write((byte) (value >>> 24));
        out.write((byte) (value >>> 16));
        out.write((byte) (value >>> 8));
        out.write((byte) value);
    }

    static long readLong(InputStream in) throws IOException {
        long b0 = readUnsignedByte(in);
        long b1 = readUnsignedByte(in);
        long b2 = readUnsignedByte(in);
        long b3 = readUnsignedByte(in);
        long b4 = readUnsignedByte(in);
        long b5 = readUnsignedByte(in);
        long b6 = readUnsignedByte(in);
        long b7 = readUnsignedByte(in);
        return (b0 << 56)
                | (b1 << 48)
                | (b2 << 40)
                | (b3 << 32)
                | (b4 << 24)
                | (b5 << 16)
                | (b6 << 8)
                | b7;
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt cannot be negative: " + value);
        }
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int readVarInt(InputStream in) throws IOException {
        Integer value = readVarIntOrEof(in);
        if (value == null) {
            throw new EOFException("Unexpected EOF while reading varint");
        }
        return value;
    }

    static Integer readVarIntOrEof(InputStream in) throws IOException {
        int shift = 0;
        int result = 0;
        while (shift < 35) {
            int b = in.read();
            if (b < 0) {
                if (shift == 0) {
                    return null;
                }
                throw new EOFException("Unexpected EOF while reading varint");
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("VarInt too long");
    }

    private static int readUnsignedByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected EOF while reading byte");
        }
        return b;
    }
}
