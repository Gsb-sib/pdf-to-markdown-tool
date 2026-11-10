package com.example.pdfmarkdown;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 直接读取 ONNX 的 UTF-8 元数据，避免 JNI 修改版 UTF-8 截断补充平面字符。 */
final class OnnxModelDictionary {
    private OnnxModelDictionary() { }

    static String read(Path model) throws IOException {
        try (InputStream input = Files.newInputStream(model)) {
            long tag;
            while ((tag = varint(input, true)) != -1) {
                if (tag == (14 << 3 | 2)) {
                    long length = varint(input, false);
                    if (length > 16 * 1024 * 1024 || length < 0) throw new IOException("模型元数据长度异常");
                    byte[] entry = input.readNBytes((int) length);
                    if (entry.length != length) throw new EOFException("模型元数据不完整");
                    String value = readEntry(entry);
                    if (value != null) return value;
                } else skip(input, tag);
            }
        }
        return null;
    }

    private static String readEntry(byte[] entry) throws IOException {
        String key = null, value = null;
        try (InputStream input = new ByteArrayInputStream(entry)) {
            long tag;
            while ((tag = varint(input, true)) != -1) {
                if (tag == 10 || tag == 18) {
                    long length = varint(input, false);
                    if (length < 0 || length > entry.length) throw new IOException("模型字典长度异常");
                    byte[] bytes = input.readNBytes((int) length);
                    if (bytes.length != length) throw new EOFException("模型字典不完整");
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (tag == 10) key = text; else value = text;
                } else skip(input, tag);
            }
        }
        return "character".equals(key) ? value : null;
    }

    private static void skip(InputStream input, long tag) throws IOException {
        switch ((int) (tag & 7)) {
            case 0 -> varint(input, false);
            case 1 -> input.skipNBytes(8);
            case 2 -> {
                long length = varint(input, false);
                if (length < 0) throw new IOException("模型字段长度异常");
                input.skipNBytes(length);
            }
            case 5 -> input.skipNBytes(4);
            default -> throw new IOException("模型包含不支持的 protobuf 字段");
        }
    }

    private static long varint(InputStream input, boolean allowEof) throws IOException {
        long result = 0;
        for (int offset = 0; offset < 64; offset += 7) {
            int value = input.read();
            if (value == -1) {
                if (offset == 0 && allowEof) return -1;
                throw new EOFException("模型 protobuf 不完整");
            }
            result |= (long) (value & 127) << offset;
            if ((value & 128) == 0) return result;
        }
        throw new IOException("模型 protobuf 整数溢出");
    }
}
