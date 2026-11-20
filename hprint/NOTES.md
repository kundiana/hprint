# Technical notes

## What was verified (on a Linux sandbox, no printer attached)

- The open-source `foo2zjs` driver lists the **HP LaserJet Pro P1102** as
  supported, and the project's own wrapper script gives its settings:
  `-z2 -P -L0`.
- `foo2zjs` compiled cleanly with plain `make foo2zjs`.
- A 2-page A4 test PDF was rendered to 1-bit raw PBM (9920 x 7016, which is
  A4 at 1200x600 dpi, the size the wrapper script uses) with `tools/pdf_to_pbm.py`.
- Converted with:
  `foo2zjs -z2 -P -L0 -r1200x600 -g9920x7016 -p9 -m1 -n1 < page.pbm > page.zjs`
  An 8.7 MB bitmap became a ~33 KB stream.
- The repo's own `zjsdecode` parsed the stream cleanly: A4 paper code 9,
  600 dpi, JBIG-compressed, correct START_DOC/START_PAGE/END_PAGE structure.
- `app/src/main/assets/testpage.zjs` is that converted page 1.

## What was NOT verified

- That the stream actually prints on a real P1102. No printer here.
- The Android app has never been compiled or run. The sandbox has no Android
  SDK and cannot reach Google's Maven server, so the first real build will be
  the GitHub Actions run. Expect to fix a small error or two together.

## Corrections to earlier assumptions

- The P1102 is NOT in foo2zjs's firmware list (sihp*.img files cover the older
  1000/1005/1018/1020/P1005/P1006/P1505). So no firmware upload is expected.
  If the test page does not print, this is the first thing to re-check.
- The P1102 exposes a "HP Smart Install" virtual CD drive in USB mode (see
  INSTALL in foo2zjs). Milestone 1 logs every USB interface so we can see
  whether the real printer interface (class 7) is visible to the phone.

## Milestones

1. (this) Send a canned page over USB OTG. Proves the cable + permissions + stream.
2. Receive a PDF via Android "Share -> hprint" (intent filter), so pages
   arriving on WhatsApp can be printed in two taps.
3. Render PDF pages on the phone with `android.graphics.pdf.PdfRenderer`.
   Do it in horizontal strips: a full A4 page at 1200x600 is ~278 MB as
   ARGB_8888, far too big. Threshold each strip to 1-bit and pack as PBM.
   Plain thresholding is fine for text and coloring pages; photos need dithering.
4. Run foo2zjs on the phone: compile `foo2zjs.c` + `jbig*.c` with the NDK
   (JNI, or ship the executable as a `lib*.so`). Pipe PBM in, ZJS out, then
   send to the USB endpoint (milestone 1 code).
5. Page range, copies, A4/Letter, and an Android PrintService so it appears
   in every app's normal Print menu.
