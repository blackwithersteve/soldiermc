package com.soldiermc.studio;

/** Reads studiohdr_t and its tables; v48 and v49 share the 408-byte header. */
public final class MdlFile {

    private static final int BONE_SIZE = 216;
    private static final int BODYPART_SIZE = 16;
    private static final int MODEL_SIZE = 148;
    private static final int MESH_SIZE = 116;
    private static final int TEXTURE_SIZE = 64;

    public final int version;
    public final int checksum;
    public final int length;
    public final String name;

    public final float[] hullMin = new float[3];
    public final float[] hullMax = new float[3];

    public final Bone[] bones;
    public final String[] textures;
    public final String[] cdTextures;
    public final BodyPart[] bodyParts;
    public final AnimSampler.AnimDesc[] animDescs;
    public final SeqDesc[] sequences;
    public final PoseParam[] poseParams;

    public MdlFile(byte[] bytes) {
        Reader r = new Reader(bytes);

        int id = r.i32At(0);
        if (id != 0x54534449) {   // 'IDST' little-endian
            throw new IllegalArgumentException("not an MDL (bad magic)");
        }
        version = r.i32At(0x004);
        checksum = r.i32At(0x008);
        name = r.fixedStringAt(0x00C, 64);
        length = r.i32At(0x04C);

        for (int i = 0; i < 3; i++) {
            hullMin[i] = r.f32At(0x068 + i * 4);
            hullMax[i] = r.f32At(0x074 + i * 4);
        }

        int numBones = r.i32At(0x09C);
        int boneIndex = r.i32At(0x0A0);
        int numTextures = r.i32At(0x0CC);
        int textureIndex = r.i32At(0x0D0);
        int numCdTextures = r.i32At(0x0D4);
        int cdTextureIndex = r.i32At(0x0D8);
        int numBodyParts = r.i32At(0x0E8);
        int bodyPartIndex = r.i32At(0x0EC);
        int numLocalAnim = r.i32At(0x0B4);
        int localAnimIndex = r.i32At(0x0B8);
        int numLocalSeq = r.i32At(0x0BC);
        int localSeqIndex = r.i32At(0x0C0);
        int numPoseParams = r.i32At(0x12C);
        int poseParamIndex = r.i32At(0x130);

        bones = new Bone[numBones];
        for (int i = 0; i < numBones; i++) {
            int base = boneIndex + i * BONE_SIZE;
            Bone b = new Bone();
            b.index = i;
            b.name = r.stringAt(base, r.i32At(base));
            b.parent = r.i32At(base + 0x04);
            for (int k = 0; k < 3; k++) b.pos[k] = r.f32At(base + 0x20 + k * 4);
            for (int k = 0; k < 4; k++) b.quat[k] = r.f32At(base + 0x2C + k * 4);
            for (int k = 0; k < 3; k++) b.refRot[k] = r.f32At(base + 0x3C + k * 4);
            for (int k = 0; k < 3; k++) b.posScale[k] = r.f32At(base + 0x48 + k * 4);
            for (int k = 0; k < 3; k++) b.rotScale[k] = r.f32At(base + 0x54 + k * 4);
            for (int k = 0; k < 12; k++) b.poseToBone[k] = r.f32At(base + 0x60 + k * 4);
            b.flags = r.i32At(base + 0xA0);
            bones[i] = b;
        }

        textures = new String[numTextures];
        for (int i = 0; i < numTextures; i++) {
            int base = textureIndex + i * TEXTURE_SIZE;
            textures[i] = r.stringAt(base, r.i32At(base));
        }

        cdTextures = new String[numCdTextures];
        for (int i = 0; i < numCdTextures; i++) {
            int abs = r.i32At(cdTextureIndex + 4 * i);
            cdTextures[i] = r.stringAt(0, abs);   // absolute, unlike the other string offsets
        }

        bodyParts = new BodyPart[numBodyParts];
        for (int i = 0; i < numBodyParts; i++) {
            int base = bodyPartIndex + i * BODYPART_SIZE;
            BodyPart bp = new BodyPart();
            bp.name = r.stringAt(base, r.i32At(base));
            int numModels = r.i32At(base + 4);
            int modelIndex = r.i32At(base + 12);
            bp.models = new Model[numModels];

            for (int m = 0; m < numModels; m++) {
                int mb = base + modelIndex + m * MODEL_SIZE;
                Model md = new Model();
                md.name = r.fixedStringAt(mb, 64);
                int numMeshes = r.i32At(mb + 72);
                int meshIndex = r.i32At(mb + 76);
                md.numVertices = r.i32At(mb + 80);
                md.vertexIndex = r.i32At(mb + 84);
                md.meshes = new Mesh[numMeshes];

                for (int k = 0; k < numMeshes; k++) {
                    int kb = mb + meshIndex + k * MESH_SIZE;
                    Mesh mesh = new Mesh();
                    mesh.material = r.i32At(kb);
                    mesh.numVertices = r.i32At(kb + 8);
                    mesh.vertexOffset = r.i32At(kb + 12);
                    for (int l = 0; l < 8; l++) mesh.numLodVertexes[l] = r.i32At(kb + 52 + l * 4);
                    md.meshes[k] = mesh;
                }
                bp.models[m] = md;
            }
            bodyParts[i] = bp;
        }

        animDescs = new AnimSampler.AnimDesc[numLocalAnim];
        for (int i = 0; i < numLocalAnim; i++) {
            int base = localAnimIndex + i * 100;
            AnimSampler.AnimDesc ad = new AnimSampler.AnimDesc();
            ad.structBase = base;
            ad.name = r.stringAt(base, r.i32At(base + 4));
            ad.fps = r.f32At(base + 8);
            ad.flags = r.i32At(base + 12);
            ad.numFrames = r.i32At(base + 16);
            ad.animOffset = base + r.i32At(base + 56);
            ad.sectionIndex = r.i32At(base + 80);
            ad.sectionFrames = r.i32At(base + 84);
            animDescs[i] = ad;
        }

        sequences = new SeqDesc[numLocalSeq];
        for (int i = 0; i < numLocalSeq; i++) {
            int base = localSeqIndex + i * 212;
            SeqDesc sd = new SeqDesc();
            sd.index = i;
            sd.structBase = base;
            sd.name = r.stringAt(base, r.i32At(base + 4));
            sd.activityName = r.stringAt(base, r.i32At(base + 8));
            sd.flags = r.i32At(base + 12);
            sd.groupSizeX = r.i32At(base + 68);
            sd.groupSizeY = r.i32At(base + 72);
            sd.paramIndex0 = r.i32At(base + 76);
            sd.paramIndex1 = r.i32At(base + 80);
            sd.paramStart0 = r.f32At(base + 84);
            sd.paramStart1 = r.f32At(base + 88);
            sd.paramEnd0 = r.f32At(base + 92);
            sd.paramEnd1 = r.f32At(base + 96);
            sd.numAutoLayers = r.i32At(base + 148);
            sd.autoLayerIndex = r.i32At(base + 152);
            sd.weightListIndex = r.i32At(base + 156);
            sd.poseKeyIndex = r.i32At(base + 160);

            int animIndexIndex = r.i32At(base + 60);
            int n = sd.groupSizeX * sd.groupSizeY;
            sd.blends = new int[n];
            for (int k = 0; k < n; k++) {
                sd.blends[k] = r.i16At(base + animIndexIndex + k * 2);
            }
            if (sd.numAutoLayers > 0) {
                sd.autoLayers = new AutoLayer[sd.numAutoLayers];
                for (int k = 0; k < sd.numAutoLayers; k++) {
                    int ab = base + sd.autoLayerIndex + k * 24;
                    AutoLayer al = new AutoLayer();
                    al.sequence = r.i16At(ab);
                    al.pose = r.i16At(ab + 2);
                    al.flags = r.i32At(ab + 4);
                    al.start = r.f32At(ab + 8);
                    al.peak = r.f32At(ab + 12);
                    al.tail = r.f32At(ab + 16);
                    al.end = r.f32At(ab + 20);
                    sd.autoLayers[k] = al;
                }
            }
            if (sd.poseKeyIndex != 0 && sd.groupSizeX > 0 && sd.groupSizeY > 0) {
                int keys = sd.groupSizeX + sd.groupSizeY;
                sd.poseKeys = new float[keys];
                for (int k = 0; k < keys; k++) {
                    sd.poseKeys[k] = r.f32At(base + sd.poseKeyIndex + k * 4);
                }
            }
            if (sd.weightListIndex != 0) {
                sd.boneWeights = new float[numBones];
                for (int k = 0; k < numBones; k++) {
                    sd.boneWeights[k] = r.f32At(base + sd.weightListIndex + k * 4);
                }
            }
            sequences[i] = sd;
        }

        poseParams = new PoseParam[numPoseParams];
        for (int i = 0; i < numPoseParams; i++) {
            int base = poseParamIndex + i * 20;
            PoseParam pp = new PoseParam();
            pp.name = r.stringAt(base, r.i32At(base));
            pp.flags = r.i32At(base + 4);
            pp.start = r.f32At(base + 8);
            pp.end = r.f32At(base + 12);
            pp.loop = r.f32At(base + 16);
            poseParams[i] = pp;
        }
    }

