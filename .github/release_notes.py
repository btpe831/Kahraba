"""Writes the release page text: what's new since the last release, how to install, the key.

Also says whether to publish: only when the app version changed since the last release, so a
build that doesn't change the version (e.g. a change to this script) adds no duplicate release.
Usage: release_notes.py <version> <notes file> <github output file>
"""
import re
import subprocess
import sys

version, notes_path, output_path = sys.argv[1:4]


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.strip()


# The last release before this commit (HEAD^, so a re-run of this build doesn't find its own tag).
last_tag = git("describe", "--tags", "--abbrev=0", "HEAD^")
last_version = re.sub(r"^v|-build-\d+$", "", last_tag) if last_tag else ""
publish = last_version != version

# Each change since the last release: its title, and its description without the trailer lines.
log = git("log", "--no-merges", "--format=%s%x1f%b%x1e", f"{last_tag}..HEAD" if last_tag else "-1")
changes = []
for entry in filter(None, (e.strip() for e in log.split("\x1e"))):
    subject, _, body = entry.partition("\x1f")
    subject = re.sub(r"\s*\(\d+\.\d+\.\d+\)$", "", subject.strip())
    lines = [l for l in body.splitlines() if not re.match(r"(Co-Authored-By|Claude-Session|Signed-off-by):", l, re.I)]
    changes.append((subject, "\n".join(lines).strip()))

# A hand-written note for a version (e.g. the first public one) replaces the list of changes.
import os
written = os.path.join(os.path.dirname(os.path.abspath(__file__)), "notes", f"{version}.md")
if os.path.exists(written):
    parts = [open(written).read().strip(), ""]
else:
    parts = [f"## What's new in {version}", ""]
    for subject, body in changes:
        parts += [f"### {subject}", ""] + ([body, ""] if body else [])
parts += [
    "## Install",
    "",
    "On the Android phone, tap the `.apk` file below, then allow \"Install unknown apps\" if asked.",
    "",
    "When **Google Play Protect** shows \"App scan recommended\", tap **More details**, then **Install anyway**. "
    "It shows this because the app is new and can run in the background (for the always-on monitor). "
    "Updates install over the previous version and keep your data.",
    "",
    "Signing certificate SHA-256: "
    "`77:E9:3F:A7:E7:C9:4C:4E:53:04:DA:05:50:BC:84:44:26:94:97:7A:96:50:E5:2D:92:6B:AB:F0:FE:5F:67:76`",
]
open(notes_path, "w").write("\n".join(parts) + "\n")
with open(output_path, "a") as out:
    out.write(f"publish={'true' if publish else 'false'}\n")
print(f"Last release: {last_tag or 'none'}; this version: {version}; publish: {publish}")
print("\n".join(parts))
