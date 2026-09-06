"""
Source MDL animation decoding: mstudioanimdesc_t, mstudioseqdesc_t, the per-bone
mstudioanim_t stream, the RLE mstudioanimvalue_t encoding, Quaternion48/Quaternion64/
Vector48 and animation sections. Ported from ExtractAnimValue / CalcBonePosition /
CalcBoneQuaternion in bone_setup.cpp.
"""
import struct, math

# mstudioanim_t::flags
STUDIO_ANIM_RAWPOS  = 0x01   # Vector48
STUDIO_ANIM_RAWROT  = 0x02   # Quaternion48
STUDIO_ANIM_ANIMPOS = 0x04   # mstudioanim_valueptr_t
STUDIO_ANIM_ANIMROT = 0x08   # mstudioanim_valueptr_t
STUDIO_ANIM_DELTA   = 0x10
STUDIO_ANIM_RAWROT2 = 0x20   # Quaternion64

# mstudioanimdesc_t::flags
STUDIO_LOOPING  = 0x0001
STUDIO_SNAP     = 0x0002
STUDIO_DELTA    = 0x0004
STUDIO_AUTOPLAY = 0x0008
STUDIO_POST     = 0x0010
STUDIO_ALLZEROS = 0x0020
STUDIO_CYCLEPOSE= 0x0080
STUDIO_REALTIME = 0x0100
STUDIO_LOCAL    = 0x0200
STUDIO_HIDDEN   = 0x0400
STUDIO_OVERRIDE = 0x0800
STUDIO_ACTIVITY = 0x1000
STUDIO_EVENT    = 0x2000
STUDIO_WORLD    = 0x4000

ANIMDESC_SIZE = 100
SEQDESC_SIZE = 212


# ---- packed types
def float16(u):
    """Valve float16 -> float. IEEE half layout (sign 1, exp 5, mant 10), bias 15,
    except that exponent 31 maps to max float rather than inf/NaN."""
    s = (u >> 15) & 1
    e = (u >> 10) & 0x1F
    m = u & 0x3FF
    if e == 0:
        v = m / 1024.0 * (2.0 ** -14)          # denormal
    elif e == 31:
        v = 65504.0                            # Valve: maxfloat, not inf
    else:
        v = (1.0 + m / 1024.0) * (2.0 ** (e - 15))
    return -v if s else v


def vector48(b, o):
    return (float16(struct.unpack_from('<H', b, o)[0]),
            float16(struct.unpack_from('<H', b, o + 2)[0]),
            float16(struct.unpack_from('<H', b, o + 4)[0]))


def quat48(b, o):
    """6 bytes: x:16 y:16 z:15 wneg:1  -> (x,y,z,w)"""
    xs, ys, zs = struct.unpack_from('<3H', b, o)
    x = (xs - 32768) * (1.0 / 32768.0)
    y = (ys - 32768) * (1.0 / 32768.0)
    z = ((zs & 0x7FFF) - 16384) * (1.0 / 16384.0)
    wneg = (zs >> 15) & 1
    t = 1.0 - x * x - y * y - z * z
    w = math.sqrt(t) if t > 0 else 0.0
    return (x, y, z, -w if wneg else w)


def quat64(b, o):
    """8 bytes: x:21 y:21 z:21 wneg:1 (little-endian uint64) -> (x,y,z,w)"""
    v = struct.unpack_from('<Q', b, o)[0]
    xs = v & 0x1FFFFF
    ys = (v >> 21) & 0x1FFFFF
    zs = (v >> 42) & 0x1FFFFF
    wneg = (v >> 63) & 1
    x = (xs - 1048576) * (1.0 / 1048576.0)
    y = (ys - 1048576) * (1.0 / 1048576.0)
    z = (zs - 1048576) * (1.0 / 1048576.0)
    t = 1.0 - x * x - y * y - z * z
    w = math.sqrt(t) if t > 0 else 0.0
    return (x, y, z, -w if wneg else w)


