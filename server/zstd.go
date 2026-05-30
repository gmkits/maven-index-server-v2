package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"sync"

	"github.com/klauspost/compress/zstd"
)

const versionBlobFormat = "miv2-zstd-bin"
const nullLong int64 = -1 << 63

var versionBlobMagic = []byte{'M', 'I', 'V', '2'}

var zstdDecoderPool = sync.Pool{
	New: func() any {
		decoder, err := zstd.NewReader(nil)
		if err != nil {
			panic(err)
		}
		return decoder
	},
}

type versionEntry struct {
	Version   string  `json:"v"`
	Packaging *string `json:"p"`
	Timestamp *int64  `json:"t"`
	Stable    bool    `json:"stable"`
}

func decompressZstd(data []byte) ([]byte, error) {
	decoder := zstdDecoderPool.Get().(*zstd.Decoder)
	defer zstdDecoderPool.Put(decoder)
	return decoder.DecodeAll(data, nil)
}

func decodeVersionsBlob(blob []byte) ([]versionEntry, error) {
	decompressed, err := decompressZstd(blob)
	if err != nil {
		return nil, fmt.Errorf("decompress: %w", err)
	}
	reader := bytes.NewReader(decompressed)
	magic := make([]byte, len(versionBlobMagic))
	if _, err := io.ReadFull(reader, magic); err != nil {
		return nil, fmt.Errorf("read magic: %w", err)
	}
	if !bytes.Equal(magic, versionBlobMagic) {
		return nil, fmt.Errorf("invalid version blob magic")
	}

	count, err := binary.ReadUvarint(reader)
	if err != nil {
		return nil, fmt.Errorf("read count: %w", err)
	}
	if count > 1_000_000 {
		return nil, fmt.Errorf("version count too large: %d", count)
	}

	entries := make([]versionEntry, 0, int(count))
	for i := uint64(0); i < count; i++ {
		version, err := readBlobString(reader)
		if err != nil {
			return nil, fmt.Errorf("read version %d: %w", i, err)
		}
		packaging, err := readBlobNullableString(reader)
		if err != nil {
			return nil, fmt.Errorf("read packaging %d: %w", i, err)
		}
		timestampValue, err := readBlobInt64(reader)
		if err != nil {
			return nil, fmt.Errorf("read timestamp %d: %w", i, err)
		}
		stableFlag, err := reader.ReadByte()
		if err != nil {
			return nil, fmt.Errorf("read stable %d: %w", i, err)
		}

		var timestamp *int64
		if timestampValue != nullLong {
			ts := timestampValue
			timestamp = &ts
		}
		entries = append(entries, versionEntry{
			Version:   version,
			Packaging: packaging,
			Timestamp: timestamp,
			Stable:    stableFlag != 0,
		})
	}
	return entries, nil
}

func readBlobString(reader *bytes.Reader) (string, error) {
	length, err := binary.ReadUvarint(reader)
	if err != nil {
		return "", err
	}
	if length > 16*1024*1024 {
		return "", fmt.Errorf("string too large: %d", length)
	}
	buf := make([]byte, int(length))
	if _, err := io.ReadFull(reader, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func readBlobNullableString(reader *bytes.Reader) (*string, error) {
	encodedLength, err := binary.ReadUvarint(reader)
	if err != nil {
		return nil, err
	}
	if encodedLength == 0 {
		return nil, nil
	}
	length := encodedLength - 1
	if length > 16*1024*1024 {
		return nil, fmt.Errorf("string too large: %d", length)
	}
	buf := make([]byte, int(length))
	if _, err := io.ReadFull(reader, buf); err != nil {
		return nil, err
	}
	value := string(buf)
	return &value, nil
}

func readBlobInt64(reader *bytes.Reader) (int64, error) {
	var buf [8]byte
	if _, err := io.ReadFull(reader, buf[:]); err != nil {
		return 0, err
	}
	return int64(binary.BigEndian.Uint64(buf[:])), nil
}
