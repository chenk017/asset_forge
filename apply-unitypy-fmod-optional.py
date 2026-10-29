from pathlib import Path

path = Path(
    "third_party/UnityPy/UnityPy/export/AudioClipConverter.py"
)
text = path.read_text()

old_import = "import fmod_toolkit\n"
old_return = "    return fmod_toolkit.raw_to_wav(\n"

new_import = """    try:
        import fmod_toolkit
    except ModuleNotFoundError as exc:
        if exc.name != "fmod_toolkit":
            raise
        raise RuntimeError(
            "FMOD audio conversion is unavailable in this build. "
            "Build with -PwithFmod=true and configure the FMOD SDK."
        ) from exc

    return fmod_toolkit.raw_to_wav(
"""

if old_import not in text:
    if "FMOD audio conversion is unavailable in this build." in text:
        print("UnityPy FMOD patch already applied.")
        raise SystemExit(0)
    raise SystemExit("ERROR: Expected FMOD import was not found.")

if text.count(old_import) != 1 or text.count(old_return) != 1:
    raise SystemExit("ERROR: Unexpected source layout; no changes made.")

text = text.replace(old_import, "", 1)
text = text.replace(old_return, new_import, 1)
path.write_text(text)

print("OK: FMOD import is now lazy.")
