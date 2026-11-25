#!/usr/bin/env python3
"""Fetch crash reports published by protego_parentis phones.

Inputs: the family code (DW1-...), the family passphrase, and ntfy
credentials. The passphrase never leaves this machine: it is hashed (HMAC-
SHA256, base32) into the diagnostics topic name, so the topic itself is a
capability only a passphrase holder can derive. The crash reports are read
from that topic with the ntfy account given.

Examples:
    python tools/crash_cli.py --code DW1-abcdef --passphrase '...' \\
        --user protego --password-file ~/.secrets/protego-ntfy.token --once
    python tools/crash_cli.py ... --follow
"""

import argparse
import base64
import hmac
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request

DEFAULT_HOST = "ntfy.schrammel.dev"


def derive_topic(code: str, passphrase: str) -> str:
    """The diagnostics topic for a family: code plus an HMAC of the code keyed
    by the passphrase. A wrong passphrase yields a valid-looking but empty
    topic rather than an error, which is the same fail-soft the app's own
    stores use for a wrong key."""
    digest = hmac.new(passphrase.encode("utf-8"), code.strip().encode("utf-8"), hashlib.sha256).digest()
    alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    bits = ""
    for byte in digest:
        bits += format(byte, "08b")
    encoded = "".join(alphabet[int(bits[i : i + 5], 2)] for i in range(0, 80, 5))
    return f"dementor-{code.strip()}-diag-{encoded[:12]}"


def derive_family_topic(family_topic: str, passphrase: str) -> str:
    """Derive the diagnostics topic from the app's configured sync family topic.

    Mirrors SyncConfigStore.suggestTopic() + HMAC-SHA256(code, passphrase):
        <family-topic>-diag-<12 base32 of SHA256(passphrase, code)>

    The 'dementor-' prefix is prepended because that is how the app names its
    diagnostics topic (see Diagnostics.record / CrashReportPublisher).
    """
    family_topic = family_topic.strip()
    if not family_topic.startswith("dementor-"):
        raise ValueError(
            f"--family-topic must start with 'dementor-', got {family_topic!r}",
        )
    code = family_topic[len("dementor-"):].strip()
    digest = hmac.new(passphrase.encode("utf-8"), code.encode("utf-8"), hashlib.sha256).digest()
    alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    bits = ""
    for byte in digest:
        bits += format(byte, "08b")
    encoded = "".join(alphabet[int(bits[i : i + 5], 2)] for i in range(0, 80, 5))
    return f"dementor-{code}-diag-{encoded[:12]}"


def authorization(user: str, password: str) -> str:
    if password.startswith("tk_"):
        return f"Bearer {password}"
    raw = f"{user}:{password}".encode("utf-8")
    return "Basic " + base64.b64encode(raw).decode("utf-8")


def fetch(host: str, topic: str, auth: str, since: str):
    url = f"https://{host}/{topic}/json?poll=1&since={since}"
    request = urllib.request.Request(url, headers={"Authorization": auth})
    with urllib.request.urlopen(request, timeout=30) as response:
        for line in response:
            line = line.strip()
            if line:
                yield json.loads(line)


def show(message: dict) -> None:
    if message.get("event") not in ("message", "keepalive"):
        return
    if message.get("event") == "keepalive":
        return
    body = message.get("message", "")
    try:
        report = json.loads(body)
    except json.JSONDecodeError:
        report = None
    when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(message.get("time", 0)))
    if isinstance(report, dict) and "crash" in report:
        print(f"--- crash at {when} ---")
        print(f"device : {report.get('device', '?')}")
        print(f"app    : {report.get('app', '?')} versionCode {report.get('versionCode', '?')}")
        print(f"thread : {report.get('thread', '?')}")
        print(report.get("trace", "(no trace recorded)"))
        print()
    else:
        print(f"[{when}] (non-crash message on this topic)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", help="family code, e.g. DW1-abcdef")
    parser.add_argument(
        "--topic",
        help="exact diagnostics topic (used verbatim; must already carry the -diag- suffix)",
    )
    parser.add_argument(
        "--family-topic",
        help="the app's configured sync topic (e.g. dementor-59e5...); the crash "
        "topic is derived from it + --passphrase exactly as the app does",
    )
    parser.add_argument("--passphrase", help="family passphrase")
    parser.add_argument("--passphrase-file", help="file holding the family passphrase")
    parser.add_argument("--user", required=True, help="ntfy username")
    parser.add_argument(
        "--password",
        help="ntfy password or token (tk_...); prefer --password-file or the"
        " NTFY_PASSWORD environment variable so it is not in shell history",
    )
    parser.add_argument("--password-file", help="file holding the ntfy password/token")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--once", action="store_true", help="poll once and exit")
    parser.add_argument("--follow", action="store_true", help="keep polling every 30 s")
    parser.add_argument("--since", default="7200", help="poll window in seconds")
    args = parser.parse_args()

    password = args.password
    if not password and args.password_file:
        with open(args.password_file, "r", encoding="utf-8") as handle:
            password = handle.read().strip()
    if not password:
        import getpass

        password = getpass.getpass("ntfy password/token: ")

    passphrase = args.passphrase
    if not passphrase and args.passphrase_file:
        with open(args.passphrase_file, "r", encoding="utf-8") as handle:
            passphrase = handle.read().strip()
    if not passphrase:
        import getpass
        passphrase = getpass.getpass("family passphrase: ")

    topic = (
        args.topic
        or (derive_family_topic(args.family_topic, passphrase) if args.family_topic else None)
        or derive_topic(args.code, passphrase)
    )
    auth = authorization(args.user, password)
    url = f"https://{args.host}/{topic}/json?poll=1&since={args.since}"
    print(f"topic: {topic}", file=sys.stderr)
    print(f"querying: {url}", file=sys.stderr)

    while True:
        try:
            message_count = 0
            for message in fetch(args.host, topic, auth, args.since):
                message_count += 1
                show(message)
                if message.get("id"):
                    args.since = message["id"]
            if args.once and message_count == 0:
                print(f"no crash reports found for topic '{topic}' "
                      f"({message_count} poll cycles, since={args.since}) — "
                      f"check sync enablement and passphrase, or use "
                      f"--code + --passphrase to derive the diagnostics topic",
                      file=sys.stderr)
        except urllib.error.HTTPError as error:
            try:
                error_body = error.read().decode("utf-8", errors="replace")
            except Exception:
                error_body = "(unreadable error body)"
            print(f"ntfy returned {error.code} for topic {topic}", file=sys.stderr)
            print(f"ntfy message: {error_body}", file=sys.stderr)
            if error.code == 401:
                print(
                    "ntfy returned 401: the credentials or topic do not match. "
                    "Check the ntfy user/token and that the topic is the one this "
                    "family publishes crashes to.",
                    file=sys.stderr,
                )
            if args.once:
                return 1
        except urllib.error.URLError as error:
            print(f"cannot reach {args.host}: {error.reason}", file=sys.stderr)
            if args.once:
                return 1
        if args.once:
            return 0
        if not args.follow:
            return 0
        time.sleep(30)


if __name__ == "__main__":
    sys.exit(main())
