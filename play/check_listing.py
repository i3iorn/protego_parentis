# -*- coding: utf-8 -*-
"""
Checks the store text against Play's limits before the Console does.

The Console truncates or refuses without saying which field was the problem, and
finding out by trial and error means several trips through a form that also holds
the answers to thirty other questions. This reads the fenced blocks straight out
of the listing files and measures them.

    python play/check_listing.py

Exits non-zero if anything is over, so it can go in a pre-release check.
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Play's limits, in characters. Emoji count as one each here and Play agrees;
# where it does not, it is stricter, so being a little conservative is right.
LIMITS = [
    ('App name', 30),
    ('Short description', 80),
    ('Full description', 4000),
]

# The heading carries the limit in brackets in one language and the other, so the
# match is on the leading words rather than the whole line.
HEADINGS = {
    'App name': ('app name', 'appnamn'),
    'Short description': ('short description', 'kort beskrivning'),
    'Full description': ('full description', 'fullständig beskrivning'),
}


def blocks(path):
    """Every fenced block in the file, keyed by the heading above it."""
    text = io.open(path, encoding='utf-8').read()
    found = {}
    current = None
    in_block = False
    buffer = []

    for line in text.splitlines():
        if line.startswith('##'):
            heading = line.lstrip('#').strip().lower()
            current = next(
                (name for name, aliases in HEADINGS.items()
                 if any(heading.startswith(alias) for alias in aliases)),
                None,
            )
        elif line.strip() == '```':
            if in_block:
                if current:
                    found[current] = '\n'.join(buffer)
                buffer = []
            in_block = not in_block
        elif in_block:
            buffer.append(line)

    return found


def main():
    ok = True
    for filename in sorted(os.listdir(HERE)):
        if not filename.startswith('listing-'):
            continue

        print(filename)
        found = blocks(os.path.join(HERE, filename))

        for field, limit in LIMITS:
            value = found.get(field)
            if value is None:
                print('  %-20s MISSING' % field)
                ok = False
                continue

            length = len(value)
            state = 'ok' if length <= limit else 'OVER BY %d' % (length - limit)
            if length > limit:
                ok = False
            print('  %-20s %5d / %-5d %s' % (field, length, limit, state))
        print()

    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
