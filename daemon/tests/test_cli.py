"""Tests for the daemon CLI argument parsing and entrypoint logic.

Covers argument parsing, --verbose log level, --regen-certs behaviour,
--port forwarding, and --no-tls flag.
"""

import logging
from unittest.mock import patch

import pytest
from main import _build_parser, _handle_regen_certs, main

class TestBuildParser:
    """Tests for _build_parser()."""

    def test_defaults(self):
        """Default args: TLS on, port 8776, no verbose, no regen, tray on."""
        args = _build_parser().parse_args([])
        assert args.no_tls is False
        assert args.port == 8776
        assert args.verbose is False
        assert args.regen_certs is False
        assert args.no_tray is False

    def test_no_tls_flag(self):
        """--no-tls should set no_tls to True."""
        args = _build_parser().parse_args(["--no-tls"])
        assert args.no_tls is True

    def test_port_flag(self):
        """--port should set a custom port."""
        args = _build_parser().parse_args(["--port", "9999"])
        assert args.port == 9999

    def test_port_flag_requires_value(self):
        """--port without a value should error."""
        with pytest.raises(SystemExit):
            _build_parser().parse_args(["--port"])

    def test_port_flag_rejects_non_integer(self):
        """--port with a non-integer should error."""
        with pytest.raises(SystemExit):
            _build_parser().parse_args(["--port", "abc"])

    def test_verbose_flag(self):
        """--verbose should set verbose to True."""
        args = _build_parser().parse_args(["--verbose"])
        assert args.verbose is True

    def test_regen_certs_flag(self):
        """--regen-certs should set regen_certs to True."""
        args = _build_parser().parse_args(["--regen-certs"])
        assert args.regen_certs is True

    def test_all_flags_combined(self):
        """All flags should work together."""
        args = _build_parser().parse_args(
            [
                "--no-tls",
                "--port",
                "1234",
                "--verbose",
                "--regen-certs",
                "--pair",
                "--no-tray",
            ]
        )
        assert args.no_tls is True
        assert args.port == 1234
        assert args.verbose is True
        assert args.regen_certs is True
        assert args.pair is True
        assert args.no_tray is True

    def test_unknown_flag_rejected(self):
        """Unknown flags should cause a parse error."""
        with pytest.raises(SystemExit):
            _build_parser().parse_args(["--unknown"])


class TestHandleRegenCerts:
    """Tests for _handle_regen_certs()."""

    def test_regen_deletes_existing_certs(self, tmp_path):
        """Existing cert directory should be removed before regeneration."""
        certs_dir = tmp_path / "certs"
        certs_dir.mkdir()
        (certs_dir / "old.crt").write_text("old cert")

        with patch("main.CERTS_DIR", certs_dir):
            with patch("main.generate_self_signed_cert") as mock_gen:
                _handle_regen_certs()

        assert not (certs_dir / "old.crt").exists()
        mock_gen.assert_called_once()

    def test_regen_works_when_no_existing_certs(self, tmp_path):
        """Should not fail if the certs directory doesn't exist yet."""
        certs_dir = tmp_path / "nonexistent"

        with patch("main.CERTS_DIR", certs_dir):
            with patch("main.generate_self_signed_cert") as mock_gen:
                _handle_regen_certs()

        mock_gen.assert_called_once()


class TestVerboseFlag:
    """Tests for --verbose log level behaviour."""

    def test_verbose_sets_debug_level(self):
        """--verbose should pass DEBUG level to logging.basicConfig."""
        async def noop(**kwargs):
            pass

        with patch("main.logging.basicConfig") as mock_basic:
            with patch("main.run_daemon", noop):
                with patch("sys.argv", ["main.py", "--verbose", "--no-tls"]):
                    main()

        mock_basic.assert_called_once()
        assert mock_basic.call_args.kwargs["level"] == logging.DEBUG

    def test_default_sets_info_level(self):
        """Default (no --verbose) should pass INFO level to logging.basicConfig."""
        async def noop(**kwargs):
            pass

        with patch("main.logging.basicConfig") as mock_basic:
            with patch("main.run_daemon", noop):
                with patch("sys.argv", ["main.py", "--no-tls"]):
                    main()

        mock_basic.assert_called_once()
        assert mock_basic.call_args.kwargs["level"] == logging.INFO


class TestPortFlag:
    """Tests for --port flag forwarding to run_daemon."""

    def test_port_forwarded_to_run_daemon(self):
        """--port value should be passed through to run_daemon()."""
        captured_port = {}

        async def fake_run_daemon(**kwargs):
            captured_port["port"] = kwargs["port"]

        with patch("main.run_daemon", fake_run_daemon):
            with patch("sys.argv", ["main.py", "--port", "4242", "--no-tls"]):
                main()

        assert captured_port["port"] == 4242


class TestNoTlsFlag:
    """Tests for --no-tls flag forwarding."""

    def test_no_tls_forwarded(self):
        """--no-tls should pass use_tls=False to run_daemon."""
        captured = {}

        async def fake_run_daemon(**kwargs):
            captured["use_tls"] = kwargs["use_tls"]

        with patch("main.run_daemon", fake_run_daemon):
            with patch("sys.argv", ["main.py", "--no-tls"]):
                main()

        assert captured["use_tls"] is False

    def test_default_tls_enabled(self):
        """Default (no --no-tls) should pass use_tls=True to run_daemon."""
        captured = {}

        async def fake_run_daemon(**kwargs):
            captured["use_tls"] = kwargs["use_tls"]

        with patch("main.run_daemon", fake_run_daemon):
            with patch("sys.argv", ["main.py"]):
                main()

        assert captured["use_tls"] is True


class TestPairFlag:
    """Tests for --pair flag forwarding to run_daemon."""

    def test_pair_flag_forwarded(self):
        """--pair should pass start_pairing=True to run_daemon."""
        captured = {}

        async def fake_run_daemon(**kwargs):
            captured["start_pairing"] = kwargs["start_pairing"]

        with patch("main.run_daemon", fake_run_daemon):
            with patch("sys.argv", ["main.py", "--pair", "--no-tls"]):
                main()

        assert captured["start_pairing"] is True

    def test_pair_flag_default_false(self):
        """Without --pair, start_pairing should default to False."""
        captured = {}

        async def fake_run_daemon(**kwargs):
            captured["start_pairing"] = kwargs["start_pairing"]

        with patch("main.run_daemon", fake_run_daemon):
            with patch("sys.argv", ["main.py", "--no-tls"]):
                main()

        assert captured["start_pairing"] is False
