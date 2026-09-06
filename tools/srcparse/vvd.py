"""
Source .vvd (vertex data) reader, IDSV version 4. The on-disk vertex array is in the
"source" order studiomdl emitted; VTX strip indices address the fixed-up order.
"""
import struct

HEADER_SIZE = 64
VERTEX_SIZE = 48
FIXUP_SIZE = 12
MAX_NUM_LODS = 8


class VVD:
    def __init__(self, path):
        b = open(path, 'rb').read()
        self.b = b
        (self.id, self.version, self.checksum, self.numLODs) = struct.unpack_from('<4i', b, 0)
        assert b[0:4] == b'IDSV', 'not a VVD'
        self.numLODVertexes = list(struct.unpack_from('<8i', b, 16))
        (self.numFixups, self.fixupTableStart,
         self.vertexDataStart, self.tangentDataStart) = struct.unpack_from('<4i', b, 48)

        # ---- raw vertices, in on-disk ("source") order
        self.num_raw = self.numLODVertexes[0] if self.numFixups == 0 else None
        self.num_raw = (self.tangentDataStart - self.vertexDataStart) // VERTEX_SIZE
        self.raw = [self._vertex(self.vertexDataStart + i * VERTEX_SIZE)
                    for i in range(self.num_raw)]

        # ---- fixups
        self.fixups = [struct.unpack_from('<3i', b, self.fixupTableStart + i * FIXUP_SIZE)
                       for i in range(self.numFixups)]   # (lod, sourceVertexID, numVertexes)

        # ---- tangents: Vector4D per raw vertex, parallel array
        self.tangents = None
        if self.tangentDataStart:
            n = self.num_raw
            self.tangents = [struct.unpack_from('<4f', b, self.tangentDataStart + i * 16)
                             for i in range(n)]

    def _vertex(self, o):
        b = self.b
        w = struct.unpack_from('<3f', b, o)            # float weight[3]
        bones = struct.unpack_from('<3b', b, o + 12)   # char bone[3]
        nb = b[o + 15]                                 # byte numbones
        pos = struct.unpack_from('<3f', b, o + 16)
        nrm = struct.unpack_from('<3f', b, o + 28)
        uv = struct.unpack_from('<2f', b, o + 40)
        return {'weights': w, 'bones': bones, 'numbones': nb,
                'pos': pos, 'normal': nrm, 'uv': uv}

    def vertices_for_lod(self, lod):
        """Apply the fixup table: the vertex list for `lod`, in the order VTX strip
        indices address. A fixup's `lod` field means the run is present in LODs
        0..lod, so the test is >=, not ==.
        """
        if self.numFixups == 0:
            return self.raw[:self.numLODVertexes[lod]]
        out = []
        for (flod, src, n) in self.fixups:
            if flod >= lod:
                out.extend(self.raw[src:src + n])
        return out

    def info(self):
        return {
            'id': self.b[0:4].decode(), 'version': self.version,
            'checksum': self.checksum, 'numLODs': self.numLODs,
            'numLODVertexes': self.numLODVertexes[:self.numLODs],
            'numFixups': self.numFixups, 'fixupTableStart': self.fixupTableStart,
            'vertexDataStart': self.vertexDataStart,
            'tangentDataStart': self.tangentDataStart,
            'raw_vertex_count': self.num_raw,
        }
