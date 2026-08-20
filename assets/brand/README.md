# HAPI brand assets

Canonical HAPI icon source used by the web, documentation, website, Android,
and iOS applications.

## Design decisions

- Face, symmetric eyes, smile, coral palette, and rounded robot frame retained.
- Side nodes become mirrored angle-bracket ports: `< HAPI >`.
- The brackets carry two meanings without touching the face: developer/code
  context and local ↔ remote handoff direction.
- Directional negative space changes the port structure, rather than merely
  swapping a circle for another primitive.
- Endpoint cutouts are true transparency and survive monochrome use.
- Separate PWA maskable and Android adaptive optical masters enlarge the face
  independently while keeping both handoff ports in their safe areas.
- 16 px uses simplified solid brackets; 32 px and above retain the negative
  angle-bracket ports.
- Horizontal lockup canvas remains tightened; no terminal-cursor treatment.

## Palette

- HAPI Coral: `#F25562`
- Warm White: `#FFF8F8`
- White: `#FFFFFF`
- Ink: `#17181C`

## Source SVGs

- `svg/hapi-mark.svg` — transparent primary brand mark.
- `svg/hapi-app-icon.svg` — full-bleed app icon master.
- `svg/hapi-tiny.svg` — simplified 16 px favicon master.
- `svg/hapi-small.svg` — optically corrected 24–64 px master.
- `svg/hapi-maskable.svg` — PWA maskable optical master.
- `svg/hapi-adaptive-foreground.svg` — Android adaptive foreground master.
- `svg/hapi-monochrome.svg` — one-color mark.
- `svg/hapi-lockup-horizontal.svg` — tightened horizontal lockup.

## Sync platform assets

```bash
./assets/brand/export.sh
```

The script renders into a temporary directory, then updates only the files used
by `web/`, `docs/`, `website/`, `android/`, and `ios/`. Generated intermediates
are deliberately not stored in the repository.

Android adaptive, themed, and notification icons remain native VectorDrawable
resources under `android/app/src/main/res/`; keep them aligned with
`svg/hapi-adaptive-foreground.svg` when changing the geometry.