# ---- RLE animvalue
def extract_anim_value(b, base, frame, scale):
    """Port of ExtractAnimValue(), bone_setup.cpp. `base` is the absolute file offset of
    the first mstudioanimvalue_t; returns the value at `frame` and at `frame+1`.

    mstudioanimvalue_t is a 2-byte union: struct { byte valid; byte total; } or short
    value. A run is one header plus `valid` shorts covering `total` frames; frames past
    `valid` repeat the last stored value.
    """
    if base <= 0:
        return 0.0, 0.0

    def hdr(p):
        return b[p], b[p + 1]                       # valid, total

    def val(p):
        return struct.unpack_from('<h', b, p)[0]

    p = base
    valid, total = hdr(p)
    if total == 1 and valid == 1:
        v = val(p + 2) * scale
        return v, v

    k = frame
    while total <= k:
        k -= total
        p += (valid + 1) * 2
        valid, total = hdr(p)
        if total == 0:
            return 0.0, 0.0                          # ran off the end

    if valid > k + 1:
        return val(p + (k + 1) * 2) * scale, val(p + (k + 2) * 2) * scale
    if valid > k:
        v = val(p + (k + 1) * 2) * scale
        return v, v
    v1 = val(p + valid * 2) * scale
    if total > k + 1:
        return v1, v1
    return v1, val(p + (valid + 2) * 2) * scale      # first value of the NEXT run


# ---- euler <-> quat
def angle_quaternion(rx, ry, rz):
    """Port of AngleQuaternion(RadianEuler). Intrinsic Z*Y*X."""
    sy, cy = math.sin(rz * 0.5), math.cos(rz * 0.5)
    sp, cp = math.sin(ry * 0.5), math.cos(ry * 0.5)
    sr, cr = math.sin(rx * 0.5), math.cos(rx * 0.5)
    srXcp, crXsp = sr * cp, cr * sp
    crXcp, srXsp = cr * cp, sr * sp
    return (srXcp * cy - crXsp * sy,
            crXsp * cy + srXcp * sy,
            crXcp * sy - srXsp * cy,
            crXcp * cy + srXsp * sy)


def quat_slerp_lin(q1, q2, s):
    """QuaternionBlend: nlerp with alignment."""
    d = sum(a * b for a, b in zip(q1, q2))
    if d < 0:
        q2 = tuple(-c for c in q2)
    q = tuple(q1[i] * (1 - s) + q2[i] * s for i in range(4))
    n = math.sqrt(sum(c * c for c in q)) or 1.0
    return tuple(c / n for c in q)


# ---- descriptors
def read_animdesc(b, base):
    d = {'off': base}
    d['baseptr'] = struct.unpack_from('<i', b, base + 0)[0]
    ni = struct.unpack_from('<i', b, base + 4)[0]
    d['name'] = _cstr(b, base + ni)
    d['fps'] = struct.unpack_from('<f', b, base + 8)[0]
    d['flags'] = struct.unpack_from('<i', b, base + 12)[0]
    d['numframes'] = struct.unpack_from('<i', b, base + 16)[0]
    d['nummovements'] = struct.unpack_from('<i', b, base + 20)[0]
    d['movementindex'] = struct.unpack_from('<i', b, base + 24)[0]
    d['animblock'] = struct.unpack_from('<i', b, base + 52)[0]
    d['animindex'] = struct.unpack_from('<i', b, base + 56)[0]
    d['numikrules'] = struct.unpack_from('<i', b, base + 60)[0]
    d['ikruleindex'] = struct.unpack_from('<i', b, base + 64)[0]
    d['animblockikruleindex'] = struct.unpack_from('<i', b, base + 68)[0]
    d['numlocalhierarchy'] = struct.unpack_from('<i', b, base + 72)[0]
    d['localhierarchyindex'] = struct.unpack_from('<i', b, base + 76)[0]
    d['sectionindex'] = struct.unpack_from('<i', b, base + 80)[0]
    d['sectionframes'] = struct.unpack_from('<i', b, base + 84)[0]
    d['zeroframespan'] = struct.unpack_from('<h', b, base + 88)[0]
    d['zeroframecount'] = struct.unpack_from('<h', b, base + 90)[0]
    d['zeroframeindex'] = struct.unpack_from('<i', b, base + 92)[0]
    d['zeroframestalltime'] = struct.unpack_from('<f', b, base + 96)[0]
    d['duration'] = (d['numframes'] - 1) / d['fps'] if d['fps'] > 0 else 0.0
    return d


