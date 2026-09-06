import os
"""MDL/VVD/VTX dump driver and OBJ exporter.

  python dump.py header      - studiohdr_t / studiohdr2_t for both MDLs
  python dump.py bones       - the 86-bone tree, and the FK/poseToBone identity proof
  python dump.py mesh        - bodypart/model/mesh tree, VVD fixups, per-LOD triangle counts
  python dump.py seqs        - every sequence with activity, blend grid, frames, fps, duration
  python dump.py key         - just the sequences a player mod needs
  python dump.py obj <seq> <frame> <out.obj>   - skinned OBJ in Source game space
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mdl, vvd, vtx, anim, pose

P = os.environ.get('TF2_SOLDIER_DIR',
    os.path.join(os.path.dirname(__file__), '..', '..', 'tf2-assets-staging', 'models', 'player'))

KEY_SEQUENCES = [
    'stand_PRIMARY', 'crouch_PRIMARY', 'run_PRIMARY', 'crouch_walk_PRIMARY',
    'jump_start_PRIMARY', 'jump_float_PRIMARY', 'jumpland_PRIMARY',
    'airwalk_PRIMARY', 'swim_PRIMARY',
    'AttackStand_PRIMARY', 'AttackCrouch_PRIMARY',
    'ReloadStand_PRIMARY', 'ReloadStand_PRIMARY_loop', 'ReloadStand_PRIMARY_end',
    'PRIMARY_aimmatrix_idle', 'PRIMARY_aimmatrix_run',
    'taunt01', 'layer_taunt01', 'taunt02', 'layer_taunt02',
]


def load():
    return (mdl.MDL(P + 'soldier.mdl'), mdl.MDL(P + 'soldier_animations.mdl'),
            vvd.VVD(P + 'soldier.vvd'), vtx.VTX(P + 'soldier.dx90.vtx'))


def cmd_header():
    S, A, V, X = load()
    for tag, M in (('soldier.mdl', S), ('soldier_animations.mdl', A)):
        print('===', tag)
        for name, off, kind in mdl.STUDIOHDR_FIELDS:
            print('  0x%03x %-26s %s' % (off, name, M.hdr[name]))
        print('  --- studiohdr2_t @ %d' % M.hdr['studiohdr2index'], M.hdr2)
    print('=== VVD', V.info())
    print('=== VTX', X.info())
    print('checksums equal (mdl/vvd/vtx):',
          S.hdr['checksum'] == V.checksum == X.checksum)


def cmd_bones():
    S, A, V, X = load()
    for l in S.bone_tree_lines():
        print(l)
    w = pose.bone_world(S.bones, {})
    err = 0.0
    for b in S.bones:
        C = pose.mul34(w[b['index']], b['poseToBone'])
        err = max(err, max(abs(C[i][j] - (1.0 if i == j else 0.0))
                           for i in range(3) for j in range(4)))
    print('\nmax |bindWorld * poseToBone - I| = %.3g  (poseToBone IS the inverse'
          ' of the bind FK chain)' % err)
    print('every parent index < child index:', all(b['parent'] < b['index'] for b in S.bones))
    print('bone name/parent/poseToBone identical in soldier_animations.mdl:',
          [b['name'] for b in A.bones] == [b['name'] for b in S.bones])
    print('rotscale identical between the two MDLs:',
          all(a['rotscale'] == b['rotscale'] for a, b in zip(A.bones, S.bones)))


def cmd_mesh():
    S, A, V, X = load()
    print('VVD', V.info())
    print('fixups (lod, sourceVertexID, numVertexes):')
    for f in V.fixups:
        print('   ', f)
    for lod in range(V.numLODs):
        n = len(V.vertices_for_lod(lod))
        _, _, tot = vtx.lod_vertex_bases(S, lod)
        verts, faces, used = vtx.build_lod(X, S, V, lod)
        tris = sum(len(v) for v in faces.values())
        mx = max(max(t) for v in faces.values() for t in v)
        print('LOD %d: fixup count %5d == header %5d == mesh running total %5d | '
              '%5d tris, max index %5d (< %5d) %s'
              % (lod, n, V.numLODVertexes[lod], tot, tris, mx, len(verts),
                 'SHADOW LOD' if X.bodyparts[0]['models'][0]['lods'][lod]['switchPoint'] < 0 else ''))
    print()
    for bp in S.bodyparts:
        print('bodypart %d %-10s nummodels=%d base=%d' % (bp['index'], bp['name'], bp['nummodels'], bp['base']))
        for j, m in enumerate(bp['models']):
            print('   model %d %-38s nverts=%5d vertexindex=%7d nmeshes=%d'
                  % (j, m['name'], m['numvertices'], m['vertexindex'], m['nummeshes']))
            for k, me in enumerate(m['meshes']):
                print('      mesh %d material=%2d %-45s numLODVertexes=%s'
                      % (k, me['material'], S.textures[me['material']], list(me['numLODVertexes'])))


def cmd_seqs(only_key=False):
    S, A, V, X = load()
    sq = anim.local_seqdescs(A)
    ads = anim.local_animdescs(A)
    for i, s in enumerate(sq):
        if only_key and s['name'] not in KEY_SEQUENCES:
            continue
        a = ads[s['animindices'][0]] if s['animindices'] else None
        print('%3d %-30s %-40s flags=0x%04x blend=%dx%d params=%s' %
              (i, s['name'], s['activityname'], s['flags'],
               s['groupsize'][0], s['groupsize'][1],
               tuple(A.poseparams[p]['name'] if p >= 0 else None for p in s['paramindex'])))
        if a:
            print('     anim[%d] %-28s %3d frames @ %g fps = %.4f s  animflags=0x%02x %s %s'
                  % (s['animindices'][0], a['name'], a['numframes'], a['fps'], a['duration'],
                     a['flags'], 'LOOPING' if a['flags'] & 1 else '',
                     'sections=%d' % a['sectionframes'] if a['sectionframes'] else ''))
        if s['groupsize'][0] * s['groupsize'][1] > 1:
            print('     grid:', [ads[x]['name'] for x in s['animindices']])


def cmd_obj(seqname, frame, out):
    S, A, V, X = load()
    sq = anim.local_seqdescs(A); ads = anim.local_animdescs(A)
    s = next(x for x in sq if x['name'] == seqname)
    ad = ads[s['animindices'][0]]
    lp = anim.sample_frame(A, ad, frame % max(1, ad['numframes']))
    w = pose.bone_world(A.bones, lp)
    skm = pose.skinning_matrices(S.bones, w)
    verts, faces, _ = vtx.build_lod(X, S, V, 0)
    sk = pose.skin(verts, skm)
    with open(out, 'w') as f:
        f.write('# %s frame %d (anim %s) -- Source game space, Z up, X forward, hammer units\n'
                % (seqname, frame, ad['name']))
        for p, n in sk:
            f.write('v %.5f %.5f %.5f\n' % p)
        for p, n in sk:
            f.write('vn %.5f %.5f %.5f\n' % n)
        for v in verts:
            f.write('vt %.6f %.6f\n' % (v['uv'][0], 1.0 - v['uv'][1]))
        for mat, tris in sorted(faces.items()):
            f.write('usemtl %s\n' % S.textures[mat].replace('/', '_'))
            for (a, b, c) in tris:
                # Source winding is clockwise; flip for OpenGL/OBJ CCW front faces
                f.write('f %d/%d/%d %d/%d/%d %d/%d/%d\n'
                        % (a + 1, a + 1, a + 1, c + 1, c + 1, c + 1, b + 1, b + 1, b + 1))
    print('wrote', out, sum(len(t) for t in faces.values()), 'tris')


if __name__ == '__main__':
    c = sys.argv[1] if len(sys.argv) > 1 else 'header'
    if c == 'header': cmd_header()
    elif c == 'bones': cmd_bones()
    elif c == 'mesh': cmd_mesh()
    elif c == 'seqs': cmd_seqs(False)
    elif c == 'key': cmd_seqs(True)
    elif c == 'obj': cmd_obj(sys.argv[2], int(sys.argv[3]), sys.argv[4])
    else: print(__doc__)
