"""Make a 2-page A4 test PDF with text and a coloring-page style outline."""
import pymupdf

doc = pymupdf.open()
for n in (1, 2):
    page = doc.new_page(width=595.28, height=841.89)
    page.insert_text((72, 90), f"Printer test page {n}", fontsize=28)
    page.insert_text((72, 130), "HP LaserJet Pro P1102 via open-source ZJS driver", fontsize=12)
    for i in range(12):
        page.insert_text((72, 180 + i * 22), f"Line {i + 1}: The quick brown fox jumps over the lazy dog.", fontsize=11)
    page.draw_circle((300, 600), 90, width=3)
    page.draw_rect(pymupdf.Rect(210, 510, 390, 690), width=2)
doc.save("test.pdf")
print("saved test.pdf with", len(doc), "pages")
