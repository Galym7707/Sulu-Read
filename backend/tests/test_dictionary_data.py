import hashlib
from pathlib import Path

DATA_DIRECTORY = Path(__file__).resolve().parents[1] / "app" / "data"

KK_DICTIONARY_SHA256 = "80090f69c0d098425020ab378084d05ec7a4a90155750faf73742cdde7088012"
KK_AFFIX_SHA256 = "254293c1c6ae893b87ec5c1fea3b72f696fe7821a3d87740ebad86b780d6e33a"
RU_DICTIONARY_SHA256 = "f6047416a0204adbecf3a451b874ec8a97ee37e2cbc714466ef04d8dbcc0d6fc"
RU_AFFIX_SHA256 = "38ce7d4af78e211e9bafe4bf7e3d6a2c420591136cb738ec6648f8fdf6524cd7"


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_dictionary_files_are_present():
    assert (DATA_DIRECTORY / "kk_KZ.dic").is_file()
    assert (DATA_DIRECTORY / "kk_KZ.aff").is_file()
    assert (DATA_DIRECTORY / "ru_RU.dic").is_file()
    assert (DATA_DIRECTORY / "ru_RU.aff").is_file()


def test_kazakh_dictionary_files_are_unmodified():
    # The data is vendored byte-for-byte so its provenance stays checkable.
    # A changed digest means the file was edited or line endings were normalized.
    assert file_digest(DATA_DIRECTORY / "kk_KZ.dic") == KK_DICTIONARY_SHA256
    assert file_digest(DATA_DIRECTORY / "kk_KZ.aff") == KK_AFFIX_SHA256


def test_russian_dictionary_files_are_unmodified():
    assert file_digest(DATA_DIRECTORY / "ru_RU.dic") == RU_DICTIONARY_SHA256
    assert file_digest(DATA_DIRECTORY / "ru_RU.aff") == RU_AFFIX_SHA256


def test_kazakh_license_file_ships_with_the_data():
    license_text = (DATA_DIRECTORY / "LICENSE-kk_KZ.txt").read_text(encoding="utf-8")
    assert "Mozilla Public License version 1.1" in license_text
    assert "taem/hunspell-kk" in license_text


def test_russian_license_file_ships_with_the_data():
    license_text = (DATA_DIRECTORY / "LICENSE-ru_RU.txt").read_text(encoding="utf-8")
    assert "BSD-3-Clause" in license_text
    assert "wooorm/dictionaries" in license_text
