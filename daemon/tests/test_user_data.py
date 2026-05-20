"""Factory reset of local Jibe state."""

from jibe.core.user_data import reset_user_data


def test_reset_user_data_removes_database_and_cache(tmp_path, monkeypatch):
    data_dir = tmp_path / "share" / "jibe"
    cache_dir = tmp_path / "cache" / "jibe"
    data_dir.mkdir(parents=True)
    cache_dir.mkdir(parents=True)
    (data_dir / "jibe.db").write_text("test")
    (cache_dir / "transfers").mkdir()

    monkeypatch.setattr("jibe.core.user_data.DATABASE_DIR", data_dir)
    monkeypatch.setattr("jibe.core.user_data.JIBE_CACHE_DIR", cache_dir)

    reset_user_data(assume_yes=True)

    assert not data_dir.exists()
    assert not cache_dir.exists()


def test_reset_user_data_noop_when_missing(tmp_path, monkeypatch, caplog):
    import logging

    data_dir = tmp_path / "share" / "jibe"
    cache_dir = tmp_path / "cache" / "jibe"
    monkeypatch.setattr("jibe.core.user_data.DATABASE_DIR", data_dir)
    monkeypatch.setattr("jibe.core.user_data.JIBE_CACHE_DIR", cache_dir)

    with caplog.at_level(logging.INFO):
        reset_user_data(assume_yes=True)

    assert "nothing to reset" in caplog.text.lower()
