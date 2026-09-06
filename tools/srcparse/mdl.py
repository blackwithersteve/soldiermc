"""
Source engine MDL (studiomdl) reader, IDST version 48.
Source space is right-handed Z-up, X forward, Y left; units are Hammer units.
"""
import struct, io, math, sys

# ---- primitives

def u8(b, o):   return b[o]
def i32(b, o):  return struct.unpack_from('<i', b, o)[0]
def u32(b, o):  return struct.unpack_from('<I', b, o)[0]
def i16(b, o):  return struct.unpack_from('<h', b, o)[0]
def u16(b, o):  return struct.unpack_from('<H', b, o)[0]
def f32(b, o):  return struct.unpack_from('<f', b, o)[0]
def vec3(b, o): return struct.unpack_from('<3f', b, o)
def vec4(b, o): return struct.unpack_from('<4f', b, o)

def cstr(b, o):
    """NUL-terminated string at absolute offset o. o==0 means 'no name'."""
    if o <= 0 or o >= len(b):
        return ''
    e = b.index(b'\x00', o)
    return b[o:e].decode('ascii', 'replace')

def matrix3x4(b, o):
    """Source matrix3x4_t: 3 rows of 4 floats, row-major; column 3 is the translation."""
    return [struct.unpack_from('<4f', b, o + 16 * r) for r in range(3)]


# ---- studiohdr_t
# sizeof(studiohdr_t) == 408 == 0x198 for version 48, same as v49.
STUDIOHDR_FIELDS = [
    # (name, offset, kind)
    ('id',                       0x000, 'fourcc'),
    ('version',                  0x004, 'i'),
    ('checksum',                 0x008, 'i'),
    ('name',                     0x00C, 'char[64]'),
    ('length',                   0x04C, 'i'),
    ('eyeposition',              0x050, 'v3'),
    ('illumposition',            0x05C, 'v3'),
    ('hull_min',                 0x068, 'v3'),
    ('hull_max',                 0x074, 'v3'),
    ('view_bbmin',               0x080, 'v3'),
    ('view_bbmax',               0x08C, 'v3'),
    ('flags',                    0x098, 'i'),
    ('numbones',                 0x09C, 'i'),
    ('boneindex',                0x0A0, 'i'),
    ('numbonecontrollers',       0x0A4, 'i'),
    ('bonecontrollerindex',      0x0A8, 'i'),
    ('numhitboxsets',            0x0AC, 'i'),
    ('hitboxsetindex',           0x0B0, 'i'),
    ('numlocalanim',             0x0B4, 'i'),
    ('localanimindex',           0x0B8, 'i'),
    ('numlocalseq',              0x0BC, 'i'),
    ('localseqindex',            0x0C0, 'i'),
    ('activitylistversion',      0x0C4, 'i'),
    ('eventsindexed',            0x0C8, 'i'),
    ('numtextures',              0x0CC, 'i'),
    ('textureindex',             0x0D0, 'i'),
    ('numcdtextures',            0x0D4, 'i'),
    ('cdtextureindex',           0x0D8, 'i'),
    ('numskinref',               0x0DC, 'i'),
    ('numskinfamilies',          0x0E0, 'i'),
    ('skinindex',                0x0E4, 'i'),
    ('numbodyparts',             0x0E8, 'i'),
    ('bodypartindex',            0x0EC, 'i'),
    ('numlocalattachments',      0x0F0, 'i'),
    ('localattachmentindex',     0x0F4, 'i'),
    ('numlocalnodes',            0x0F8, 'i'),
    ('localnodeindex',           0x0FC, 'i'),
    ('localnodenameindex',       0x100, 'i'),
    ('numflexdesc',              0x104, 'i'),
    ('flexdescindex',            0x108, 'i'),
    ('numflexcontrollers',       0x10C, 'i'),
    ('flexcontrollerindex',      0x110, 'i'),
    ('numflexrules',             0x114, 'i'),
    ('flexruleindex',            0x118, 'i'),
    ('numikchains',              0x11C, 'i'),
    ('ikchainindex',             0x120, 'i'),
    ('nummouths',                0x124, 'i'),
    ('mouthindex',               0x128, 'i'),
    ('numlocalposeparameters',   0x12C, 'i'),
    ('localposeparamindex',      0x130, 'i'),
    ('surfacepropindex',         0x134, 'i'),
    ('keyvalueindex',            0x138, 'i'),
    ('keyvaluesize',             0x13C, 'i'),
    ('numlocalikautoplaylocks',  0x140, 'i'),
    ('localikautoplaylockindex', 0x144, 'i'),
    ('mass',                     0x148, 'f'),
    ('contents',                 0x14C, 'i'),
    ('numincludemodels',         0x150, 'i'),
    ('includemodelindex',        0x154, 'i'),
    ('virtualModel',             0x158, 'i'),
    ('szanimblocknameindex',     0x15C, 'i'),
    ('numanimblocks',            0x160, 'i'),
    ('animblockindex',           0x164, 'i'),
    ('animblockModel',           0x168, 'i'),
    ('bonetablebynameindex',     0x16C, 'i'),
    ('pVertexBase',              0x170, 'i'),
    ('pIndexBase',               0x174, 'i'),
    ('constdirectionallightdot', 0x178, 'b'),
    ('rootLOD',                  0x179, 'b'),
    ('numAllowedRootLODs',       0x17A, 'b'),
    ('unused_byte',              0x17B, 'b'),
    ('unused4',                  0x17C, 'i'),
    ('numflexcontrollerui',      0x180, 'i'),
    ('flexcontrolleruiindex',    0x184, 'i'),
    ('flVertAnimFixedPointScale',0x188, 'f'),
    ('unused3',                  0x18C, 'i'),
    ('studiohdr2index',          0x190, 'i'),
    ('unused2',                  0x194, 'i'),
]
STUDIOHDR_SIZE = 0x198  # 408

