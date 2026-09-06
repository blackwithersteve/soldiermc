package com.soldiermc.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * Source {@code .vvd} — the vertex array. Verified against {@code soldier.vvd}, IDSV version 4. The
 * array on disk is in the order studiomdl emitted it; the order VTX strip indices address is the
 * fixed-up order, so the fixup table has to be applied whenever {@code numFixups > 0}.
 */
public final class VvdFile {

    private static final int VERTEX_SIZE = 48;
    private static final int FIXUP_SIZE = 12;

    public final int version;
    public final int checksum;
    public final int numLODs;
    public final int[] numLODVertexes = new int[8];
    public final int numFixups;

    /** Vertices in on-disk order — {@link #verticesForLod} applies the fixup remap. */
    private final Vertex[] raw;
    private final int[][] fixups;   // {lod, sourceVertexID, numVertexes}

    public VvdFile(byte[] bytes) {
        Reader r = new Reader(bytes);

        int id = r.i32At(0);
        if (id != 0x56534449) {   // 'I','D','S','V' read little-endian
            throw new IllegalArgumentException("not a VVD (bad magic)");
        }
        version = r.i32At(4);
        checksum = r.i32At(8);
        numLODs = r.i32At(12);
        for (int i = 0; i < 8; i++) numLODVertexes[i] = r.i32At(16 + i * 4);

        numFixups = r.i32At(48);
        int fixupTableStart = r.i32At(52);
        int vertexDataStart = r.i32At(56);
        int tangentDataStart = r.i32At(60);

        int rawCount = (tangentDataStart - vertexDataStart) / VERTEX_SIZE;
        raw = new Vertex[rawCount];
        for (int i = 0; i < rawCount; i++) {
            raw[i] = readVertex(r, vertexDataStart + i * VERTEX_SIZE);
        }

        fixups = new int[numFixups][3];
        for (int i = 0; i < numFixups; i++) {
            int o = fixupTableStart + i * FIXUP_SIZE;
            fixups[i][0] = r.i32At(o);
            fixups[i][1] = r.i32At(o + 4);
            fixups[i][2] = r.i32At(o + 8);
        }
    }

    private static Vertex readVertex(Reader r, int o) {
        Vertex v = new Vertex();
        v.weight0 = r.f32At(o);
        v.weight1 = r.f32At(o + 4);
        v.weight2 = r.f32At(o + 8);
        v.bone0 = (byte) r.u8At(o + 12);
        v.bone1 = (byte) r.u8At(o + 13);
        v.bone2 = (byte) r.u8At(o + 14);
        v.numBones = r.u8At(o + 15);
        v.px = r.f32At(o + 16);
        v.py = r.f32At(o + 20);
        v.pz = r.f32At(o + 24);
        v.nx = r.f32At(o + 28);
        v.ny = r.f32At(o + 32);
        v.nz = r.f32At(o + 36);
        v.u = r.f32At(o + 40);
        v.v = r.f32At(o + 44);
        return v;
    }

    /**
     * The vertex list in the order VTX strip indices address, for one LOD: walk the fixup table in
     * order and copy the run {@code [sourceVertexID, +numVertexes)} for every fixup whose
     * {@code lod >= lod}. A fixup's {@code lod} field means "present in LODs 0..lod", so the test is
     * {@code >=}, not {@code ==}.
     */
    public List<Vertex> verticesForLod(int lod) {
        List<Vertex> out = new ArrayList<>();
        if (numFixups == 0) {
            for (int i = 0; i < numLODVertexes[lod]; i++) out.add(raw[i]);
            return out;
        }
        for (int[] f : fixups) {
            if (f[0] >= lod) {
                for (int i = 0; i < f[2]; i++) out.add(raw[f[1] + i]);
            }
        }
        return out;
    }

    public int rawCount() {
        return raw.length;
    }

    /** {@code mstudiovertex_t} — position and normal in FILE space (Y-up), not game space. */
    public static final class Vertex {
        public float weight0, weight1, weight2;
        public byte bone0, bone1, bone2;
        public int numBones;
        public float px, py, pz;
        public float nx, ny, nz;
        public float u, v;
    }
}
