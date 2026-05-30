package main

import (
	"bytes"
	"encoding/binary"
	"testing"

	"github.com/klauspost/compress/zstd"
)

func TestDecodeVersionsBlob(t *testing.T) {
	var raw bytes.Buffer
	raw.Write(versionBlobMagic)
	writeUvarint(&raw, 2)
	writeString(&raw, "1.0.1")
	writeNullableString(&raw, "jar")
	writeInt64(&raw, 3000)
	raw.WriteByte(1)
	writeString(&raw, "1.1.0-beta")
	writeNullableString(&raw, "")
	writeInt64(&raw, nullLong)
	raw.WriteByte(0)

	encoder, err := zstd.NewWriter(nil)
	if err != nil {
		t.Fatal(err)
	}
	blob := encoder.EncodeAll(raw.Bytes(), nil)
	encoder.Close()

	entries, err := decodeVersionsBlob(blob)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("len(entries) = %d, want 2", len(entries))
	}
	if entries[0].Version != "1.0.1" || entries[0].Packaging == nil || *entries[0].Packaging != "jar" {
		t.Fatalf("first entry mismatch: %+v", entries[0])
	}
	if entries[0].Timestamp == nil || *entries[0].Timestamp != 3000 || !entries[0].Stable {
		t.Fatalf("first timestamp/stable mismatch: %+v", entries[0])
	}
	if entries[1].Timestamp != nil || entries[1].Stable {
		t.Fatalf("second timestamp/stable mismatch: %+v", entries[1])
	}
}

func writeUvarint(buf *bytes.Buffer, value uint64) {
	var tmp [10]byte
	n := binary.PutUvarint(tmp[:], value)
	buf.Write(tmp[:n])
}

func writeString(buf *bytes.Buffer, value string) {
	writeUvarint(buf, uint64(len(value)))
	buf.WriteString(value)
}

func writeNullableString(buf *bytes.Buffer, value string) {
	if value == "" {
		writeUvarint(buf, 0)
		return
	}
	writeUvarint(buf, uint64(len(value)+1))
	buf.WriteString(value)
}

func writeInt64(buf *bytes.Buffer, value int64) {
	var tmp [8]byte
	binary.BigEndian.PutUint64(tmp[:], uint64(value))
	buf.Write(tmp[:])
}
