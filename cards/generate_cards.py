"""Cartes ArUco (DICT_4X4_50) du stand Buddy -> cards/cartes-buddy.pdf. Usage: python3 cards/generate_cards.py"""
import tempfile
from pathlib import Path

import cv2
import numpy as np
import pymupdf
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
MEDIA = ROOT / "display/out/_next/static/media"
FONTS = {  # police -> woff2 (sous-ensemble latin) du site d'affichage
    "anton": "6e7755c9813efd51-s.p.0pan85mumqyf3.woff2",
    "mono": "ab56636924653a94-s.p.3cik_s2si-ft-.woff2",
}
BLUE, WHITE = "#013afb", "#ffffff"
GREEN, ORANGE, VIOLET = "#00ff97", "#ff5f3a", "#ff1ef7"
PROFIL, QUIZ = "Profil tech : quel métier tech es-tu ?", "Quiz Vrai ou Faux"
# (id, libellé, sous-titre, accent)
CARDS = [(0, "A", None, GREEN), (1, "B", None, ORANGE), (2, "C", None, VIOLET), (3, "D", None, WHITE),
         (10, "VRAI", None, GREEN), (11, "FAUX", None, ORANGE),
         (20, "JOUER", PROFIL, GREEN), (21, "JOUER", QUIZ, VIOLET)]

CAPH = 0.86  # hauteur de capitale d'Anton / em (approx. mesurée)
W, H = 842, 595            # A4 paysage (pt)
CELL = 14 * 28.3465 / 6    # marqueur noir 14 cm = 6 cellules (4 bits + bordure)


def rgb(h):
    return tuple(int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))


def bits(mid):
    img = cv2.aruco.generateImageMarker(cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50), mid, 6)
    return img < 128  # 6x6, True = noir


def font(page, tmp, key):
    ttf = Path(tmp) / f"{key}.ttf"
    if not ttf.exists():
        f = TTFont(MEDIA / FONTS[key]); f.flavor = None; f.save(ttf)
    page.insert_font(fontname=key, fontfile=str(ttf))


def card(doc, tmp, mid, label, sub, accent):
    p = doc.new_page(width=W, height=H)
    for k in FONTS:
        font(p, tmp, k)
    p.draw_rect(p.rect, fill=rgb(BLUE), color=None)
    side = 8 * CELL  # marqueur + 1 cellule blanche de chaque côté
    x0, y0 = 30, (H - side) / 2
    p.draw_rect(pymupdf.Rect(x0, y0, x0 + side, y0 + side), fill=rgb(WHITE), color=None)
    p.draw_rect(pymupdf.Rect(x0 + CELL, y0 + CELL, x0 + 7 * CELL, y0 + 7 * CELL), fill=(0, 0, 0), color=None)
    for r, row in enumerate(bits(mid)):  # noir d'abord, puis les cellules blanches par-dessus (pas de joints)
        for c, black in enumerate(row):
            if not black:
                x, y = x0 + (c + 1) * CELL, y0 + (r + 1) * CELL
                p.draw_rect(pymupdf.Rect(x, y, x + CELL, y + CELL), fill=rgb(WHITE), color=None)
    tx = x0 + side + 40
    maxw = W - 30 - tx
    # barre d'accent + petit carré pixel
    p.draw_rect(pymupdf.Rect(tx, 150, tx + maxw, 160), fill=rgb(accent), color=None)
    p.draw_rect(pymupdf.Rect(tx + maxw - 22, 120, tx + maxw, 142), fill=rgb(accent), color=None)
    size = min(190, maxw / pymupdf.Font(fontfile=str(Path(tmp) / "anton.ttf")).text_length(label, 1))
    p.insert_text((tx, 190 + size * CAPH), label, fontname="anton", fontsize=size, color=rgb(accent))
    if sub:
        p.insert_textbox(pymupdf.Rect(tx, 190 + size * CAPH + 40, tx + maxw, H - 60), sub,
                         fontname="mono", fontsize=15, color=rgb(WHITE))
    p.insert_text((tx, H - 22), "{Epitech} La Réunion — Salon de l'Étudiant", fontname="mono", fontsize=8,
                  color=rgb(WHITE))
    p.insert_text((tx, H - 10), f"marqueur #{mid}", fontname="mono", fontsize=8, color=rgb(WHITE))


def main():
    doc = pymupdf.open()
    with tempfile.TemporaryDirectory() as tmp:
        for c in CARDS:
            card(doc, tmp, *c)
        out = ROOT / "cards/cartes-buddy.pdf"
        doc.save(out)
    print("écrit", out)


if __name__ == "__main__":
    main()
