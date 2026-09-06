// Decrypts TF2's scripts/tf_weapon_*.ctx: KeyValues run through Kwan's ICE cipher at
// level 0 (8-byte key, 8-byte block, 8 rounds), key "E2NcUkG2" - source-sdk-2013
// src/game/shared/tf/tf_shareddefs.cpp:1614. Whole 8-byte blocks only, trailing
// remainder left as-is (src/game/shared/util_shared.cpp:993 UTIL_DecodeICE).
//
//   java DecryptCtx.java in.ctx [out.txt]
//   java DecryptCtx.java scripts\*.ctx            (writes <name>.txt beside each)
//
// Requires JDK 11+ (single-file source launch).

import java.io.IOException;
import java.nio.file.*;

public class DecryptCtx {

    private static final String TF2_KEY = "E2NcUkG2";

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: java DecryptCtx.java <file.ctx> [out.txt]");
            System.exit(2);
        }
        String out = args.length > 1 ? args[1] : null;
        for (String a : args.length > 1 ? new String[]{args[0]} : args) {
            byte[] buf = Files.readAllBytes(Path.of(a));
            new IceKey(0, TF2_KEY.getBytes("ISO-8859-1")).decryptInPlace(buf);
            Path dst = out != null ? Path.of(out)
                     : Path.of(a.replaceFirst("(?i)\\.ctx$", "") + ".txt");
            Files.write(dst, buf);
            System.out.printf("%s -> %s (%d bytes)%n", a, dst, buf.length);
        }
    }

    /** Kwan's ICE block cipher. level 0 == "Thin ICE": 64-bit key, 8 rounds. */
    static final class IceKey {
        private static final int[][] S_MOD = {
            {333, 313, 505, 369}, {379, 375, 319, 391},
            {361, 445, 451, 397}, {397, 425, 395, 505}};
        private static final int[][] S_XOR = {
            {0x83, 0x85, 0x9b, 0xcd}, {0xcc, 0xa7, 0xad, 0x41},
            {0x4b, 0x2e, 0xd4, 0x33}, {0xea, 0xcb, 0x2e, 0x04}};
        private static final int[] P_BOX = {
            0x00000001, 0x00000080, 0x00000400, 0x00002000,
            0x00080000, 0x00200000, 0x01000000, 0x40000000,
            0x00000008, 0x00000020, 0x00000100, 0x00004000,
            0x00010000, 0x00800000, 0x04000000, 0x20000000,
            0x00000004, 0x00000010, 0x00000200, 0x00008000,
            0x00020000, 0x00400000, 0x08000000, 0x10000000,
            0x00000002, 0x00000040, 0x00000800, 0x00001000,
            0x00040000, 0x00100000, 0x02000000, 0x80000000};
        private static final int[] KEY_ROT = {
            0, 1, 2, 3, 2, 1, 3, 0, 1, 3, 2, 0, 3, 1, 0, 2};
        private static final int[][] SP_BOX = new int[4][1024];
        static {
            for (int i = 0; i < 1024; i++) {
                int col = (i >> 1) & 0xff;
                int row = (i & 0x1) | ((i & 0x200) >> 8);
                for (int b = 0; b < 4; b++) {
                    int x = gfExp7(col ^ S_XOR[b][row], S_MOD[b][row]) << (24 - 8 * b);
                    SP_BOX[b][i] = perm32(x);
                }
            }
        }

        private final int rounds;
        private final int[][] keySched;

        IceKey(int level, byte[] key) {
            this.rounds = (level < 1) ? 8 : level * 16;
            this.keySched = new int[rounds][3];
            if (rounds == 8) {
                if (key.length < 8) throw new IllegalArgumentException("level 0 needs an 8-byte key");
                int[] kb = new int[4];
                for (int i = 0; i < 4; i++)
                    kb[3 - i] = ((key[i * 2] & 0xff) << 8) | (key[i * 2 + 1] & 0xff);
                scheduleBuild(kb, 0, 0);
            } else {
                for (int i = 0; i < level; i++) {
                    int[] kb = new int[4];
                    for (int j = 0; j < 4; j++)
                        kb[3 - j] = ((key[i * 8 + j * 2] & 0xff) << 8) | (key[i * 8 + j * 2 + 1] & 0xff);
                    scheduleBuild(kb, i * 8, 0);
                    scheduleBuild(kb, rounds - 8 - i * 8, 8);
                }
            }
        }

        private void scheduleBuild(int[] kb, int n, int krIdx) {
            for (int i = 0; i < 8; i++) {
                int kr = KEY_ROT[krIdx + i];
                int[] isk = keySched[n + i];
                isk[0] = isk[1] = isk[2] = 0;
                for (int j = 0; j < 15; j++) {
                    int sk = j % 3;
                    for (int k = 0; k < 4; k++) {
                        int idx = (kr + k) & 3;
                        int bit = kb[idx] & 1;
                        isk[sk] = (isk[sk] << 1) | bit;
                        kb[idx] = (kb[idx] >> 1) | ((bit ^ 1) << 15);
                    }
                }
            }
        }

        private static int gfMult(int a, int b, int m) {
            int res = 0;
            while (b != 0) {
                if ((b & 1) != 0) res ^= a;
                a <<= 1; b >>= 1;
                if (a >= 256) a ^= m;
            }
            return res;
        }

        private static int gfExp7(int b, int m) {
            if (b == 0) return 0;
            int x = gfMult(b, b, m);      // b^2
            x = gfMult(b, x, m);          // b^3
            x = gfMult(x, x, m);          // b^6
            return gfMult(b, x, m);       // b^7
        }

        private static int perm32(int x) {
            int res = 0, i = 0;
            while (x != 0) {
                if ((x & 1) != 0) res |= P_BOX[i];
                i++;
                x >>>= 1;
            }
            return res;
        }

        private static int f(int p, int[] sk) {
            int tl = ((p >>> 16) & 0x3ff) | (((p >>> 14) | (p << 18)) & 0xffc00);
            int tr = (p & 0x3ff) | ((p << 2) & 0xffc00);
            int al = sk[2] & (tl ^ tr);
            int ar = al ^ tr;
            al ^= tl;
            al ^= sk[0];
            ar ^= sk[1];
            return SP_BOX[0][al >>> 10] | SP_BOX[1][al & 0x3ff]
                 | SP_BOX[2][ar >>> 10] | SP_BOX[3][ar & 0x3ff];
        }

        /** Decrypts whole 8-byte blocks; a trailing partial block is left untouched. */
        void decryptInPlace(byte[] buf) {
            for (int off = 0; off + 8 <= buf.length; off += 8) {
                int l = 0, r = 0;
                for (int i = 0; i < 4; i++) {
                    l = (l << 8) | (buf[off + i] & 0xff);
                    r = (r << 8) | (buf[off + 4 + i] & 0xff);
                }
                for (int i = rounds - 1; i > 0; i -= 2) {
                    l ^= f(r, keySched[i]);
                    r ^= f(l, keySched[i - 1]);
                }
                for (int i = 0; i < 4; i++) {
                    buf[off + 3 - i] = (byte) (r & 0xff); r >>>= 8;
                    buf[off + 7 - i] = (byte) (l & 0xff); l >>>= 8;
                }
            }
        }
    }
}
