package com.soldiermc.studio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Little-endian reader; Source string offsets are relative to their own struct. */
public final class Reader {

    private final ByteBuffer buf;

    public Reader(byte[] bytes) {
        this.buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int size() {
        return buf.capacity();
    }

    public int pos() {
        return buf.position();
    }

    public int i32At(int at) {
        return buf.getInt(at);
    }

    public short i16At(int at) {
        return buf.getShort(at);
    }

    public int u16At(int at) {
        return buf.getShort(at) & 0xFFFF;
    }

    public int u8At(int at) {
        return buf.get(at) & 0xFF;
    }

    public float f32At(int at) {
        return buf.getFloat(at);
    }

    public String stringAt(int structBase, int relativeOffset) {
        if (relativeOffset == 0) return "";
        int p = structBase + relativeOffset;
        if (p < 0 || p >= buf.capacity()) return "";
        int end = p;
        while (end < buf.capacity() && buf.get(end) != 0) end++;
        byte[] out = new byte[end - p];
        for (int i = 0; i < out.length; i++) out[i] = buf.get(p + i);
        return new String(out, StandardCharsets.US_ASCII);
    }

    public String fixedStringAt(int at, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = buf.get(at + i);
        int end = 0;
        while (end < len && out[end] != 0) end++;
        return new String(out, 0, end, StandardCharsets.US_ASCII);
    }
}
