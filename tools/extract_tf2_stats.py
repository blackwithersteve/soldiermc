#!/usr/bin/env python3
"""
Extract stock TF2 weapon stats from a TF2 install into a JSON data file for the mod.

Sources, in the order the engine reads them:
  1. scripts/tf_weapon_<name>.txt      -> base per-weapon stats (CTFWeaponInfo::Parse)
  2. scripts/playerclasses/<class>.txt -> max ammo, class health, class speed
  3. scripts/items/items_game.txt      -> item defs + prefabs + attribute defs

Loose files under <tf2>/tf/ win over VPK entries, matching the engine's search path.
TF2 ships these as ICE-encrypted .ctx, decoded here through ice_cipher.py.

Usage:
  python extract_tf2_stats.py --tf-dir "C:/Program Files (x86)/Steam/steamapps/common/Team Fortress 2/tf" \
                              --out ../src/main/resources/data/tf2soldier/tf2_stats.json
  python extract_tf2_stats.py --autodetect --out tf2_stats.json
"""

import argparse
import json
import os
import re
import struct
import sys

from ice_cipher import decode_ice

# ICE keys (level 0 / 64-bit). tf_shareddefs.cpp:1614 and econ_item_system.h:79
KEY_WEAPON_SCRIPTS = b"E2NcUkG2"
KEY_ECON_SCRIPTS = b"A5fSXbf7"

# ---- VPK v1/v2 directory reader (read-only)

VPK_MAGIC = 0x55AA1234


class Vpk:
    def __init__(self, dir_path):
        self.dir_path = dir_path
        self.base = dir_path[:-len("_dir.vpk")] if dir_path.endswith("_dir.vpk") else None
        self.entries = {}  # "scripts/tf_weapon_shovel.txt" -> entry dict
        self._read_dir()

    def _read_dir(self):
        with open(self.dir_path, "rb") as f:
            magic, version = struct.unpack("<II", f.read(8))
            if magic != VPK_MAGIC:
                raise ValueError("not a VPK: %s" % self.dir_path)
            if version == 1:
                (tree_size,) = struct.unpack("<I", f.read(4))
            elif version == 2:
                tree_size = struct.unpack("<IIIII", f.read(20))[0]
            else:
                raise ValueError("unsupported VPK version %d" % version)
            tree_start = f.tell()
            tree = f.read(tree_size)
            self.data_offset = tree_start + tree_size

        pos = 0

        def cstr():
            nonlocal pos
            end = tree.index(b"\x00", pos)
            s = tree[pos:end].decode("utf-8", "replace")
            pos = end + 1
            return s

        while True:
            ext = cstr()
            if ext == "":
                break
            while True:
                path = cstr()
                if path == "":
                    break
                while True:
                    name = cstr()
                    if name == "":
                        break
                    crc, preload_len, archive_index, entry_off, entry_len, term = \
                        struct.unpack_from("<IHHIIH", tree, pos)
                    pos += 18
                    preload = tree[pos:pos + preload_len]
                    pos += preload_len
                    full = "%s/%s.%s" % (path, name, ext) if path != " " else "%s.%s" % (name, ext)
                    self.entries[full.lower()] = {
                        "archive_index": archive_index,
                        "offset": entry_off,
                        "length": entry_len,
                        "preload": preload,
                    }

    def read(self, vpk_relative_path):
        e = self.entries.get(vpk_relative_path.lower().replace("\\", "/"))
        if e is None:
            return None
        if e["length"] == 0:
            return e["preload"]
        if e["archive_index"] == 0x7FFF:
            src, off = self.dir_path, self.data_offset + e["offset"]
        else:
            if not self.base:
                return None
            src = "%s_%03d.vpk" % (self.base, e["archive_index"])
            off = e["offset"]
        with open(src, "rb") as f:
            f.seek(off)
            return e["preload"] + f.read(e["length"])


# ---- Valve KeyValues (VDF) parser, text form; no #base/#include support

TOKEN_RE = re.compile(r'"((?:[^"\\]|\\.)*)"|(\{)|(\})|([^\s{}"]+)')