def read_seqdesc(b, base):
    g = lambda o: struct.unpack_from('<i', b, base + o)[0]
    f = lambda o: struct.unpack_from('<f', b, base + o)[0]
    d = {'off': base}
    d['baseptr'] = g(0)
    d['name'] = _cstr(b, base + g(4))
    d['activityname'] = _cstr(b, base + g(8))
    d['flags'] = g(12)
    d['activity'] = g(16)
    d['actweight'] = g(20)
    d['numevents'] = g(24); d['eventindex'] = g(28)
    d['bbmin'] = struct.unpack_from('<3f', b, base + 32)
    d['bbmax'] = struct.unpack_from('<3f', b, base + 44)
    d['numblends'] = g(56)
    d['animindexindex'] = g(60)
    d['movementindex'] = g(64)
    d['groupsize'] = (g(68), g(72))
    d['paramindex'] = (g(76), g(80))
    d['paramstart'] = (f(84), f(88))
    d['paramend'] = (f(92), f(96))
    d['paramparent'] = g(100)
    d['fadeintime'] = f(104); d['fadeouttime'] = f(108)
    d['localentrynode'] = g(112); d['localexitnode'] = g(116); d['nodeflags'] = g(120)
    d['entryphase'] = f(124); d['exitphase'] = f(128); d['lastframe'] = f(132)
    d['nextseq'] = g(136); d['pose'] = g(140)
    d['numikrules'] = g(144)
    d['numautolayers'] = g(148); d['autolayerindex'] = g(152)
    d['weightlistindex'] = g(156)
    d['posekeyindex'] = g(160)
    d['numiklocks'] = g(164); d['iklockindex'] = g(168)
    d['keyvalueindex'] = g(172); d['keyvaluesize'] = g(176)
    d['cycleposeindex'] = g(180)
    n = d['groupsize'][0] * d['groupsize'][1]
    d['animindices'] = list(struct.unpack_from('<%dh' % n, b, base + d['animindexindex'])) if n else []
    return d


def _cstr(b, o):
    if o <= 0 or o >= len(b):
        return ''
    return b[o:b.index(b'\x00', o)].decode('ascii', 'replace')


# ---- the anim stream
def anim_data_offset(b, ad, frame):
    """Absolute offset of the mstudioanim_t chain covering `frame`, plus the
    frame index within that section. Handles sectioned animations."""
    if ad['sectionframes'] == 0:
        return ad['off'] + ad['animindex'], frame
    isec = frame // ad['sectionframes']
    # mstudioanimsections_t { int animblock; int animindex; } -- 8 bytes
    so = ad['off'] + ad['sectionindex'] + isec * 8
    animblock, animindex = struct.unpack_from('<ii', b, so)
    assert animblock == 0, 'external .ani animblock not present in this MDL'
    return ad['off'] + animindex, frame - isec * ad['sectionframes']


def iter_bone_anims(b, off):
    """Walk the mstudioanim_t linked list at `off`.
    mstudioanim_t { byte bone; byte flags; short nextoffset; }  -- 4 bytes.
    nextoffset is relative to this struct; 0 ends the chain."""
    while True:
        bone = b[off]
        flags = b[off + 1]
        nxt = struct.unpack_from('<h', b, off + 2)[0]
        yield bone, flags, off
        if nxt == 0:
            break
        off += nxt


def _data_ptr(off):
    return off + 4


def _rotv(off, flags):
    return _data_ptr(off)


def _posv(off, flags):
    return _data_ptr(off) + (6 if (flags & STUDIO_ANIM_ANIMROT) else 0)