    public SeqDesc findSequence(String name) {
        for (SeqDesc sd : sequences) {
            if (sd.name.equals(name)) return sd;
        }
        return null;
    }

    public AnimSampler.AnimDesc findAnim(String name) {
        for (AnimSampler.AnimDesc ad : animDescs) {
            if (ad.name.equals(name)) return ad;
        }
        return null;
    }

    public static final class Bone {
        public int index;
        public String name;
        public int parent;
        public final float[] pos = new float[3];
        public final float[] quat = new float[4];
        public final float[] refRot = new float[3];
        public final float[] posScale = new float[3];
        public final float[] rotScale = new float[3];
        public final float[] poseToBone = new float[12];
        public int flags;
    }

    /** blends is indexed y * groupSizeX + x; locomotion grids put move_y on X. */
    public static final class SeqDesc {
        public int index;
        public int structBase;
        public String name;
        public String activityName;
        public int flags;
        public int groupSizeX, groupSizeY;
        public int paramIndex0, paramIndex1;
        public float paramStart0, paramStart1, paramEnd0, paramEnd1;
        public int numAutoLayers, autoLayerIndex, weightListIndex, poseKeyIndex;
        public int[] blends;
        public AutoLayer[] autoLayers = new AutoLayer[0];
        public float[] boneWeights;
        public float[] poseKeys;

        public float poseKey(int localIndex, int i) {
            return poseKeys[localIndex * groupSizeX + i];
        }

        public boolean isBlend() {
            return groupSizeX > 1 || groupSizeY > 1;
        }
    }

    public static final class AutoLayer {
        public int sequence;
        public int pose;
        public int flags;
        public float start, peak, tail, end;
    }

    public static final class PoseParam {
        public String name;
        public int flags;
        public float start, end, loop;
    }

    public static final class BodyPart {
        public String name;
        public Model[] models;
    }

    public static final class Model {
        public String name;
        public int numVertices;
        public int vertexIndex;   // byte offset into the VVD vertex data, not an index
        public Mesh[] meshes;
    }

    public static final class Mesh {
        public int material;
        public int numVertices;
        public int vertexOffset;
        public final int[] numLodVertexes = new int[8];
    }
}
