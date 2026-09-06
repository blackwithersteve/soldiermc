#!/usr/bin/env python
"""VTF -> PNG. Pure Python + numpy + Pillow.

usage: python vtf2png.py <out_dir> <file.vtf> [file.vtf ...]
       python vtf2png.py --all-mips <out_dir> <file.vtf ...>
"""
import struct, sys, os
import numpy as np
from PIL import Image

FMT = {0:'RGBA8888',1:'ABGR8888',2:'RGB888',3:'BGR888',4:'RGB565',5:'I8',6:'IA88',7:'P8',8:'A8',
 9:'RGB888_BLUESCREEN',10:'BGR888_BLUESCREEN',11:'ARGB8888',12:'BGRA8888',13:'DXT1',14:'DXT3',
 15:'DXT5',16:'BGRX8888',17:'BGR565',18:'BGRX5551',19:'BGRA4444',20:'DXT1_ONEBITALPHA',
 21:'BGRA5551',22:'UV88',23:'UVWQ8888',24:'RGBA16161616F',25:'RGBA16161616',26:'UVLX8888'}
BPP = {0:32,1:32,2:24,3:24,4:16,5:8,6:16,7:8,8:8,9:24,10:24,11:32,12:32,16:32,17:16,
       18:16,19:16,21:16,22:16,23:32,24:64,25:64,26:32}


def _bs(f):                       # bytes per 4x4 block, or None if uncompressed
    return 8 if f in (13, 20) else 16 if f in (14, 15) else None


