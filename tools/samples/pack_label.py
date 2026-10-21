#!/usr/bin/env python3
"""Packs up to four characters (space, A-Z, 0-9 and a few marks) into one
GLSL int, five bits each, for samples that draw short labels with the
built-in font: code = c0 + c1 * 32 + c2 * 1024 + c3 * 32768, where 0 is a
space, 1-26 are A-Z, 27-31 are '+', '-', '#', '.' and ':'. Kept under 2^24,
which some phone GPUs can't hold exactly in a shader constant.

The matching GLSL decode is `labelChar()` in the samples that use it.

Usage: pack_label.py KICK SNAR ...
"""
import sys

EXTRA = {'+': 27, '-': 28, '#': 29, '.': 30, ':': 31}


def pack(word):
    word = word.upper()[:4].ljust(4)
    code = 0
    for i, ch in enumerate(word):
        if ch == ' ':
            v = 0
        elif 'A' <= ch <= 'Z':
            v = ord(ch) - 64
        elif ch in EXTRA:
            v = EXTRA[ch]
        else:
            raise ValueError(ch)
        code += v << (5 * i)
    return code


if __name__ == '__main__':
    for w in sys.argv[1:]:
        print(w, pack(w))