# studiohdr2_t, located at hdr.studiohdr2index (absolute from file start)
STUDIOHDR2_FIELDS = [
    ('numsrcbonetransform',          0x00, 'i'),
    ('srcbonetransformindex',        0x04, 'i'),
    ('illumpositionattachmentindex', 0x08, 'i'),
    ('flMaxEyeDeflection',           0x0C, 'f'),
    ('linearboneindex',              0x10, 'i'),
    ('sznameindex',                  0x14, 'i'),
    ('m_nBoneFlexDriverCount',       0x18, 'i'),
    ('m_nBoneFlexDriverIndex',       0x1C, 'i'),
    # int reserved[56] -> 0x20 .. 0xFF ; sizeof(studiohdr2_t) == 0x100 == 256
]


def _read_fields(b, base, fields):
    out = {}
    for name, off, kind in fields:
        o = base + off
        if kind == 'i':      out[name] = i32(b, o)
        elif kind == 'f':    out[name] = f32(b, o)
        elif kind == 'b':    out[name] = u8(b, o)
        elif kind == 'v3':   out[name] = vec3(b, o)
        elif kind == 'fourcc': out[name] = b[o:o + 4].decode('ascii', 'replace')
        elif kind == 'char[64]': out[name] = b[o:o + 64].split(b'\x00')[0].decode('ascii', 'replace')
    return out


# ---- mstudiobone_t
# sizeof(mstudiobone_t) == 216, in v48 and v49.
BONE_SIZE = 216
BONE_FIELDS = [
    ('sznameindex',      0x00, 'i'),   # relative to the bone struct base
    ('parent',           0x04, 'i'),
    # int bonecontroller[6]  0x08..0x1F   (-1 = none)
    ('pos',              0x20, 'v3'),
    ('quat',             0x2C, 'v4'),   # x,y,z,w
    ('rot',              0x3C, 'v3'),   # euler radians (the "reference" rotation)
    ('posscale',         0x48, 'v3'),   # animation decode scale for pos channels
    ('rotscale',         0x54, 'v3'),   # animation decode scale for rot channels
    ('poseToBone',       0x60, 'm3x4'), # 48 bytes, 0x60..0x8F
    ('qAlignment',       0x90, 'v4'),
    ('flags',            0xA0, 'i'),
    ('proctype',         0xA4, 'i'),
    ('procindex',        0xA8, 'i'),
    ('physicsbone',      0xAC, 'i'),
    ('surfacepropidx',   0xB0, 'i'),
    ('contents',         0xB4, 'i'),
    # int unused[8]  0xB8 .. 0xD7  -> 216
]