def kv_parse(text):
    """Parse KeyValues text into nested dicts. Duplicate keys become lists."""
    if text.startswith("\ufeff"):
        text = text[1:]
    # strip // comments outside quotes
    out, i, n, in_str = [], 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        out.append(c); i += 1
    text = "".join(out)

    tokens = []
    for m in TOKEN_RE.finditer(text):
        if m.group(1) is not None:
            tokens.append(("str", m.group(1)))
        elif m.group(2):
            tokens.append(("{", None))
        elif m.group(3):
            tokens.append(("}", None))
        else:
            tokens.append(("str", m.group(4)))

    pos = 0

    def add(d, k, v):
        lk = k.lower()
        if lk in d:
            if isinstance(d[lk], list):
                d[lk].append(v)
            else:
                d[lk] = [d[lk], v]
        else:
            d[lk] = v

    def block():
        nonlocal pos
        d = {}
        while pos < len(tokens):
            t, val = tokens[pos]
            if t == "}":
                pos += 1
                return d
            if t != "str":
                pos += 1
                continue
            key = val
            pos += 1
            if pos >= len(tokens):
                break
            t2, v2 = tokens[pos]
            if t2 == "{":
                pos += 1
                add(d, key, block())
            else:
                pos += 1
                add(d, key, v2)
        return d

    root = {}
    while pos < len(tokens):
        t, val = tokens[pos]
        if t != "str":
            pos += 1
            continue
        key = val
        pos += 1
        if pos < len(tokens) and tokens[pos][0] == "{":
            pos += 1
            add(root, key, block())
        else:
            pos += 1
    return root


# ---- Prefab resolution: MergeDefinitionPrefab / RecursiveInheritKeyValues
# (econ_item_schema.cpp:2940 and :2897)

def recursive_inherit(out, src):
    for k, v in src.items():
        if isinstance(v, dict):
            child = out.get(k)
            if not isinstance(child, dict):
                child = {}
                out[k] = child
            recursive_inherit(child, v)
        else:
            out[k] = v


def merge_prefab(out, src, prefabs):
    name = src.get("prefab")
    if isinstance(name, str):
        # "iterate backwards so adjectives get applied over the noun prefab"
        for p in reversed(name.split()):
            kv = prefabs.get(p.lower())
            if kv:
                merge_prefab(out, kv, prefabs)
    recursive_inherit(out, src)


def resolve_item(item_kv, prefabs):
    out = {}
    merge_prefab(out, item_kv, prefabs)
    return out


# ---- File access layer: loose file first, then VPKs

class TfFiles:
    def __init__(self, tf_dir):
        self.tf_dir = tf_dir
        self.vpks = []
        for fn in sorted(os.listdir(tf_dir)):
            if fn.endswith("_dir.vpk"):
                try:
                    self.vpks.append(Vpk(os.path.join(tf_dir, fn)))
                except Exception as e:
                    print("  warn: %s: %s" % (fn, e), file=sys.stderr)

    def read_bytes(self, rel):
        """Loose file wins over VPK, exactly like the engine's MOD/GAME search paths."""
        loose = os.path.join(self.tf_dir, rel.replace("/", os.sep))
        if os.path.isfile(loose):
            with open(loose, "rb") as f:
                return f.read(), "loose:" + rel
        for v in self.vpks:
            data = v.read(rel)
            if data is not None:
                return data, os.path.basename(v.dir_path) + ":" + rel
        return None, None

    def read_text(self, rel):
        data, src = self.read_bytes(rel)
        if data is None:
            return None, None
        return data.decode("utf-8", "replace"), src

    def read_kv(self, rel_no_ext, key):
        """Mirror of ReadEncryptedKVFile (weapon_parse.cpp:196): try <name>.txt,
        fall back to ICE-decoding <name>.ctx. TF2 ships .ctx for these."""
        txt, src = self.read_text(rel_no_ext + ".txt")
        if txt is not None:
            return txt, src
        raw, src = self.read_bytes(rel_no_ext + ".ctx")
        if raw is None:
            return None, None
        plain = decode_ice(raw, key).decode("utf-8", "replace")
        if "{" not in plain:
            raise RuntimeError("ICE decode of %s.ctx produced no KeyValues - wrong key?"
                               % rel_no_ext)
        return plain, src + " (ICE-decoded)"

    def read_weapon_script(self, classname):
        return self.read_kv("scripts/" + classname, KEY_WEAPON_SCRIPTS)


# ---- Extraction

# entity classname -> which class-slot it is, and which items_game def index is stock
TARGETS = [
    ("tf_weapon_rocketlauncher",   "soldier", "primary",   18),
    ("tf_weapon_shotgun_soldier",  "soldier", "secondary", 10),
    ("tf_weapon_shovel",           "soldier", "melee",      6),
]

