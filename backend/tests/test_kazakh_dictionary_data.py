import hashlib
from pathlib import Path

DATA_DIRECTORY = Path(__file__).resolve().parents[1] / "app" / "data"
DICTIONARY_SHA256 = "80090f69c0d098425020ab378084d05ec7a4a90155750faf73742cdde7088012"
AFFIX_SHA256 = "254293c1c6ae893b87ec5c1fea3b72f696fe7821a3d87740ebad86b780d6e33a"


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_dictionary_files_are_present():
    assert (DATA_DIRECTORY / "kk_KZ.dic").is_file()
    assert (DATA_DIRECTORY / "kk_KZ.aff").is_file()


def test_dictionary_files_are_unmodified():
    # The data is vendored byte-for-byte so its provenance stays checkable.
    # A changed digest means the file was edited or line endings were normalized.
    assert file_digest(DATA_DIRECTORY / "kk_KZ.dic") == DICTIONARY_SHA256
    assert file_digest(DATA_DIRECTORY / "kk_KZ.aff") == AFFIX_SHA256


def test_license_file_ships_with_the_data():
    license_text = (DATA_DIRECTORY / "LICENSE-kk_KZ.txt").read_text(encoding="utf-8")
    assert "Mozilla Public License version 1.1" in license_text
    assert "taem/hunspell-kk" in license_text
