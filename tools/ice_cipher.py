"""
Port of Valve's IceKey (src/mathlib/IceKey.cpp), Matthew Kwan's public-domain ICE cipher.
UTIL_DecodeICE (util_shared.cpp:993) uses level 0 ("Thin ICE", 8 rounds, 64-bit key).

Keys used by TF2:
    weapon scripts + playerclass scripts : b"E2NcUkG2"   (tf_shareddefs.cpp:1614)
    items_game / econ .ctx files          : b"A5fSXbf7"   (econ_item_system.h:79)
"""

_SMOD = ((333, 313, 505, 369),
         (379, 375, 319, 391),
         (361, 445, 451, 397),
         (397, 425, 395, 505))

_SXOR = ((0x83, 0x85, 0x9b, 0xcd),
         (0xcc, 0xa7, 0xad, 0x41),
         (0x4b, 0x2e, 0xd4, 0x33),
         (0xea, 0xcb, 0x2e, 0x04))

_PBOX = (0x00000001, 0x00000080, 0x00000400, 0x00002000,
         0x00080000, 0x00200000, 0x01000000, 0x40000000,
         0x00000008, 0x00000020, 0x00000100, 0x00004000,
         0x00010000, 0x00800000, 0x04000000, 0x20000000,
         0x00000004, 0x00000010, 0x00000200, 0x00008000,
         0x00020000, 0x00400000, 0x08000000, 0x10000000,
         0x00000002, 0x00000040, 0x00000800, 0x00001000,
         0x00040000, 0x00100000, 0x02000000, 0x80000000)

_KEYROT = (0, 1, 2, 3, 2, 1, 3, 0,
           1, 3, 2, 0, 3, 1, 0, 2)

_M32 = 0xFFFFFFFF


def _gf_mult(a, b, m):
    res = 0
    while b:
        if b & 1:
            res ^= a
        a <<= 1
        b >>= 1
        if a >= 256:
            a ^= m
    return res


def _gf_exp7(b, m):
    if b == 0:
        return 0
    x = _gf_mult(b, b, m)
    x = _gf_mult(b, x, m)
    x = _gf_mult(x, x, m)
    return _gf_mult(b, x, m)


def _perm32(x):
    res = 0
    i = 0
    while x:
        if x & 1:
            res |= _PBOX[i]
        i += 1
        x >>= 1
    return res


def _build_sboxes():
    sbox = [[0] * 1024 for _ in range(4)]
    for i in range(1024):
        col = (i >> 1) & 0xff
        row = (i & 0x1) | ((i & 0x200) >> 8)
        sbox[0][i] = _perm32((_gf_exp7(col ^ _SXOR[0][row], _SMOD[0][row]) << 24) & _M32)
        sbox[1][i] = _perm32((_gf_exp7(col ^ _SXOR[1][row], _SMOD[1][row]) << 16) & _M32)
        sbox[2][i] = _perm32((_gf_exp7(col ^ _SXOR[2][row], _SMOD[2][row]) << 8) & _M32)
        sbox[3][i] = _perm32(_gf_exp7(col ^ _SXOR[3][row], _SMOD[3][row]))
    return sbox


_SBOX = _build_sboxes()


class IceKey:
    def __init__(self, level, key):
        if level < 1:
            self._size, self._rounds = 1, 8
        else:
            self._size, self._rounds = level, level * 16
        self._ks = [[0, 0, 0] for _ in range(self._rounds)]
        self._set(key)

    def _schedule_build(self, kb, n, keyrot):
        for i in range(8):
            kr = keyrot[i]
            isk = self._ks[n + i]
            isk[0] = isk[1] = isk[2] = 0
            for j in range(15):
                idx = j % 3
                for k in range(4):
                    ci = (kr + k) & 3
                    bit = kb[ci] & 1
                    isk[idx] = ((isk[idx] << 1) | bit) & _M32
                    kb[ci] = ((kb[ci] >> 1) | ((bit ^ 1) << 15)) & 0xFFFF

    def _set(self, key):
        if self._rounds == 8:
            kb = [0, 0, 0, 0]
            for i in range(4):
                kb[3 - i] = (key[i * 2] << 8) | key[i * 2 + 1]
            self._schedule_build(kb, 0, _KEYROT)
            return
        for i in range(self._size):
            kb = [0, 0, 0, 0]
            for j in range(4):
                kb[3 - j] = (key[i * 8 + j * 2] << 8) | key[i * 8 + j * 2 + 1]
            self._schedule_build(kb, i * 8, _KEYROT)
            self._schedule_build(kb, self._rounds - 8 - i * 8, _KEYROT[8:])

    @staticmethod
    def _f(p, sk):
        tl = ((p >> 16) & 0x3ff) | (((p >> 14) | (p << 18)) & 0xffc00)
        tr = (p & 0x3ff) | ((p << 2) & 0xffc00)
        al = sk[2] & (tl ^ tr)
        ar = al ^ tr
        al ^= tl
        al ^= sk[0]
        ar ^= sk[1]
        return (_SBOX[0][al >> 10] | _SBOX[1][al & 0x3ff]
                | _SBOX[2][ar >> 10] | _SBOX[3][ar & 0x3ff]) & _M32

    def encrypt_block(self, b):
        l = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
        r = (b[4] << 24) | (b[5] << 16) | (b[6] << 8) | b[7]
        for i in range(0, self._rounds, 2):
            l ^= self._f(r, self._ks[i])
            r ^= self._f(l, self._ks[i + 1])
        # the C++ writes r into bytes 0-3 and l into bytes 4-7 (halves swap)
        return bytes([(r >> 24) & 0xff, (r >> 16) & 0xff, (r >> 8) & 0xff, r & 0xff,
                      (l >> 24) & 0xff, (l >> 16) & 0xff, (l >> 8) & 0xff, l & 0xff])

    def decrypt_block(self, b):
        l = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
        r = (b[4] << 24) | (b[5] << 16) | (b[6] << 8) | b[7]
        for i in range(self._rounds - 1, 0, -2):
            l ^= self._f(r, self._ks[i])
            r ^= self._f(l, self._ks[i - 1])
        # the C++ writes r into bytes 0-3 and l into bytes 4-7 (halves swap)
        return bytes([(r >> 24) & 0xff, (r >> 16) & 0xff, (r >> 8) & 0xff, r & 0xff,
                      (l >> 24) & 0xff, (l >> 16) & 0xff, (l >> 8) & 0xff, l & 0xff])


def decode_ice(data, key, level=0):
    """Mirror of UTIL_DecodeICE: ECB over whole 8-byte blocks; a trailing partial block
    is left untouched, as Valve does."""
    ik = IceKey(level, key)
    out = bytearray()
    n = len(data)
    full = n - (n % 8)
    for off in range(0, full, 8):
        out += ik.decrypt_block(data[off:off + 8])
    out += data[full:]
    return bytes(out)


if __name__ == "__main__":
    import os
    k = IceKey(0, b"E2NcUkG2")
    for _ in range(200):
        p = os.urandom(8)
        assert k.decrypt_block(k.encrypt_block(p)) == p, "ICE round-trip failed"
    print("ICE level-0 round-trip OK over 200 random blocks")
    print("E2NcUkG2 / 8 zero bytes ->", k.encrypt_block(b"\x00" * 8).hex())