# Keys read by CTFWeaponInfo::Parse (tf_weapon_parse.cpp:68-97) and
# FileWeaponInfo_t::Parse (weapon_parse.cpp:364-442). Defaults match the source.
WEAPON_KEYS_INT = {
    "damage": 0, "bulletspershot": 0, "clip_size": -1, "clip2_size": -1,
    "ammopershot": 1, "bucket": 0, "bucket_position": 0, "weight": 0,
    "meleeweapon": 0, "usearapidfirecrits": 0,
}
WEAPON_KEYS_FLOAT = {
    "range": 8192.0, "spread": 0.0, "punchangle": 0.0, "timefiredelay": 0.0,
    # The engine reads the misspelled key "TimeIdleEmpy" (tf_weapon_parse.cpp:75), so
    # both spellings are captured.
    "timeidle": 0.0, "timeidleempy": 0.0, "timeidleempty": 0.0, "timereloadstart": 0.0,
    "timereload": 0.0, "projectilespeed": 0.0, "smackdelay": 0.2,
    "damageradius": 0.0, "primertime": 0.0,
}
WEAPON_KEYS_STR = {
    "printname": "", "weapontype": "", "projectiletype": "projectile_none",
    "primary_ammo": "None", "secondary_ammo": "None", "anim_prefix": "",
    "viewmodel": "", "playermodel": "",
}


def num(v, default):
    try:
        return type(default)(float(v))
    except (TypeError, ValueError):
        return default


