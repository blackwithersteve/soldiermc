"""
Source .dx90.vtx (optimized index/strip data) reader, version 7. All struct sizes are
packed, no C alignment padding: MeshHeader_t 9, StripGroupHeader_t 25, StripHeader_t 27.

Index -> VVD vertex chain:
    strip index (unsigned short, relative to strip.indexOffset within the
                 STRIP GROUP's index array)
      -> stripgroup.verts[ that index ]            (Vertex_t, 9 bytes)
      -> Vertex_t.origMeshVertID                   (unsigned short)
      -> + mstudiomesh_t.vertexoffset              (from the MDL)
      -> + mstudiomodel_t.vertexindex / 48         (from the MDL)
      -> index into the FIXED-UP VVD vertex array for this LOD
"""
import struct

VTX_HEADER_SIZE = 36


class VTX:
    def __init__(self, path):
        b = open(path, 'rb').read()
        self.b = b
        (self.version, self.vertCacheSize) = struct.unpack_from('<ii', b, 0)
        (self.maxBonesPerStrip, self.maxBonesPerTri) = struct.unpack_from('<HH', b, 8)
        (self.maxBonesPerVert, self.checksum, self.numLODs,
         self.materialReplacementListOffset, self.numBodyParts,
         self.bodyPartOffset) = struct.unpack_from('<6i', b, 12)
        self.bodyparts = [self._bodypart(self.bodyPartOffset + i * 8)
                          for i in range(self.numBodyParts)]

    # BodyPartHeader_t { int numModels; int modelOffset; }  -- 8 bytes
    def _bodypart(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        return {'off': o, 'models': [self._model(o + off + i * 8) for i in range(n)]}

    # ModelHeader_t { int numLODs; int lodOffset; }  -- 8 bytes
    def _model(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        return {'off': o, 'lods': [self._lod(o + off + i * 12) for i in range(n)]}

    # ModelLODHeader_t { int numMeshes; int meshOffset; float switchPoint; } -- 12
    def _lod(self, o):
        n, off, sp = struct.unpack_from('<iif', self.b, o)
        return {'off': o, 'switchPoint': sp,
                'meshes': [self._mesh(o + off + i * 9) for i in range(n)]}

    # MeshHeader_t { int numStripGroups; int stripGroupHeaderOffset; uchar flags; } -- 9
    def _mesh(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        flags = self.b[o + 8]
        return {'off': o, 'flags': flags,
                'stripgroups': [self._stripgroup(o + off + i * 25) for i in range(n)]}

    # StripGroupHeader_t -- 25 bytes packed
    #   int numVerts; int vertOffset; int numIndices; int indexOffset;
    #   int numStrips; int stripOffset; uchar flags;
    def _stripgroup(self, o):
        b = self.b
        nv, vo, ni, io, ns, so = struct.unpack_from('<6i', b, o)
        flags = b[o + 24]
        sg = {'off': o, 'flags': flags,
              'vertOffsetAbs': o + vo, 'numVerts': nv,
              'indexOffsetAbs': o + io, 'numIndices': ni,
              'strips': [self._strip(o + so + i * 27) for i in range(ns)]}
        return sg

    # StripHeader_t -- 27 bytes packed
    #   int numIndices; int indexOffset; int numVerts; int vertOffset;
    #   short numBones; uchar flags; int numBoneStateChanges; int boneStateChangeOffset;
    def _strip(self, o):
        b = self.b
        ni, io, nv, vo = struct.unpack_from('<4i', b, o)
        nbones = struct.unpack_from('<h', b, o + 16)[0]
        flags = b[o + 18]
        nbsc, bsco = struct.unpack_from('<ii', b, o + 19)
        return {'off': o, 'numIndices': ni, 'indexOffset': io,
                'numVerts': nv, 'vertOffset': vo,
                'numBones': nbones, 'flags': flags,
                'numBoneStateChanges': nbsc}

    # ---- accessors
    def sg_index(self, sg, i):
        """i-th unsigned short in the strip group's index array."""
        return struct.unpack_from('<H', self.b, sg['indexOffsetAbs'] + 2 * i)[0]

    def sg_vertex(self, sg, i):
        """Vertex_t (9 bytes):
             uchar boneWeightIndex[3]; uchar numBones;
             ushort origMeshVertID; char boneID[3];"""
        o = sg['vertOffsetAbs'] + 9 * i
        b = self.b
        return {'boneWeightIndex': (b[o], b[o + 1], b[o + 2]),
                'numBones': b[o + 3],
                'origMeshVertID': struct.unpack_from('<H', b, o + 4)[0],
                'boneID': struct.unpack_from('<3b', b, o + 6)}

    def strip_triangles(self, sg, strip):
        """Yields (a,b,c) triples of STRIP-GROUP vertex indices."""
        base = strip['indexOffset']
        n = strip['numIndices']
        if strip['flags'] & 0x02:          # STRIP_IS_TRISTRIP
            for k in range(n - 2):
                a = self.sg_index(sg, base + k)
                b_ = self.sg_index(sg, base + k + 1)
                c = self.sg_index(sg, base + k + 2)
                if a == b_ or b_ == c or a == c:
                    continue               # degenerate stitch
                yield (a, c, b_) if (k & 1) else (a, b_, c)
        else:                              # STRIP_IS_TRILIST (flags & 0x01)
            for k in range(0, n, 3):
                yield (self.sg_index(sg, base + k),
                       self.sg_index(sg, base + k + 1),
                       self.sg_index(sg, base + k + 2))

    def info(self):
        return {'version': self.version, 'vertCacheSize': self.vertCacheSize,
                'maxBonesPerStrip': self.maxBonesPerStrip,
                'maxBonesPerTri': self.maxBonesPerTri,
                'maxBonesPerVert': self.maxBonesPerVert,
                'checksum': self.checksum, 'numLODs': self.numLODs,
                'numBodyParts': self.numBodyParts}


def lod_vertex_bases(mdlf, lod):
    """Base index into the fixed-up VVD array for that LOD, for every (bodypart, model)
    and every mesh inside it. mstudiomodel_t.vertexindex/48 and mstudiomesh_t.vertexoffset
    hold these values for LOD 0 only; above that the layout compacts and the base is the
    running sum of mstudiomesh_t.vertexdata.numLODVertexes[lod] in bodypart -> model ->
    mesh order.
    """
    run = 0
    model_base = {}
    mesh_base = {}
    for bpi, bp in enumerate(mdlf.bodyparts):
        for mi, md in enumerate(bp['models']):
            model_base[(bpi, mi)] = run
            for ki, mesh in enumerate(md['meshes']):
                mesh_base[(bpi, mi, ki)] = run
                run += mesh['numLODVertexes'][lod]
    return model_base, mesh_base, run


def build_lod(vtxf, mdlf, vvdf, lod=0, bodygroup_choice=None):
    """Assemble one LOD. Returns (vvd vertex list for that LOD,
    {material index: [(i0,i1,i2), ...]}, [(bodypart, model) chosen]).

    bodygroup_choice: dict bodypart_index -> model_index (default 0 = the first
    submodel of each bodypart, the stock Soldier).
    """
    verts = vvdf.vertices_for_lod(lod)
    _, mesh_base, total = lod_vertex_bases(mdlf, lod)
    out_faces = {}
    used = []
    for bpi, bp in enumerate(mdlf.bodyparts):
        want = 0 if bodygroup_choice is None else bodygroup_choice.get(bpi, 0)
        if want >= bp['nummodels']:
            continue
        vbp = vtxf.bodyparts[bpi]
        md = bp['models'][want]
        vmd = vbp['models'][want]
        if lod >= len(vmd['lods']):
            continue
        vlod = vmd['lods'][lod]
        for mi, mesh in enumerate(md['meshes']):
            if mesh['numLODVertexes'][lod] == 0:
                continue
            vmesh = vlod['meshes'][mi]
            base = mesh_base[(bpi, want, mi)]
            mat = mesh['material']
            for sg in vmesh['stripgroups']:
                for strip in sg['strips']:
                    for tri in vtxf.strip_triangles(sg, strip):
                        f = tuple(base + vtxf.sg_vertex(sg, si)['origMeshVertID']
                                  for si in tri)
                        out_faces.setdefault(mat, []).append(f)
        used.append((bpi, want))
    return verts, out_faces, used