def _quat_ptr(off, flags):
    return _data_ptr(off)


def _pos_ptr(off, flags):
    p = _data_ptr(off)
    if flags & STUDIO_ANIM_RAWROT:  p += 6
    if flags & STUDIO_ANIM_RAWROT2: p += 8
    if flags & STUDIO_ANIM_ANIMROT: p += 6
    if flags & STUDIO_ANIM_ANIMPOS: p += 6
    return p


def calc_bone_quaternion(b, off, flags, frame, s, bone):
    """bone = the mstudiobone_t dict from mdl.MDL (needs quat, rot, rotscale)."""
    if flags & STUDIO_ANIM_RAWROT:
        return quat48(b, _quat_ptr(off, flags))
    if flags & STUDIO_ANIM_RAWROT2:
        return quat64(b, _quat_ptr(off, flags))
    if not (flags & STUDIO_ANIM_ANIMROT):
        return (0.0, 0.0, 0.0, 1.0) if (flags & STUDIO_ANIM_DELTA) else bone['quat']

    vp = _rotv(off, flags)
    rel = struct.unpack_from('<3h', b, vp)          # short offset[3], self-relative
    scale = bone['rotscale']
    a1 = [0.0, 0.0, 0.0]
    a2 = [0.0, 0.0, 0.0]
    for j in range(3):
        base = vp + rel[j] if rel[j] > 0 else 0
        a1[j], a2[j] = extract_anim_value(b, base, frame, scale[j])
    if not (flags & STUDIO_ANIM_DELTA):
        for j in range(3):
            a1[j] += bone['rot'][j]
            a2[j] += bone['rot'][j]
    if s > 0.001 and a1 != a2:
        return quat_slerp_lin(angle_quaternion(*a1), angle_quaternion(*a2), s)
    return angle_quaternion(*a1)


def calc_bone_position(b, off, flags, frame, s, bone):
    if flags & STUDIO_ANIM_RAWPOS:
        p = vector48(b, _pos_ptr(off, flags))
        return p if (flags & STUDIO_ANIM_DELTA) else p   # RAWPOS is already absolute
    if not (flags & STUDIO_ANIM_ANIMPOS):
        return (0.0, 0.0, 0.0) if (flags & STUDIO_ANIM_DELTA) else bone['pos']

    vp = _posv(off, flags)
    rel = struct.unpack_from('<3h', b, vp)
    scale = bone['posscale']
    out = [0.0, 0.0, 0.0]
    for j in range(3):
        base = vp + rel[j] if rel[j] > 0 else 0
        v1, v2 = extract_anim_value(b, base, frame, scale[j])
        out[j] = v1 * (1.0 - s) + v2 * s if s > 0.001 else v1
    if not (flags & STUDIO_ANIM_DELTA):
        out = [out[j] + bone['pos'][j] for j in range(3)]
    return tuple(out)


def sample_frame(mdlobj, ad, frame, s=0.0):
    """Returns {bone index: (pos, quat)} for one integer frame of one animdesc.
    Bones absent from the stream keep their bind pose."""
    b = mdlobj.b
    off, local_frame = anim_data_offset(b, ad, frame)
    out = {}
    if ad['flags'] & STUDIO_ALLZEROS:
        return out
    for bone, flags, o in iter_bone_anims(b, off):
        if bone >= len(mdlobj.bones):
            break
        bb = mdlobj.bones[bone]
        out[bone] = (calc_bone_position(b, o, flags, local_frame, s, bb),
                     calc_bone_quaternion(b, o, flags, local_frame, s, bb))
    return out


def local_animdescs(mdlobj):
    h = mdlobj.hdr
    return [read_animdesc(mdlobj.b, h['localanimindex'] + i * ANIMDESC_SIZE)
            for i in range(h['numlocalanim'])]


def local_seqdescs(mdlobj):
    h = mdlobj.hdr
    return [read_seqdesc(mdlobj.b, h['localseqindex'] + i * SEQDESC_SIZE)
            for i in range(h['numlocalseq'])]
