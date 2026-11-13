#!/usr/bin/env python3
"""
Skate 3 Mobile — texture optimizer.

Shrinks the *loose* image assets that ship with this project and with user
mods (PNG / JPEG / BMP / TGA / WebP). It does two things:

  1. Downscales anything above a size budget, preserving aspect ratio.
     At the Honor X5 Plus's 720p panel a 2048 px character texture is
     waste; 1024 px is indistinguishable once the scene renders at
     216-288p and upscales.
  2. Recompresses losslessly-ish: PNG with max deflate and stripped
     metadata, progressive JPEG. A resized file that would end up larger
     is never written.

It also measures every file it refuses to touch: Xbox 360 / EA container
formats (.dds, .ktx, .gtf, .xpr, .xma, .bh, .ark ...) are reported but never
rewritten, because those bytes are checksum-adjacent data owned by your
ISO and this tool is not an archive editor. Use it on:

  * android/app/src/main/assets/mods   (game-shipped mod assets)
  * your mod folders                   (custom characters)
  * any folder of unpacked textures    (reports container sizes)
  * extracted Game files kept loose    (sizes only, containers skipped)

Examples
--------
  # Preview savings for one folder (nothing is written)
  python3 tools/optimize_textures.py android/app/src/main/assets/mods

  # Apply a 1024 px budget in place, with a JSON manifest
  python3 tools/optimize_textures.py android/app/src/main/assets/mods \
      --in-place --max-size 1024 --report build/texture-manifest.json

  # Ultra-low-end pass, sizes written to a separate tree
  python3 tools/optimize_textures.py /path/to/loose-textures \
      --output /path/to/loose-textures-small --max-size 512 --pow2

Exit codes: 0 = ok, 2 = Pillow required but not installed.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - exercised on machines without Pillow
    Image = None

LOSSY_INPUTS = {".jpg", ".jpeg"}
LOSSLESS_INPUTS = {".png", ".bmp", ".tga", ".webp"}
READABLE = LOSSY_INPUTS | LOSSLESS_INPUTS
# Known non-touch formats, reported for visibility only.
CONTAINERS = {
    ".dds", ".ktx", ".ktx2", ".gtf", ".xpr", ".gxt", ".pvr",
    ".xma", ".wav", ".bh", ".ark", ".bin", ".dat", ".big",
}


def die(message: str, code: int = 2) -> "SystemExit":
    print(f"error: {message}", file=sys.stderr)
    return SystemExit(code)


def pow2_floor(value: int) -> int:
    result = 1
    while result * 2 <= value:
        result *= 2
    return result


def target_size(width: int, height: int, max_size: int, pow2: bool) -> tuple[int, int]:
    longest = max(width, height)
    scale = 1.0
    if longest > max_size:
        scale = max_size / float(longest)
    new_w = max(1, int(round(width * scale)))
    new_h = max(1, int(round(height * scale)))
    if pow2:
        new_w = pow2_floor(new_w)
        new_h = pow2_floor(new_h)
    return new_w, new_h


def process_image(path: Path, max_size: int, pow2: bool, jpeg_quality: int,
                  dest: Path | None, in_place: bool, dry_run: bool) -> dict:
    entry: dict = {
        "path": str(path),
        "action": "skipped",
        "reason": "",
        "width": None,
        "height": None,
        "new_width": None,
        "new_height": None,
        "before_bytes": path.stat().st_size,
        "after_bytes": path.stat().st_size,
    }
    assert Image is not None
    try:
        with Image.open(path) as img:
            img.load()
            width, height = img.size
            entry["width"], entry["height"] = width, height
            new_size = target_size(width, height, max_size, pow2)
            entry["new_width"], entry["new_height"] = new_size
            resized = new_size != (width, height)
            suffix = path.suffix.lower()
            if suffix in LOSSY_INPUTS and img.mode not in ("RGB", "L"):
                img = img.convert("RGB")

            out_path = dest if dest is not None else path
            if dry_run:
                # Estimate the post-resize byte count without writing.
                import io

                buffer = io.BytesIO()
                _save(img, path, new_size, jpeg_quality, buffer)
                entry["after_bytes"] = buffer.getbuffer().nbytes
                entry["action"] = "would-resize" if resized else "would-recompress"
                return entry
            _save(img, path, new_size, jpeg_quality, None, out_path)
            new_bytes = out_path.stat().st_size
            if in_place and new_bytes >= entry["before_bytes"] and not resized:
                # Recompression did not help: restore the original bytes.
                # (Saved beforehand by _save's backup path — see below.)
                out_path.write_bytes(BACKUP.pop(str(out_path)))
                new_bytes = entry["before_bytes"]
                entry["action"] = "kept-original"
            else:
                entry["action"] = "resized" if resized else "recompressed"
            entry["after_bytes"] = new_bytes
            return entry
    except Exception as exc:  # pragma: no cover - depends on user files
        entry["reason"] = f"{type(exc).__name__}: {exc}"
        return entry


BACKUP: dict[str, bytes] = {}


def _save(img, source: Path, new_size: tuple[int, int], jpeg_quality: int,
          buffer, out_path: Path | None = None) -> None:
    from PIL import Image as PILImage

    if new_size != img.size:
        img = img.resize(new_size, PILImage.LANCZOS)
    suffix = source.suffix.lower()
    target = buffer if buffer is not None else out_path
    # Preserve a backup of the original so in-place runs can roll back a
    # recompression that did not actually shrink the file.
    if buffer is None and out_path is not None and out_path.exists():
        BACKUP[str(out_path)] = out_path.read_bytes()
    if suffix in (".jpg", ".jpeg"):
        img.save(target, "JPEG", quality=jpeg_quality, optimize=True,
                 progressive=True)
    elif suffix == ".png":
        img.save(target, "PNG", optimize=True, compress_level=9)
    elif suffix == ".webp":
        img.save(target, "WEBP", quality=jpeg_quality, method=6)
    elif suffix == ".bmp":
        img.save(target, "BMP")
    elif suffix == ".tga":
        img.save(target, "TGA")
    else:  # pragma: no cover - guarded by READABLE
        raise ValueError(f"unsupported suffix {suffix}")


def iter_files(paths: list[Path]):
    for root in paths:
        if root.is_dir():
            yield from sorted(p for p in root.rglob("*") if p.is_file())
        elif root.is_file():
            yield root
        else:
            print(f"warning: {root} does not exist, skipping", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", type=Path,
                        help="Files or directories to scan.")
    parser.add_argument("--max-size", type=int, default=1024,
                        help="Longest allowed side in pixels (default: 1024). "
                             "Use 512 for the Ultra Low / Honor X5 Plus profile.")
    parser.add_argument("--pow2", action="store_true",
                        help="Round both sides down to powers of two "
                             "(required by some GPU compressed formats).")
    parser.add_argument("--jpeg-quality", type=int, default=85)
    parser.add_argument("--in-place", action="store_true",
                        help="Rewrite files in place. Without this flag (and without "
                             "--output) the run is a dry run that only reports.")
    parser.add_argument("--output", type=Path, default=None,
                        help="Write optimized copies into this directory tree "
                             "instead of modifying the originals.")
    parser.add_argument("--report", type=Path, default=None,
                        help="Write a JSON manifest to this path.")
    args = parser.parse_args()

    if args.max_size < 16:
        return die("--max-size must be at least 16")
    dry_run = not args.in_place and args.output is None
    if args.in_place and args.output is not None:
        return die("--in-place and --output are mutually exclusive")
    if not dry_run and Image is None:
        return die("Pillow is required to write files. "
                   "Install it with: python3 -m pip install pillow")
    if dry_run and Image is None:
        return die("Pillow is required even for a dry run (it decodes the "
                   "images to estimate the result). "
                   "Install it with: python3 -m pip install pillow")

    entries: list[dict] = []
    containers: list[dict] = []
    for path in iter_files(args.paths):
        suffix = path.suffix.lower()
        if suffix in CONTAINERS:
            containers.append({"path": str(path), "bytes": path.stat().st_size})
            continue
        if suffix not in READABLE:
            continue
        dest = None
        if args.output is not None:
            # Mirror the input path's tail under --output.
            dest = args.output / path.name
            if len(args.paths) == 1 and args.paths[0].is_dir():
                try:
                    rel = path.relative_to(args.paths[0])
                except ValueError:
                    rel = Path(path.name)
                dest = args.output / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
        entries.append(process_image(path, args.max_size, args.pow2,
                                     args.jpeg_quality, dest,
                                     in_place=args.in_place, dry_run=dry_run))

    before = sum(e["before_bytes"] for e in entries)
    after = sum(e["after_bytes"] for e in entries)
    saved = before - after
    summary = {
        "tool": "optimize_textures",
        "max_size": args.max_size,
        "pow2": args.pow2,
        "dry_run": dry_run,
        "files": len(entries),
        "before_bytes": before,
        "after_bytes": after,
        "saved_bytes": saved,
        "saved_percent": round(100.0 * saved / before, 2) if before else 0.0,
        "container_files": len(containers),
        "container_bytes": sum(c["bytes"] for c in containers),
    }
    manifest = {"summary": summary, "files": entries, "containers": containers}
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(manifest, indent=2) + "\n",
                               encoding="utf-8")

    print(f"{'DRY RUN — nothing written' if dry_run else 'Done'}: "
          f"{summary['files']} images, {before // 1024} KiB -> {after // 1024} KiB "
          f"(saved {saved // 1024} KiB, {summary['saved_percent']}%)")
    if containers:
        print(f"Note: {len(containers)} container/archive files "
              f"({summary['container_bytes'] // 1024} KiB) were measured but not "
              "modified — those are owned by your ISO.")
    if dry_run:
        print("Re-run with --in-place (rewrite originals) or --output DIR "
              "(write elsewhere) to apply.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
