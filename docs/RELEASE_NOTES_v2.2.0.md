# Skate 3 Mobile v2.2.0

Compressed archives are now first-class import sources: the game, Seiyu mods
and GPU driver packages can all come in as `.zip`, `.rar`, `.7z`, `.tar` or
`.tar.gz` instead of loose files or an ISO.

## What changed

- **Game installs from archives.** **Select ISO or Archive** now accepts a
  `.zip`, `.rar`, `.7z`, `.tar` or `.tar.gz` archive of the extracted game
  tree. The archive must contain `default.xex` and `data/webkit/EAWebkit.xex`
  (USA/Europe retail), either at the top level or inside a single wrapping
  folder. The same SHA-256 retail verification as the ISO path applies.
- **One-file Seiyu import.** **Characters > Restore Seiyu > Import archive**
  takes a single archive with `base.obj` and `texture_diffuse.png` (any folder
  depth; exact names preferred).
- **GPU driver imports widen** from ZIP/ADPKG to also accept `.rar`, `.7z`,
  `.tar` and `.tar.gz`, with unchanged metadata, Android API and ARM64 ELF
  validation.
- **Safety for every archive import.** Format detection uses magic bytes (not
  the extension), entry paths are sanitized against zip-slip (absolute paths,
  drive letters and `..` are rejected), duplicate entries are rejected,
  symbolic links are skipped, and entry counts and total uncompressed size are
  capped per flow. Password-protected archives are not supported.
- **Seiyu's bundled texture is smaller.** The shipped texture was re-compressed
  to 1024px (574 KB, was 2.1 MB); the APK, the Mod Store catalog and the
  manual ZIP now all serve the same verified file.
- **Fix:** the bundled Seiyu installer was pinned to the old texture size and
  SHA-256 and would always fail verification; it now matches the shipped
  asset (bundled version marker bumped to `1.0.0-bundled.2`).
- Mod archives from streaming-only providers (Drive, Dropbox and similar) are
  spooled through the app cache automatically. The game archive flow still
  needs a seekable document, the same as the ISO flow.

## Known limits

- `.tar.gz` of a full game dump needs roughly double the temporary disk
  space; prefer `.zip`, `.7z` or `.rar` for the game itself.
- Windows-made 7z files using BCJ filters may not open (XZ for Java is not
  bundled); re-save without BCJ or use `.zip`.
- Encrypted archives are rejected with a clear message.
- `.tar.gz` game dumps are not recognized as an ISO fallback if they fail to
  open; they fail with a clear error instead.

## Third-party libraries added

- Apache Commons Compress 1.27.1 (Apache-2.0) with Commons IO and Commons Lang
- junrar 8.1.2 (UnRar License)
- slf4j-nop 2.0.17 (MIT)

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The APK does not contain retail Skate 3 game data. Use a legally obtained game
dump and follow the setup instructions in the repository.
