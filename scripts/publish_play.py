#!/usr/bin/env python3
"""Publish one pre-verified Android App Bundle using the Play Edits API.

Authentication is deliberately external. The workflow supplies a short-lived
OIDC-derived access token through PLAY_ACCESS_TOKEN.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_ROOT = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
BLOCKING_STATUSES = {"draft", "inProgress", "halted"}


class PlayApiError(RuntimeError):
    pass


def _request(
    method: str,
    url: str,
    token: str,
    *,
    body: bytes | None = None,
    content_type: str = "application/json; charset=utf-8",
) -> dict[str, Any]:
    request = urllib.request.Request(url, data=body, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    if body is not None:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")[:4000]
        raise PlayApiError(f"Play API returned HTTP {error.code}: {details}") from error
    except urllib.error.URLError as error:
        raise PlayApiError(f"Could not reach Play API: {error.reason}") from error

    if not payload:
        return {}
    try:
        return json.loads(payload)
    except json.JSONDecodeError as error:
        raise PlayApiError("Play API returned malformed JSON.") from error


def _json_bytes(value: dict[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def _release_version_codes(release: dict[str, Any]) -> set[str]:
    return {str(code) for code in release.get("versionCodes", [])}


def build_track_update(
    current_track: dict[str, Any],
    *,
    track: str,
    version_code: int,
    version_name: str,
    rollout: float,
    notes: str,
) -> dict[str, Any]:
    """Build a conservative track update without overwriting active rollouts."""
    releases = current_track.get("releases", [])
    blocking = [release for release in releases if release.get("status") in BLOCKING_STATUSES]
    if blocking:
        statuses = ", ".join(str(release.get("status")) for release in blocking)
        raise ValueError(
            "The target track already has an unfinished release "
            f"({statuses}). Resolve it in Play Console before deploying."
        )

    code = str(version_code)
    if any(code in _release_version_codes(release) for release in releases):
        raise ValueError(f"Version code {version_code} already exists on track {track}.")

    new_release: dict[str, Any] = {
        "name": f"{version_name} ({version_code})",
        "versionCodes": [code],
        "releaseNotes": [{"language": "en-US", "text": notes}],
    }

    if track == "production" and rollout < 1.0:
        new_release["status"] = "inProgress"
        new_release["userFraction"] = rollout
        retained = [release for release in releases if release.get("status") == "completed"]
        return {"track": track, "releases": [*retained, new_release]}

    new_release["status"] = "completed"
    return {"track": track, "releases": [new_release]}


class PlayPublisher:
    def __init__(self, package: str, token: str) -> None:
        self.package = urllib.parse.quote(package, safe="")
        self.token = token

    def _url(self, suffix: str) -> str:
        return f"{API_ROOT}/applications/{self.package}/{suffix}"

    def create_edit(self) -> str:
        response = _request("POST", self._url("edits"), self.token)
        edit_id = response.get("id")
        if not edit_id:
            raise PlayApiError("Play API did not return an edit ID.")
        return str(edit_id)

    def upload_bundle(self, edit_id: str, bundle: Path) -> int:
        edit = urllib.parse.quote(edit_id, safe="")
        url = (
            f"{UPLOAD_ROOT}/applications/{self.package}/edits/{edit}/bundles"
            "?uploadType=media"
        )
        response = _request(
            "POST",
            url,
            self.token,
            body=bundle.read_bytes(),
            content_type="application/octet-stream",
        )
        try:
            return int(response["versionCode"])
        except (KeyError, TypeError, ValueError) as error:
            raise PlayApiError("Play API did not return the uploaded version code.") from error

    def get_track(self, edit_id: str, track: str) -> dict[str, Any]:
        edit = urllib.parse.quote(edit_id, safe="")
        track_name = urllib.parse.quote(track, safe="")
        return _request(
            "GET", self._url(f"edits/{edit}/tracks/{track_name}"), self.token
        )

    def update_track(self, edit_id: str, track: str, body: dict[str, Any]) -> None:
        edit = urllib.parse.quote(edit_id, safe="")
        track_name = urllib.parse.quote(track, safe="")
        _request(
            "PUT",
            self._url(f"edits/{edit}/tracks/{track_name}"),
            self.token,
            body=_json_bytes(body),
        )

    def validate_edit(self, edit_id: str) -> None:
        edit = urllib.parse.quote(edit_id, safe="")
        _request("POST", self._url(f"edits/{edit}:validate"), self.token)

    def commit_edit(self, edit_id: str) -> None:
        edit = urllib.parse.quote(edit_id, safe="")
        _request(
            "POST",
            self._url(
                f"edits/{edit}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
            ),
            self.token,
        )

    def delete_edit(self, edit_id: str) -> None:
        edit = urllib.parse.quote(edit_id, safe="")
        _request("DELETE", self._url(f"edits/{edit}"), self.token)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--track", required=True, choices=("internal", "production"))
    parser.add_argument("--rollout", required=True, type=float)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--release-notes", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    token = os.environ.get("PLAY_ACCESS_TOKEN", "")
    if not token:
        raise ValueError("PLAY_ACCESS_TOKEN is missing.")
    if not args.bundle.is_file():
        raise ValueError(f"Bundle does not exist: {args.bundle}")
    if not 0 < args.rollout <= 1:
        raise ValueError("Rollout must be greater than 0 and no greater than 1.")
    if args.track == "internal" and args.rollout != 1:
        raise ValueError("Internal releases must use rollout 1.0.")

    notes = args.release_notes.read_text(encoding="utf-8").strip()
    if not notes:
        raise ValueError("Release notes are empty.")
    if len(notes) > 500:
        raise ValueError("Release notes exceed the Google Play 500-character limit.")

    publisher = PlayPublisher(args.package, token)
    edit_id: str | None = None
    committed = False
    try:
        edit_id = publisher.create_edit()
        uploaded_code = publisher.upload_bundle(edit_id, args.bundle)
        if uploaded_code != args.version_code:
            raise PlayApiError(
                f"Uploaded version code {uploaded_code} does not match expected "
                f"{args.version_code}."
            )

        current_track = publisher.get_track(edit_id, args.track)
        update = build_track_update(
            current_track,
            track=args.track,
            version_code=args.version_code,
            version_name=args.version_name,
            rollout=args.rollout,
            notes=notes,
        )
        publisher.update_track(edit_id, args.track, update)
        publisher.validate_edit(edit_id)
        publisher.commit_edit(edit_id)
        committed = True
    finally:
        if edit_id and not committed:
            try:
                publisher.delete_edit(edit_id)
            except PlayApiError as cleanup_error:
                print(f"Warning: could not delete failed edit: {cleanup_error}", file=sys.stderr)

    print(
        f"Published version {args.version_name} ({args.version_code}) "
        f"to {args.track}."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, PlayApiError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        raise SystemExit(1)
