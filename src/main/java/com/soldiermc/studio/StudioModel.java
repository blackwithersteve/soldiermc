package com.soldiermc.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StudioModel {

    public final MdlFile mdl;
    public final VvdFile vvd;
    public final VtxFile vtx;
    public final List<VvdFile.Vertex> vertices;
    public final Map<Integer, int[]> trianglesByMaterial;
    public final int lod;
    public final int triangleCount;

    public StudioModel(MdlFile mdl, VvdFile vvd, VtxFile vtx, int lod) {
        this.mdl = mdl;
        this.vvd = vvd;
        this.vtx = vtx;
        this.lod = lod;
        this.vertices = vvd.verticesForLod(lod);

        // Mesh bases are a running sum of numLodVertexes; vertexIndex only fits LOD 0.
        Map<Integer, int[]> out = new LinkedHashMap<>();
        int running = 0;
        List<int[]> tris = new ArrayList<>();

        for (int bpi = 0; bpi < mdl.bodyParts.length; bpi++) {
            MdlFile.BodyPart bp = mdl.bodyParts[bpi];

            for (int mi = 0; mi < bp.models.length; mi++) {
                MdlFile.Model md = bp.models[mi];
                int[] meshBase = new int[md.meshes.length];
                for (int k = 0; k < md.meshes.length; k++) {
                    meshBase[k] = running;
                    running += md.meshes[k].numLodVertexes[lod];
                }

                // Body 0 draws only model 0; skipped models still advance the running sum.
                if (mi != 0) continue;
                if (bpi >= vtx.bodyParts.length) continue;
                VtxFile.Model vmd = vtx.bodyParts[bpi].models[mi];
                if (lod >= vmd.lods.length) continue;
                VtxFile.Lod vlod = vmd.lods[lod];

                for (int k = 0; k < md.meshes.length; k++) {
                    MdlFile.Mesh mesh = md.meshes[k];
                    if (mesh.numLodVertexes[lod] == 0) continue;
                    if (k >= vlod.meshes.length) continue;

                    final int base = meshBase[k];
                    final int material = mesh.material;
                    List<Integer> acc = new ArrayList<>();

                    for (VtxFile.StripGroup sg : vlod.meshes[k].stripGroups) {
                        for (VtxFile.Strip strip : sg.strips) {
                            vtx.triangles(sg, strip, (a, b, c) -> {
                                acc.add(base + vtx.origMeshVertId(sg, a));
                                acc.add(base + vtx.origMeshVertId(sg, b));
                                acc.add(base + vtx.origMeshVertId(sg, c));
                            });
                        }
                    }
                    if (acc.isEmpty()) continue;

                    int[] prev = out.get(material);
                    int prevLen = prev == null ? 0 : prev.length;
                    int[] merged = new int[prevLen + acc.size()];
                    if (prev != null) System.arraycopy(prev, 0, merged, 0, prevLen);
                    for (int i = 0; i < acc.size(); i++) merged[prevLen + i] = acc.get(i);
                    out.put(material, merged);
                }
            }
        }

        this.trianglesByMaterial = out;
        int t = 0;
        for (int[] idx : out.values()) t += idx.length / 3;
        this.triangleCount = t;
    }

    /** Returns null if the three files agree, otherwise the reason. */
    public static String validate(MdlFile mdl, VvdFile vvd, VtxFile vtx, int mdlFileSize) {
        if (mdl.checksum != vvd.checksum || mdl.checksum != vtx.checksum) {
            return "checksum mismatch: mdl=" + mdl.checksum
                 + " vvd=" + vvd.checksum + " vtx=" + vtx.checksum
                 + " (the three must agree; the ANIMATION mdl deliberately differs)";
        }
        if (mdl.length != mdlFileSize) {
            return "mdl length field " + mdl.length + " != file size " + mdlFileSize;
        }
        for (int n = 0; n < vvd.numLODs; n++) {
            int got = vvd.verticesForLod(n).size();
            if (got != vvd.numLODVertexes[n]) {
                return "fixup produced " + got + " vertices for LOD " + n
                     + ", expected " + vvd.numLODVertexes[n]
                     + " (the fixup table was skipped or filtered with == instead of >=)";
            }
        }
        return null;
    }
}
