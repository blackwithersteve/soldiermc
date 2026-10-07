"""Bone FK + linear-blend skinning, matching Source's bone_setup.cpp order."""
import math


def quat_to_m3(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def mk34(q, p):
    r = quat_to_m3(q)
    return [[r[i][0], r[i][1], r[i][2], p[i]] for i in range(3)]


def mul34(a, b):
    o = [[0.0] * 4 for _ in range(3)]
    for i in range(3):
        for j in range(3):
            o[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]
        o[i][3] = a[i][0] * b[0][3] + a[i][1] * b[1][3] + a[i][2] * b[2][3] + a[i][3]
    return o


def xform(m, v):
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + m[0][3],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + m[1][3],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + m[2][3])


def rotate(m, v):
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2])


def bone_world(bones, local_pose):
    """Missing bones use the bind pose; MDL bones are stored parent before child."""
    w = {}
    for b in bones:
        i = b['index']
        p, q = local_pose.get(i, (b['pos'], b['quat']))
        L = mk34(q, p)
        w[i] = L if b['parent'] < 0 else mul34(w[b['parent']], L)
    return w


def skinning_matrices(bones, world):
    return {b['index']: mul34(world[b['index']], b['poseToBone']) for b in bones}


def skin(verts, skm):
    out = []
    for v in verts:
        px = py = pz = nx = ny = nz = 0.0
        for k in range(v['numbones']):
            wgt = v['weights'][k]
            m = skm[v['bones'][k]]
            p = xform(m, v['pos'])
            n = rotate(m, v['normal'])
            px += p[0] * wgt; py += p[1] * wgt; pz += p[2] * wgt
            nx += n[0] * wgt; ny += n[1] * wgt; nz += n[2] * wgt
        l = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
        out.append(((px, py, pz), (nx / l, ny / l, nz / l)))
    return out


# STUDIO_DELTA (flags & 0x0004) sequences are additive, layered as in SlerpBones().

def quat_normalize(q):
    n = math.sqrt(sum(c * c for c in q)) or 1.0
    return tuple(c / n for c in q)


def quat_mult(p, q):
    # As in QuaternionMult, q is first aligned to p's hemisphere.
    if p[3] * q[3] + p[0] * q[0] + p[1] * q[1] + p[2] * q[2] < 0:
        q = tuple(-c for c in q)
    px, py, pz, pw = p
    qx, qy, qz, qw = q
    return (pw * qx + px * qw + py * qz - pz * qy,
            pw * qy - px * qz + py * qw + pz * qx,
            pw * qz + px * qy - py * qx + pz * qw,
            pw * qw - px * qx - py * qy - pz * qz)


def quat_scale(q, s):
    """QuaternionScale(): scales the rotation angle by s, keeping the axis."""
    x, y, z, w = q
    sinom = math.sqrt(x * x + y * y + z * z)
    sinom = min(sinom, 1.0)
    sinsom = math.sin(math.asin(sinom) * s)
    if sinom > 1e-9:
        k = sinsom / sinom
        x, y, z = x * k, y * k, z * k
    else:
        x = y = z = 0.0
    ww = 1.0 - sinsom * sinsom
    w = math.sqrt(ww) if ww > 0 else 0.0
    if q[3] < 0:
        w = -w
    return (x, y, z, w)


def quat_ma(p, s, q):
    """QuaternionMA(p, s, q) = normalize(p * q^s)"""
    return quat_normalize(quat_mult(p, quat_scale(q, s)))


def apply_delta(base_local, delta_local, s, bones, weights=None):
    """`weights` is the sequence's per-bone weight list; None means 1.0 for every bone."""
    out = dict(base_local)
    for b in bones:
        i = b['index']
        if i not in delta_local:
            continue
        w = s if weights is None else s * weights[i]
        if w <= 0.0:
            continue
        bp, bq = base_local.get(i, (b['pos'], b['quat']))
        dp, dq = delta_local[i]
        out[i] = ((bp[0] + dp[0] * w, bp[1] + dp[1] * w, bp[2] + dp[2] * w),
                  quat_ma(bq, w, dq))
    return out


def seq_bone_weights(mdlobj, seqdesc):
    import struct
    n = len(mdlobj.bones)
    return list(struct.unpack_from('<%df' % n, mdlobj.b,
                                   seqdesc['off'] + seqdesc['weightlistindex']))
