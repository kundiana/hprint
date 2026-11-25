"""Render one page of a PDF to a 1-bit raw PBM (P4) sized for the HP P1102 at A4, 1200x600 dpi.

Usage: python3 pdf_to_pbm.py in.pdf page_index out.pbm
"""
import sys

import numpy as np
import pymupdf

XDIM, YDIM = 9920, 7016  # A4 at 1200x600 dpi, the sizes foo2zjs-wrapper uses
XRES, YRES = 1200, 600


def main(pdf_path, page_index, out_path):
    doc = pymupdf.open(pdf_path)
    page = doc[int(page_index)]
    matrix = pymupdf.Matrix(XRES / 72.0, YRES / 72.0)
    pix = page.get_pixmap(matrix=matrix, colorspace=pymupdf.csGRAY, alpha=False)
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width)

    canvas = np.full((YDIM, XDIM), 255, dtype=np.uint8)  # white page
    h = min(YDIM, img.shape[0])
    w = min(XDIM, img.shape[1])
    canvas[:h, :w] = img[:h, :w]

    black = canvas < 128  # PBM: 1 = black
    packed = np.packbits(black, axis=1)  # rows padded to a byte boundary
    with open(out_path, "wb") as f:
        f.write(f"P4\n{XDIM} {YDIM}\n".encode())
        f.write(packed.tobytes())
    print(f"wrote {out_path}: {XDIM}x{YDIM}, {black.mean() * 100:.2f}% black")


if __name__ == "__main__":
    main(*sys.argv[1:4])