def extract_weapon(kv):
    wd = kv.get("weapondata", kv)
    out = {}
    for k, d in WEAPON_KEYS_INT.items():
        out[k] = num(wd.get(k), d)
    for k, d in WEAPON_KEYS_FLOAT.items():
        out[k] = num(wd.get(k), d)
    for k, d in WEAPON_KEYS_STR.items():
        out[k] = wd.get(k, d) if isinstance(wd.get(k, d), str) else d
    # secondary mode inherits from primary unless overridden (tf_weapon_parse.cpp:108-120)
    sec = {}
    for k in ("damage", "bulletspershot", "ammopershot"):
        sec[k] = num(wd.get("secondary_" + k), out[k])
    for k in ("range", "spread", "punchangle", "timefiredelay", "timeidle",
              "timereloadstart", "timereload", "smackdelay"):
        sec[k] = num(wd.get("secondary_" + k), out[k])
    out["secondary_mode"] = sec
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tf-dir", help="path to <Team Fortress 2>/tf")
    ap.add_argument("--autodetect", action="store_true")
    ap.add_argument("--out", default="tf2_stats.json")
    ap.add_argument("--all-weapons", action="store_true",
                    help="dump every scripts/tf_weapon_*.txt found, not just the three targets")
    args = ap.parse_args()

    tf_dir = args.tf_dir
    if not tf_dir and args.autodetect:
        for root in (r"C:\Program Files (x86)\Steam\steamapps\common\Team Fortress 2\tf",
                     r"D:\SteamLibrary\steamapps\common\Team Fortress 2\tf",
                     os.path.expanduser("~/.steam/steam/steamapps/common/Team Fortress 2/tf")):
            if os.path.isdir(root):
                tf_dir = root
                break
    if not tf_dir or not os.path.isdir(tf_dir):
        sys.exit("TF2 'tf' directory not found. Pass --tf-dir.")

    files = TfFiles(tf_dir)
    print("tf dir : %s" % tf_dir)
    print("vpks   : %s" % ", ".join(os.path.basename(v.dir_path) for v in files.vpks))

    result = {
        "_source": "extracted from a local TF2 install by tools/extract_tf2_stats.py",
        "_units": {
            "distance": "Hammer units (1 hu = 0.75 in; TF2 player is 83 hu tall)",
            "time": "seconds",
            "speed": "hammer units / second",
            "damage": "hit points, per shot/pellet, BEFORE range ramp, spread and crits",
        },
        "_provenance": {},
        "engine_constants": {
            "rocket_launch_speed": 1100.0,
            "rocket_blast_radius": 146.0,
            "rocket_self_blast_radius": 121.0,
            "melee_swing_range": 48.0,
            "melee_swing_bounds": 18.0,
            "damage_range_spread": 0.5,
            "damage_ramp_optimal_distance": 512.0,
            "_cite": [
                "tf_weaponbase_rocket.cpp:274 (1100 launch speed)",
                "tf_weaponbase_rocket.h:26 (TF_ROCKET_RADIUS 146)",
                "tf_weaponbase_rocket.h:25 (TF_ROCKET_RADIUS_FOR_RJS 110*1.1)",
                "tf_weaponbase_melee.cpp:164-179 (swing range 48)",
                "tf_weaponbase_melee.cpp:399-401 (swing bounds +/-18)",
                "tf_player.cpp:197 (tf_damage_range 0.5)",
                "tf_gamerules.cpp:6591 (flOptimalDistance 512)",
            ],
        },
        "weapons": {},
        "classes": {},
        "items": {},
    }

    # ---- per-weapon scripts
    names = [t[0] for t in TARGETS]
    if args.all_weapons:
        seen = set(names)
        for v in files.vpks:
            for p in v.entries:
                m = re.fullmatch(r"scripts/(tf_weapon_[a-z0-9_]+)\.(?:txt|ctx)", p)
                if m and m.group(1) not in seen:
                    seen.add(m.group(1))
                    names.append(m.group(1))

    for cls in names:
        txt, src = files.read_weapon_script(cls)
        if txt is None:
            print("  MISSING scripts/%s.txt" % cls, file=sys.stderr)
            continue
        result["weapons"][cls] = extract_weapon(kv_parse(txt))
        result["_provenance"][cls] = src
        print("  ok %-32s <- %s" % (cls, src))

    # ---- player class scripts
    for pclass in ("soldier",):
        txt, src = files.read_kv("scripts/playerclasses/%s" % pclass, KEY_WEAPON_SCRIPTS)
        if txt is None:
            print("  MISSING scripts/playerclasses/%s.txt" % pclass, file=sys.stderr)
            continue
        kv = kv_parse(txt)
        root = kv.get(pclass) or next(iter(kv.values()))
        ammo = root.get("ammomax", {}) if isinstance(root, dict) else {}
        result["classes"][pclass] = {
            "health_max": num(root.get("health_max"), 0),
            "speed_max": num(root.get("speed_max"), 0.0),
            "ammo_max": {k.upper(): num(v, 0) for k, v in ammo.items()},
        }
        result["_provenance"]["playerclass_" + pclass] = src
        print("  ok %-32s <- %s" % (pclass, src))

    # ---- items_game.txt
    txt, src = files.read_kv("scripts/items/items_game", KEY_ECON_SCRIPTS)
    if txt is None:
        print("  MISSING scripts/items/items_game.txt", file=sys.stderr)
    else:
        root = kv_parse(txt)
        schema = root.get("items_game") or next(iter(root.values()))
        prefabs = {k.lower(): v for k, v in (schema.get("prefabs") or {}).items()}
        items = schema.get("items") or {}
        attrs = schema.get("attributes") or {}
        # attribute def index -> (name, attribute_class, description_format)
        attr_by_name = {}
        for idx, a in attrs.items():
            if not isinstance(a, dict):
                continue
            attr_by_name[a.get("name", "").lower()] = {
                "defindex": idx,
                "attribute_class": a.get("attribute_class"),
                "description_format": a.get("description_format"),
                "effect_type": a.get("effect_type"),
            }

        for _cls, _pc, _slot, defindex in TARGETS:
            raw = items.get(str(defindex))
            if not isinstance(raw, dict):
                # defindex guess missed; find the stock (baseitem) def by item_class
                for k, v in items.items():
                    if not isinstance(v, dict):
                        continue
                    m = resolve_item(v, prefabs)
                    if m.get("item_class") == _cls and str(m.get("baseitem", "0")) == "1":
                        raw, defindex = v, k
                        print("  note: %s stock def is %s, not the assumed index" % (_cls, k))
                        break
            if not isinstance(raw, dict):
                print("  MISSING item def for %s" % _cls, file=sys.stderr)
                continue
            merged = resolve_item(raw, prefabs)
            static = {}
            for section in ("static_attrs", "attributes"):
                blk = merged.get(section)
                if isinstance(blk, dict):
                    for an, av in blk.items():
                        value = av.get("value") if isinstance(av, dict) else av
                        static[an] = {
                            "value": value,
                            "hook": (attr_by_name.get(an.lower()) or {}).get("attribute_class"),
                            "format": (attr_by_name.get(an.lower()) or {}).get("description_format"),
                        }
            result["items"][str(defindex)] = {
                "name": merged.get("name"),
                "item_class": merged.get("item_class"),
                "item_slot": merged.get("item_slot"),
                "prefab_chain": raw.get("prefab"),
                "baseitem": merged.get("baseitem"),
                "used_by_classes": list((merged.get("used_by_classes") or {}).keys()),
                "attributes": static,
                "_stock_note": ("no stat attributes => weapon-script values ARE the stock stats"
                                if not static else "HAS attributes - apply them to the script base"),
            }
            print("  ok item %-5s %-28s attrs=%d" % (defindex, merged.get("item_class"), len(static)))
        result["_provenance"]["items_game"] = src

    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=False)
    print("wrote %s" % out)


if __name__ == "__main__":
    main()