# mstudiobone_t::flags
BONE_FLAGS = [
    (0x0001, 'PHYSICALLY_SIMULATED'), (0x0002, 'PHYSICS_PROCEDURAL'),
    (0x0004, 'ALWAYS_PROCEDURAL'),    (0x0008, 'SCREEN_ALIGN_SPHERE'),
    (0x0010, 'SCREEN_ALIGN_CYLINDER'),
    (0x00000100, 'USED_BY_HITBOX'),
    (0x00000200, 'USED_BY_ATTACHMENT'),
    (0x00000400, 'USED_BY_VERTEX_LOD0'),
    (0x00000800, 'USED_BY_VERTEX_LOD1'),
    (0x00001000, 'USED_BY_VERTEX_LOD2'),
    (0x00002000, 'USED_BY_VERTEX_LOD3'),
    (0x00004000, 'USED_BY_VERTEX_LOD4'),
    (0x00008000, 'USED_BY_VERTEX_LOD5'),
    (0x00010000, 'USED_BY_VERTEX_LOD6'),
    (0x00020000, 'USED_BY_VERTEX_LOD7'),
    (0x00040000, 'USED_BY_BONE_MERGE'),
]

def bone_flag_names(f):
    return [n for m, n in BONE_FLAGS if f & m]


class MDL:
    def __init__(self, path):
        self.path = path
        self.b = open(path, 'rb').read()
        b = self.b
        assert b[0:4] == b'IDST', 'not an MDL'
        self.hdr = _read_fields(b, 0, STUDIOHDR_FIELDS)
        assert self.hdr['length'] == len(b), (self.hdr['length'], len(b))
        self.hdr2 = None
        if self.hdr['studiohdr2index'] > 0:
            self.hdr2 = _read_fields(b, self.hdr['studiohdr2index'], STUDIOHDR2_FIELDS)
        self.bones = self._bones()
        self.textures = self._textures()
        self.cdtextures = self._cdtextures()
        self.skins = self._skins()
        self.bodyparts = self._bodyparts()
        self.attachments = self._attachments()
        self.poseparams = self._poseparams()

    # ---- bones
    def _bones(self):
        b = self.b; out = []
        for i in range(self.hdr['numbones']):
            base = self.hdr['boneindex'] + i * BONE_SIZE
            d = {}
            d['index'] = i
            d['name'] = cstr(b, base + i32(b, base + 0x00))
            d['parent'] = i32(b, base + 0x04)
            d['bonecontroller'] = struct.unpack_from('<6i', b, base + 0x08)
            d['pos'] = vec3(b, base + 0x20)
            d['quat'] = vec4(b, base + 0x2C)
            d['rot'] = vec3(b, base + 0x3C)
            d['posscale'] = vec3(b, base + 0x48)
            d['rotscale'] = vec3(b, base + 0x54)
            d['poseToBone'] = matrix3x4(b, base + 0x60)
            d['qAlignment'] = vec4(b, base + 0x90)
            d['flags'] = u32(b, base + 0xA0)
            d['proctype'] = i32(b, base + 0xA4)
            d['procindex'] = i32(b, base + 0xA8)
            d['physicsbone'] = i32(b, base + 0xAC)
            d['surfaceprop'] = cstr(b, base + i32(b, base + 0xB0)) if i32(b, base + 0xB0) else ''
            d['contents'] = i32(b, base + 0xB4)
            out.append(d)
        return out

    # ---- textures  (mstudiotexture_t, 64 bytes)
    def _textures(self):
        b = self.b; out = []
        for i in range(self.hdr['numtextures']):
            base = self.hdr['textureindex'] + i * 64
            out.append(cstr(b, base + i32(b, base + 0x00)))
        return out

    def _cdtextures(self):
        b = self.b
        return [cstr(b, i32(b, self.hdr['cdtextureindex'] + 4 * i))
                for i in range(self.hdr['numcdtextures'])]

    def _skins(self):
        b = self.b; n = self.hdr['numskinref']; out = []
        for fam in range(self.hdr['numskinfamilies']):
            base = self.hdr['skinindex'] + fam * n * 2
            out.append(list(struct.unpack_from('<%dh' % n, b, base)))
        return out

    # ---- bodypart / model / mesh tree
    def _bodyparts(self):
        b = self.b; out = []
        for i in range(self.hdr['numbodyparts']):
            base = self.hdr['bodypartindex'] + i * 16   # mstudiobodyparts_t = 16 bytes
            bp = {'index': i,
                  'name': cstr(b, base + i32(b, base + 0)),
                  'nummodels': i32(b, base + 4),
                  'base': i32(b, base + 8),
                  'modelindex': i32(b, base + 12),
                  'models': []}
            for m in range(bp['nummodels']):
                mb = base + bp['modelindex'] + m * 148   # mstudiomodel_t = 148 bytes
                md = {'name': b[mb:mb + 64].split(b'\x00')[0].decode('ascii', 'replace'),
                      'type': i32(b, mb + 64),
                      'boundingradius': f32(b, mb + 68),
                      'nummeshes': i32(b, mb + 72),
                      'meshindex': i32(b, mb + 76),
                      'numvertices': i32(b, mb + 80),
                      'vertexindex': i32(b, mb + 84),   # BYTE offset into the VVD vertex array
                      'tangentsindex': i32(b, mb + 88),
                      'numattachments': i32(b, mb + 92),
                      'attachmentindex': i32(b, mb + 96),
                      'numeyeballs': i32(b, mb + 100),
                      'eyeballindex': i32(b, mb + 104),
                      'meshes': []}
                for k in range(md['nummeshes']):
                    kb = mb + md['meshindex'] + k * 116  # mstudiomesh_t = 116 bytes
                    mesh = {'material': i32(b, kb + 0),
                            'modelindex': i32(b, kb + 4),
                            'numvertices': i32(b, kb + 8),
                            'vertexoffset': i32(b, kb + 12),  # vertex index offset inside the model
                            'numflexes': i32(b, kb + 16),
                            'flexindex': i32(b, kb + 20),
                            'materialtype': i32(b, kb + 24),
                            'materialparam': i32(b, kb + 28),
                            'meshid': i32(b, kb + 32),
                            'center': vec3(b, kb + 36),
                            # mstudio_meshvertexdata_t vertexdata: int modelvertexdata + int numLODVertexes[8]
                            'numLODVertexes': struct.unpack_from('<8i', b, kb + 52)}
                    md['meshes'].append(mesh)
                bp['models'].append(md)
            out.append(bp)
        return out

    # ---- attachments (mstudioattachment_t = 92 bytes)
    def _attachments(self):
        b = self.b; out = []
        for i in range(self.hdr['numlocalattachments']):
            base = self.hdr['localattachmentindex'] + i * 92
            out.append({'name': cstr(b, base + i32(b, base + 0)),
                        'flags': u32(b, base + 4),
                        'localbone': i32(b, base + 8),
                        'local': matrix3x4(b, base + 12)})
        return out

    # ---- pose parameters (mstudioposeparamdesc_t = 20 bytes)
    def _poseparams(self):
        b = self.b; out = []
        for i in range(self.hdr['numlocalposeparameters']):
            base = self.hdr['localposeparamindex'] + i * 20
            out.append({'name': cstr(b, base + i32(b, base + 0)),
                        'flags': i32(b, base + 4),
                        'start': f32(b, base + 8),
                        'end': f32(b, base + 12),
                        'loop': f32(b, base + 16)})
        return out

    # ---- include models (mstudiomodelgroup_t = 8 bytes)
    def include_models(self):
        b = self.b; out = []
        for i in range(self.hdr['numincludemodels']):
            base = self.hdr['includemodelindex'] + i * 8
            out.append((cstr(b, base + i32(b, base + 0)),   # label
                        cstr(b, base + i32(b, base + 4))))  # filename
        return out

    def bone_tree_lines(self):
        kids = {}
        for bo in self.bones:
            kids.setdefault(bo['parent'], []).append(bo['index'])
        lines = []
        def walk(i, depth):
            bo = self.bones[i]
            lines.append('%s[%3d] %s' % ('  ' * depth, i, bo['name']))
            for c in kids.get(i, []):
                walk(c, depth + 1)
        for r in kids.get(-1, []):
            walk(r, 0)
        return lines
