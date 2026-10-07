"""VTX v7 reader; structs are packed: mesh 9, strip group 25, strip 27 bytes."""
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

    def _bodypart(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        return {'off': o, 'models': [self._model(o + off + i * 8) for i in range(n)]}

    def _model(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        return {'off': o, 'lods': [self._lod(o + off + i * 12) for i in range(n)]}

    def _lod(self, o):
        n, off, sp = struct.unpack_from('<iif', self.b, o)
        return {'off': o, 'switchPoint': sp,
                'meshes': [self._mesh(o + off + i * 9) for i in range(n)]}

    def _mesh(self, o):
        n, off = struct.unpack_from('<ii', self.b, o)
        flags = self.b[o + 8]
        return {'off': o, 'flags': flags,
                'stripgroups': [self._stripgroup(o + off + i * 25) for i in range(n)]}

    def _stripgroup(self, o):
        b = self.b
        nv, vo, ni, io, ns, so = struct.unpack_from('<6i', b, o)
        flags = b[o + 24]
        sg = {'off': o, 'flags': flags,
              'vertOffsetAbs': o + vo, 'numVerts': nv,
              'indexOffsetAbs': o + io, 'numIndices': ni,
              'strips': [self._strip(o + so + i * 27) for i in range(ns)]}
        return sg

    # StripHeader_t: 4 ints, short numBones, uchar flags, 2 ints; 27 bytes packed.
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

    def sg_index(self, sg, i):
        return struct.unpack_from('<H', self.b, sg['indexOffsetAbs'] + 2 * i)[0]

    def sg_vertex(self, sg, i):
        o = sg['vertOffsetAbs'] + 9 * i
        b = self.b
        return {'boneWeightIndex': (b[o], b[o + 1], b[o + 2]),
                'numBones': b[o + 3],
                'origMeshVertID': struct.unpack_from('<H', b, o + 4)[0],
                'boneID': struct.unpack_from('<3b', b, o + 6)}

    def strip_triangles(self, sg, strip):
        """Yields triangles as strip group vertex indices."""
        base = strip['indexOffset']
        n = strip['numIndices']
        if strip['flags'] & 0x02:          # STRIP_IS_TRISTRIP
            for k in range(n - 2):
                a = self.sg_index(sg, base + k)
                b_ = self.sg_index(sg, base + k + 1)
                c = self.sg_index(sg, base + k + 2)
                if a == b_ or b_ == c or a == c:
                    continue
                yield (a, c, b_) if (k & 1) else (a, b_, c)
        else:                              # STRIP_IS_TRILIST
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
    """Per-mesh VVD bases; above LOD 0 these are running sums, not vertexoffset."""
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
