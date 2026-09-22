"""Record a real run of scripts/demo.sh and render it as an animated terminal GIF for the README.

Nothing is simulated: the script runs the demo against the local stack, timestamps every output line, and draws
the frames from that transcript. The plain-text transcript is saved next to the GIF as evidence.

Usage (stack running, decision-service with the lab profile, JVM warmed up):
    python scripts/record-demo.py            -> docs/media/demo.gif + docs/media/demo-transcript.txt
"""
import datetime
import os
import re
import subprocess
import sys
import time

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs", "media")
COLS, ROWS = 118, 34
FONT_PATHS = ["C:/Windows/Fonts/consola.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"]
FONT_SIZE = 14
BG, FG, DIM, HEAD, OK, WARN, TITLE_BG = (13, 17, 23), (201, 209, 217), (125, 133, 144), (88, 166, 255), (63, 185, 80), (210, 153, 34), (22, 27, 34)
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def record():
    env = dict(os.environ, DEMO_PAUSE="0", PYTHONIOENCODING="utf-8")
    # On Windows, "bash" may resolve to WSL (no Docker socket, no python): use Git Bash explicitly.
    git_bash = "C:/Program Files/Git/bin/bash.exe"
    bash = git_bash if os.name == "nt" and os.path.exists(git_bash) else "bash"
    proc = subprocess.Popen([bash, "scripts/demo.sh"], cwd=ROOT, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    start, lines = time.time(), []
    for raw in proc.stdout:
        line = ANSI.sub("", raw.rstrip("\n")).replace("\t", "    ")
        if "CRLF will be replaced" in line:
            continue
        lines.append((time.time() - start, line))
        print(line, flush=True)
    proc.wait()
    return lines, proc.returncode


def colour(line):
    if line.startswith("==================="):
        return HEAD
    if line.startswith("$ ") or line.startswith("#"):
        return DIM
    if re.search(r"\b(DECLINE|failed=[1-9]|REJECTED|CIRCUIT|OPEN\)|DEVICE_RISK_UNAVAILABLE)", line):
        return WARN
    if re.search(r"\b(APPROVE|COMPLETED|200|201|t$|OPEN|True)\b", line):
        return OK
    return FG


def render(lines, gif_path):
    font = next((ImageFont.truetype(p, FONT_SIZE) for p in FONT_PATHS if os.path.exists(p)), ImageFont.load_default())
    cw = font.getbbox("M")[2]
    ch = FONT_SIZE + 4
    width, height = cw * COLS + 24, ch * (ROWS + 2) + 20
    title = f" fraud-platform · scripts/demo.sh · real run {datetime.date.today().isoformat()} · synthetic data "

    def frame(visible):
        img = Image.new("RGB", (width, height), BG)
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, width, ch + 8], fill=TITLE_BG)
        for i, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
            d.ellipse([10 + i * 18, 7, 22 + i * 18, 19], fill=c)
        d.text((70, 5), title, font=font, fill=DIM)
        y = ch + 14
        for text in visible[-ROWS:]:
            d.text((12, y), text[:COLS], font=font, fill=colour(text))
            y += ch
        return img.quantize(colors=16, method=Image.Quantize.MEDIANCUT)

    frames, durations = [], []
    frames.append(frame(["# Real-time fraud decision platform - end-to-end demo", "#",
                         "# Every line below is the actual output of scripts/demo.sh against the local stack:",
                         "# decision + reasons -> SHAP explanation -> events and case creation -> configuration change",
                         "# with four-eyes approval and rollback -> legacy file ingestion -> slow-vendor troubleshooting",
                         "# -> performance.  Synthetic data, fictional customers, one laptop."]))
    durations.append(4500)
    visible, i = [], 0
    while i < len(lines):
        t0 = lines[i][0]
        chunk = []
        while i < len(lines) and lines[i][0] - t0 < 0.25:   # lines printed together appear together
            chunk.append(lines[i][1]); i += 1
        visible.extend(chunk)
        gap = (lines[i][0] - t0) if i < len(lines) else 3.0
        pause = 2600 if any(c.startswith("===") for c in chunk) else 0
        frames.append(frame(visible))
        durations.append(int(min(max(gap * 1000 * 0.6, 350), 2200)) + pause)
    durations[-1] = 6000
    frames[0].save(gif_path, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)
    return len(frames), sum(durations) / 1000


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    lines, rc = record()
    errors = [l for _, l in lines if re.search(r"command not found|failed to connect to the docker API|Traceback", l)]
    if rc != 0 or errors:
        print(f"recording rejected (exit {rc}); first error: {errors[:1]}", file=sys.stderr)
        return 1
    with open(os.path.join(OUT_DIR, "demo-transcript.txt"), "w", encoding="utf-8") as f:
        f.write(f"# scripts/demo.sh — real run on {datetime.datetime.now().isoformat(timespec='seconds')} (exit code {rc})\n")
        f.write("# seconds-since-start | output\n")
        for t, line in lines:
            f.write(f"{t:7.2f} | {line}\n")
    n, secs = render(lines, os.path.join(OUT_DIR, "demo.gif"))
    size = os.path.getsize(os.path.join(OUT_DIR, "demo.gif")) / 1e6
    print(f"\nexit code {rc}; GIF: {n} frames, {secs:.0f} s, {size:.1f} MB", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
