#!/usr/bin/env python3
"""
Keevo E2E Test Runner — runs all story test scripts in order.

Usage:
  python3 run_all.py
  python3 run_all.py --base-url http://staging.example.com
  python3 run_all.py --story 1-4
  python3 run_all.py --verbose
"""

import os
import sys
import subprocess
import argparse
import time

HERE = os.path.dirname(__file__)

# Map story ID → (script filename, accepts --base-url flag)
STORIES = {
    "1-4": ("story_1_4_onboarding.py", True),
    "1-6": ("e2e-story-1-6.py",        False),
    "1-7": ("e2e-story-1-7.py",        True),
}


def main():
    parser = argparse.ArgumentParser(description="Keevo E2E test runner")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="Backend base URL")
    parser.add_argument("--story", default=None,
                        help="Run only a specific story (e.g., 1-4)")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    scripts = (
        {args.story: STORIES[args.story]}
        if args.story and args.story in STORIES
        else STORIES
    )

    if args.story and args.story not in STORIES:
        print(f"Story '{args.story}' inconnue. Stories disponibles: {list(STORIES.keys())}")
        sys.exit(1)
    results = {}
    for story_id, (script, supports_base_url) in scripts.items():
        path = os.path.join(HERE, script)
        cmd = [sys.executable, path]
        if supports_base_url:
            cmd += ["--base-url", args.base_url]
        if args.verbose and supports_base_url:
            cmd.append("--verbose")

        print(f"\n{'=' * 58}")
        print(f"  Story {story_id}: {script}")
        print(f"{'=' * 58}")
        start = time.time()
        r = subprocess.run(cmd)
        elapsed = time.time() - start
        results[story_id] = (r.returncode == 0, elapsed)

    print(f"\n{'=' * 58}")
    print("  RÉSUMÉ GLOBAL")
    print(f"{'=' * 58}")
    all_ok = True
    for sid, (passed, elapsed) in results.items():
        status = "✓ OK" if passed else "✗ FAIL"
        print(f"  {status}  Story {sid}  ({elapsed:.1f}s)")
        if not passed:
            all_ok = False
    print(f"{'=' * 58}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
