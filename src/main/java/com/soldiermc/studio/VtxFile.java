package com.soldiermc.studio;

/**
 * Source {@code .dx90.vtx} — the optimised index and strip data. Verified against
 * {@code soldier.dx90.vtx}, version 7. Every struct is packed ({@code #pragma pack(1)}), so
 * {@code MeshHeader_t} is 9 bytes, {@code StripGroupHeader_t} 25 and {@code StripHeader_t} 27, and
 * every offset inside the tree is relative to the struct that holds it.
 *
 * <p>The index chain:
 * <pre>
 *   strip index (ushort, relative to strip.indexOffset within the strip group's index array)
 *     -> stripgroup.verts[index]        (Vertex_t, 9 bytes)
 *     -> Vertex_t.origMeshVertID        (ushort)
 *     -> + the mesh's base in the fixed-up VVD array for this LOD
 * </pre>
 */
public final class VtxFile {

    public final int version;
    public final int checksum;
    public final int numLODs;
    public final BodyPart[] bodyParts;

    private final Reader r;

    public VtxFile(byte[] bytes) {
        r = new Reader(bytes);
        // FileHeader_t: version 0, vertCacheSize 4, maxBonesPerStrip 8 (u16), maxBonesPerTri 10
        // (u16), maxBonesPerVert 12, checksum 16, numLODs 20, materialReplacementListOffset 24,
        // numBodyParts 28, bodyPartOffset 32.
        version = r.i32At(0);
        checksum = r.i32At(16);
        numLODs = r.i32At(20);
        int numBodyParts = r.i32At(28);
        int bodyPartOffset = r.i32At(32);

        bodyParts = new BodyPart[numBodyParts];
        for (int i = 0; i < numBodyParts; i++) {
            bodyParts[i] = readBodyPart(bodyPartOffset + i * 8);
        }
    }

    private BodyPart readBodyPart(int o) {
        BodyPart bp = new BodyPart();
        int n = r.i32At(o);
        int off = r.i32At(o + 4);
        bp.models = new Model[n];
        for (int i = 0; i < n; i++) bp.models[i] = readModel(o + off + i * 8);
        return bp;
    }

    private Model readModel(int o) {
        Model m = new Model();
        int n = r.i32At(o);
        int off = r.i32At(o + 4);
        m.lods = new Lod[n];
        for (int i = 0; i < n; i++) m.lods[i] = readLod(o + off + i * 12);
        return m;
    }

    private Lod readLod(int o) {
        Lod l = new Lod();
        int n = r.i32At(o);
        int off = r.i32At(o + 4);
        l.meshes = new Mesh[n];
        for (int i = 0; i < n; i++) l.meshes[i] = readMesh(o + off + i * 9);
        return l;
    }

    private Mesh readMesh(int o) {
        Mesh m = new Mesh();
        int n = r.i32At(o);
        int off = r.i32At(o + 4);
        m.stripGroups = new StripGroup[n];
        for (int i = 0; i < n; i++) m.stripGroups[i] = readStripGroup(o + off + i * 25);
        return m;
    }

    private StripGroup readStripGroup(int o) {
        StripGroup sg = new StripGroup();
        sg.numVerts = r.i32At(o);
        sg.vertOffsetAbs = o + r.i32At(o + 4);
        sg.numIndices = r.i32At(o + 8);
        sg.indexOffsetAbs = o + r.i32At(o + 12);
        int ns = r.i32At(o + 16);
        int so = r.i32At(o + 20);
        sg.strips = new Strip[ns];
        for (int i = 0; i < ns; i++) sg.strips[i] = readStrip(o + so + i * 27);
        return sg;
    }

    private Strip readStrip(int o) {
        Strip s = new Strip();
        s.numIndices = r.i32At(o);
        s.indexOffset = r.i32At(o + 4);
        s.flags = r.u8At(o + 18);
        return s;
    }

    /** The i-th ushort in a strip group's index array. */
    public int index(StripGroup sg, int i) {
        return r.u16At(sg.indexOffsetAbs + 2 * i);
    }

    /** {@code Vertex_t.origMeshVertID} for the i-th strip-group vertex (9-byte stride). */
    public int origMeshVertId(StripGroup sg, int i) {
        return r.u16At(sg.vertOffsetAbs + 9 * i + 4);
    }

    /**
     * Emit a strip's triangles as strip-group vertex indices into {@code out}. Tristrips alternate
     * winding per triangle and contain degenerate stitch triangles; trilists are plain triples.
     */
    public void triangles(StripGroup sg, Strip strip, TriSink out) {
        int base = strip.indexOffset;
        if ((strip.flags & 0x02) != 0) {          // STRIP_IS_TRISTRIP
            for (int k = 0; k < strip.numIndices - 2; k++) {
                int a = index(sg, base + k);
                int b = index(sg, base + k + 1);
                int c = index(sg, base + k + 2);
                if (a == b || b == c || a == c) continue;   // degenerate stitch
                if ((k & 1) != 0) out.tri(a, c, b);
                else out.tri(a, b, c);
            }
        } else {                                   // STRIP_IS_TRILIST
            for (int k = 0; k + 2 < strip.numIndices; k += 3) {
                out.tri(index(sg, base + k), index(sg, base + k + 1), index(sg, base + k + 2));
            }
        }
    }

    public interface TriSink {
        void tri(int a, int b, int c);
    }

    public static final class BodyPart { public Model[] models; }
    public static final class Model { public Lod[] lods; }
    public static final class Lod { public Mesh[] meshes; }
    public static final class Mesh { public StripGroup[] stripGroups; }

    public static final class StripGroup {
        public int numVerts, vertOffsetAbs, numIndices, indexOffsetAbs;
        public Strip[] strips;
    }

    public static final class Strip {
        public int numIndices, indexOffset, flags;
    }
}
