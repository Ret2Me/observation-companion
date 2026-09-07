import unittest

from publish_play import build_track_update


class BuildTrackUpdateTest(unittest.TestCase):
    def test_internal_release_replaces_completed_release(self) -> None:
        result = build_track_update(
            {"track": "internal", "releases": [{"status": "completed", "versionCodes": ["1"]}]},
            track="internal",
            version_code=2,
            version_name="1.3",
            rollout=1.0,
            notes="Release notes",
        )

        self.assertEqual(result["track"], "internal")
        self.assertEqual(result["releases"][0]["status"], "completed")
        self.assertEqual(result["releases"][0]["versionCodes"], ["2"])
        self.assertNotIn("userFraction", result["releases"][0])

    def test_staged_production_release_retains_completed_release(self) -> None:
        old_release = {"status": "completed", "versionCodes": ["1"]}
        result = build_track_update(
            {"track": "production", "releases": [old_release]},
            track="production",
            version_code=2,
            version_name="1.3",
            rollout=0.1,
            notes="Release notes",
        )

        self.assertEqual(result["releases"][0], old_release)
        self.assertEqual(result["releases"][1]["status"], "inProgress")
        self.assertEqual(result["releases"][1]["userFraction"], 0.1)

    def test_existing_rollout_is_never_overwritten(self) -> None:
        for status in ("draft", "inProgress", "halted"):
            with self.subTest(status=status), self.assertRaises(ValueError):
                build_track_update(
                    {"track": "production", "releases": [{"status": status}]},
                    track="production",
                    version_code=2,
                    version_name="1.3",
                    rollout=1.0,
                    notes="Release notes",
                )

    def test_duplicate_version_code_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            build_track_update(
                {"track": "internal", "releases": [{"status": "completed", "versionCodes": ["2"]}]},
                track="internal",
                version_code=2,
                version_name="1.3",
                rollout=1.0,
                notes="Release notes",
            )


if __name__ == "__main__":
    unittest.main()
