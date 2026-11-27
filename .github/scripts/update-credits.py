#!/usr/bin/env python3
"""Adds new people to the credits: `Credits` in SupportScreen.kt (Settings › Contributors and supporters) and the
"Support Kyllene" part of the README.

    update-credits.py <contributors file> <sponsors file>

Each file holds GitHub logins, one per line, in the order GitHub gives them. A login not yet on its list is added at
the end, so no one moves and no one is dropped. Maintainers are never added, and neither is anyone in LEAVE_OFF: add
a login there when its owner asks to be taken off the page, or the next run adds them back. Both files are then
rewritten from `Credits`, so they always match. Prints a Markdown list of who was added.
"""

import re
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/settings/SupportScreen.kt"
README = ROOT / "README.md"
LEAVE_OFF = {"claude"}
WIDTH = 120
LOGIN = re.compile(r"^[A-Za-z0-9-]+$")


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8", newline="\n")


def read_logins(path):
    # jq prints "null" for a sponsor whose account is gone; it's no one to credit.
    logins = (line.strip() for line in read(Path(path)).splitlines())
    return [login for login in logins if LOGIN.match(login) and login != "null"]


def kotlin_list_pattern(name):
    return re.compile(rf"^    val {name} = (?:listOf(?:<String>)?\([^)]*\)|emptyList(?:<String>)?\(\))", re.M)


def current(source, name):
    match = kotlin_list_pattern(name).search(source)
    if not match:
        sys.exit(f"Credits.{name} not found in {KOTLIN}")
    return re.findall(r'"([A-Za-z0-9-]+)"', match.group(0))


def kotlin_list(name, logins):
    if not logins:
        return f"    val {name} = listOf<String>()"
    items = [f'"{login}"' for login in logins]
    one_line = f"    val {name} = listOf({', '.join(items)})"
    if len(one_line) <= WIDTH:
        return one_line
    lines, line = [], ""
    for item in items:
        candidate = f"{line} {item}," if line else f"        {item},"
        if len(candidate) > WIDTH:
            lines.append(line)
            candidate = f"        {item},"
        line = candidate
    lines.append(line)
    return f"    val {name} = listOf(\n" + "\n".join(lines) + "\n    )"


def links(logins):
    if not logins:
        return "none yet"
    linked = [f"[{login}](https://github.com/{login})" for login in logins]
    return linked[0] if len(linked) == 1 else ", ".join(linked[:-1]) + " and " + linked[-1]


def paragraph(label, text):
    return textwrap.fill(f"**{label}:** {text}", WIDTH, break_long_words=False, break_on_hyphens=False)


def replace_paragraph(readme, label, new):
    pattern = re.compile(rf"^\*\*{label}:\*\* .*?(?=\n\n)", re.M | re.S)
    if not pattern.search(readme):
        sys.exit(f'"**{label}:**" not found in {README}')
    return pattern.sub(lambda _: new, readme, count=1)


def added(have, found, skip):
    known = {login.lower() for login in have} | {login.lower() for login in skip}
    new = []
    for login in found:
        if login.lower() not in known:
            known.add(login.lower())
            new.append(login)
    return new


def main(contributors_file, sponsors_file):
    source = read(KOTLIN)
    maintainers = current(source, "maintainers")
    contributors = current(source, "contributors")
    supporters = current(source, "supporters")

    left_off = set(maintainers) | LEAVE_OFF
    new_contributors = added(contributors, read_logins(contributors_file), left_off)
    new_supporters = added(supporters, read_logins(sponsors_file), left_off)
    contributors += new_contributors
    supporters += new_supporters

    for name, logins in (("contributors", contributors), ("supporters", supporters)):
        source = kotlin_list_pattern(name).sub(lambda _: kotlin_list(name, logins), source, count=1)
    readme = read(README)
    readme = replace_paragraph(readme, "Contributors", paragraph("Contributors", links(contributors)))
    thanks = links(supporters) + ". Thank you!" if supporters else "none yet."
    readme = replace_paragraph(readme, "Supporters", paragraph("Supporters", thanks))
    write(KOTLIN, source)
    write(README, readme)

    for label, logins in (("Contributors", new_contributors), ("Supporters", new_supporters)):
        if logins:
            print(f"**{label}:** " + ", ".join(f"[{login}](https://github.com/{login})" for login in logins))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