def image_size(fmt, w, h):
    b = _bs(fmt)
    if b is not None:
        return max(1, (w + 3) // 4) * max(1, (h + 3) // 4) * b
    return (w * h * BPP[fmt]) // 8


# ---- header

class VtfHeader:
    pass


def read_header(data):
    if data[:4] != b"VTF\x00":
        raise ValueError("not a VTF")
    h = VtfHeader()
    h.vmaj, h.vmin = struct.unpack_from("<II", data, 4)
    h.headerSize   = struct.unpack_from("<I",  data, 12)[0]
    h.width, h.height = struct.unpack_from("<HH", data, 16)
    h.flags        = struct.unpack_from("<I",  data, 20)[0]
    h.frames, h.firstFrame = struct.unpack_from("<HH", data, 24)
    h.reflectivity = struct.unpack_from("<3f", data, 32)
    h.bumpScale    = struct.unpack_from("<f",  data, 48)[0]
    h.hiFmt        = struct.unpack_from("<i",  data, 52)[0]
    h.mipCount     = data[56]
    h.loFmt        = struct.unpack_from("<i",  data, 57)[0]
    h.loW, h.loH   = data[61], data[62]
    h.depth   = struct.unpack_from("<H", data, 63)[0] if (h.vmaj, h.vmin) >= (7, 2) else 1
    h.numRes  = struct.unpack_from("<I", data, 68)[0] if (h.vmaj, h.vmin) >= (7, 3) else 0
    h.res = {}
    for i in range(h.numRes):                     # 7.3+ resource dir: 8 bytes per entry
        o = 80 + 8 * i
        h.res[bytes(data[o:o + 3])] = struct.unpack_from("<I", data, o + 4)[0]
    h.faces = 6 if (h.flags & 0x4000) else 1      # TEXTUREFLAGS_ENVMAP
    key = b"\x30\x00\x00"                         # VTF_LEGACY_RSRC_IMAGE
    if key in h.res:
        h.hiOff = h.res[key]
    else:
        lo = 0 if h.loFmt < 0 else image_size(h.loFmt, h.loW, h.loH)
        h.hiOff = h.headerSize + lo
    return h


def mip_offset(h, level):
    """Byte offset of mip `level` (0 = full size). VTF stores the SMALLEST mip FIRST."""
    off = h.hiOff
    for lvl in range(h.mipCount - 1, level, -1):
        w, hh = max(1, h.width >> lvl), max(1, h.height >> lvl)
        off += image_size(h.hiFmt, w, hh) * h.frames * h.faces * max(1, h.depth)
    return off


# ---- DXT

def _rgb565(c):
    r = (c >> 11) & 0x1F
    g = (c >> 5) & 0x3F
    b = c & 0x1F
    return (np.left_shift(r, 3) | np.right_shift(r, 2),
            np.left_shift(g, 2) | np.right_shift(g, 4),
            np.left_shift(b, 3) | np.right_shift(b, 2))


def _dxt_colour(buf, bw, bh, stride, coff, dxt1_alpha):
    """Decode the 8-byte colour half of every block -> (H,W,4) uint8."""
    raw = np.frombuffer(buf, dtype=np.uint8).reshape(bw * bh, stride)[:, coff:coff + 8]
    c0 = raw[:, 0].astype(np.uint16) | (raw[:, 1].astype(np.uint16) << 8)
    c1 = raw[:, 2].astype(np.uint16) | (raw[:, 3].astype(np.uint16) << 8)
    idx = (raw[:, 4].astype(np.uint32) | (raw[:, 5].astype(np.uint32) << 8)
           | (raw[:, 6].astype(np.uint32) << 16) | (raw[:, 7].astype(np.uint32) << 24))
    r0, g0, b0 = _rgb565(c0)
    r1, g1, b1 = _rgb565(c1)
    r0, g0, b0 = r0.astype(np.int32), g0.astype(np.int32), b0.astype(np.int32)
    r1, g1, b1 = r1.astype(np.int32), g1.astype(np.int32), b1.astype(np.int32)
    op = np.full_like(r0, 255)
    four = c0 > c1                                 # 4-colour vs 3-colour+punchthrough
    pal = np.zeros((bw * bh, 4, 4), np.int32)
    pal[:, 0] = np.stack([r0, g0, b0, op], 1)
    pal[:, 1] = np.stack([r1, g1, b1, op], 1)
    c2_4 = np.stack([(2 * r0 + r1) // 3, (2 * g0 + g1) // 3, (2 * b0 + b1) // 3, op], 1)
    c3_4 = np.stack([(r0 + 2 * r1) // 3, (g0 + 2 * g1) // 3, (b0 + 2 * b1) // 3, op], 1)
    c2_3 = np.stack([(r0 + r1) // 2, (g0 + g1) // 2, (b0 + b1) // 2, op], 1)
    a3 = 0 if dxt1_alpha else 255                  # transparent black vs opaque black
    z = np.zeros_like(r0)
    c3_3 = np.stack([z, z, z, np.full_like(r0, a3)], 1)
    f = four[:, None]
    pal[:, 2] = np.where(f, c2_4, c2_3)
    pal[:, 3] = np.where(f, c3_4, c3_3)
    shifts = (2 * np.arange(16, dtype=np.uint32))[None, :]
    sel = ((idx[:, None] >> shifts) & 3).astype(np.intp)      # pixel order y*4+x
    px = np.take_along_axis(pal, sel[:, :, None].repeat(4, 2), axis=1)   # (nb,16,4)
    px = px.reshape(bh, bw, 4, 4, 4).transpose(0, 2, 1, 3, 4).reshape(bh * 4, bw * 4, 4)
    return px.astype(np.uint8)


def _dxt5_alpha(buf, bw, bh):
    raw = np.frombuffer(buf, dtype=np.uint8).reshape(bw * bh, 16)[:, 0:8]
    a0 = raw[:, 0].astype(np.int32)
    a1 = raw[:, 1].astype(np.int32)
    bits = np.zeros(bw * bh, np.uint64)
    for k in range(6):
        bits |= raw[:, 2 + k].astype(np.uint64) << np.uint64(8 * k)
    pal = np.zeros((bw * bh, 8), np.int32)
    pal[:, 0] = a0
    pal[:, 1] = a1
    six = a0 > a1
    for i in range(1, 7):                          # 8-alpha (fully interpolated) branch
        pal[:, i + 1] = np.where(six, ((7 - i) * a0 + i * a1) // 7, pal[:, i + 1])
    for i in range(1, 5):                          # 6-alpha branch fills 2..5
        pal[:, i + 1] = np.where(six, pal[:, i + 1], ((5 - i) * a0 + i * a1) // 5)
    pal[:, 6] = np.where(six, pal[:, 6], 0)
    pal[:, 7] = np.where(six, pal[:, 7], 255)
    shifts = (np.uint64(3) * np.arange(16, dtype=np.uint64))[None, :]
    sel = ((bits[:, None] >> shifts) & np.uint64(7)).astype(np.intp)
    a = np.take_along_axis(pal, sel, axis=1)
    return a.reshape(bh, bw, 4, 4).transpose(0, 2, 1, 3).reshape(bh * 4, bw * 4).astype(np.uint8)


def _dxt3_alpha(buf, bw, bh):
    raw = np.frombuffer(buf, dtype=np.uint8).reshape(bw * bh, 16)[:, 0:8]
    lo = raw & 0x0F
    hi = raw >> 4
    a = np.empty((bw * bh, 16), np.uint8)
    a[:, 0::2] = lo
    a[:, 1::2] = hi
    a = a * 17                                     # 4-bit -> 8-bit
    return a.reshape(bh, bw, 4, 4).transpose(0, 2, 1, 3).reshape(bh * 4, bw * 4)


# ---- decode

def decode(data, h, level=0, frame=0, face=0):
    w, hh = max(1, h.width >> level), max(1, h.height >> level)
    sz = image_size(h.hiFmt, w, hh)
    base = mip_offset(h, level) + sz * (frame * h.faces + face)
    buf = data[base:base + sz]
    f = h.hiFmt
    bw, bh = max(1, (w + 3) // 4), max(1, (hh + 3) // 4)
    if f in (13, 20):
        img = _dxt_colour(buf, bw, bh, 8, 0, dxt1_alpha=True)
    elif f == 15:
        img = _dxt_colour(buf, bw, bh, 16, 8, dxt1_alpha=False)
        img[..., 3] = _dxt5_alpha(buf, bw, bh)
    elif f == 14:
        img = _dxt_colour(buf, bw, bh, 16, 8, dxt1_alpha=False)
        img[..., 3] = _dxt3_alpha(buf, bw, bh)
    else:
        a = np.frombuffer(buf, np.uint8)
        op = np.full((hh, w, 1), 255, np.uint8)
        if f == 0:
            img = a.reshape(hh, w, 4)                                    # RGBA8888
        elif f == 12:
            img = a.reshape(hh, w, 4)[..., [2, 1, 0, 3]]                 # BGRA8888
        elif f == 16:
            img = a.reshape(hh, w, 4)[..., [2, 1, 0, 3]].copy()          # BGRX8888
            img[..., 3] = 255
        elif f == 11:
            img = a.reshape(hh, w, 4)[..., [1, 2, 3, 0]]                 # ARGB8888
        elif f == 1:
            img = a.reshape(hh, w, 4)[..., [3, 2, 1, 0]]                 # ABGR8888
        elif f == 2:
            img = np.dstack([a.reshape(hh, w, 3), op])                   # RGB888
        elif f == 3:
            img = np.dstack([a.reshape(hh, w, 3)[..., ::-1], op])        # BGR888
        elif f == 5:
            g = a.reshape(hh, w)                                         # I8
            img = np.dstack([g, g, g, op[..., 0]])
        elif f == 8:
            g = a.reshape(hh, w)                                         # A8
            img = np.dstack([np.zeros_like(g), np.zeros_like(g), np.zeros_like(g), g])
        elif f == 6:
            t = a.reshape(hh, w, 2)                                      # IA88
            img = np.dstack([t[..., 0], t[..., 0], t[..., 0], t[..., 1]])
        elif f == 22:
            t = a.reshape(hh, w, 2)                                      # UV88
            img = np.dstack([t[..., 0], t[..., 1], op[..., 0], op[..., 0]])
        else:
            raise NotImplementedError("format %d (%s)" % (f, FMT.get(f, "?")))
    return np.ascontiguousarray(img[:hh, :w, :])


# ---- main

def convert(path, out_dir, all_mips=False):
    data = open(path, "rb").read()
    h = read_header(data)
    stem = os.path.splitext(os.path.basename(path))[0]
    os.makedirs(out_dir, exist_ok=True)
    outs = []
    levels = range(h.mipCount) if all_mips else [0]
    for lvl in levels:
        for fr in range(h.frames):
            img = decode(data, h, lvl, fr)
            n = stem
            if all_mips:
                n += "_mip%d" % lvl
            if h.frames > 1:
                n += "_f%d" % fr
            o = os.path.join(out_dir, n + ".png")
            Image.fromarray(img, "RGBA").save(o, optimize=True)
            outs.append((o, img.shape[1], img.shape[0], os.path.getsize(o),
                         int(img[..., 3].min()), int(img[..., 3].max())))
    return h, outs


if __name__ == "__main__":
    args = sys.argv[1:]
    all_mips = False
    if args and args[0] == "--all-mips":
        all_mips = True
        args = args[1:]
    out_dir, files = args[0], args[1:]
    for p in files:
        h, outs = convert(p, out_dir, all_mips)
        for o, w, hh, sz, amin, amax in outs:
            print("%-32s v%d.%d %-5s %4dx%-4d mips=%-2d -> %-34s %4dx%-4d %8d B  alpha[%d..%d]"
                  % (os.path.basename(p), h.vmaj, h.vmin, FMT.get(h.hiFmt, "?"),
                     h.width, h.height, h.mipCount, os.path.basename(o), w, hh, sz, amin, amax))
